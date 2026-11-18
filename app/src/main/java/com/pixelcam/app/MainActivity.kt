package com.pixelcam.app

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Range
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.pixelcam.app.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private var provider: ProcessCameraProvider? = null
    private var extMgr: ExtensionsManager? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var videoMode = false
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var timerSec = 0
    private var gridOn = false
    private var lookOn = true
    private var qualityIdx = 0 // 0 = 1080p60, 1 = 1080p30, 2 = 4K30
    private var extIdx = 0

    private val extModes = listOf(
        "AUTO" to ExtensionMode.NONE,
        "HDR" to ExtensionMode.HDR,
        "NIGHT" to ExtensionMode.NIGHT,
        "PORTRAIT" to ExtensionMode.BOKEH
    )
    private val qualityLabels = listOf("1080p 60fps", "1080p 30fps", "4K 30fps")

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        if (res[Manifest.permission.CAMERA] == true || hasPerm(Manifest.permission.CAMERA)) startCamera()
        else Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
    }

    private fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setupUi()
        setupGestures()
        permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    private fun setupUi() {
        b.btnFlash.setOnClickListener {
            flashMode = when (flashMode) {
                ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                else -> ImageCapture.FLASH_MODE_OFF
            }
            b.btnFlash.text = when (flashMode) {
                ImageCapture.FLASH_MODE_AUTO -> "⚡ AUTO"
                ImageCapture.FLASH_MODE_ON -> "⚡ ON"
                else -> "⚡ OFF"
            }
            imageCapture?.flashMode = flashMode
            if (videoMode) camera?.cameraControl?.enableTorch(flashMode == ImageCapture.FLASH_MODE_ON)
        }
        b.btnTimer.setOnClickListener {
            timerSec = when (timerSec) { 0 -> 3; 3 -> 10; else -> 0 }
            b.btnTimer.text = if (timerSec == 0) "⏱ OFF" else "⏱ ${timerSec}s"
        }
        b.btnGrid.setOnClickListener {
            gridOn = !gridOn
            b.grid.visibility = if (gridOn) View.VISIBLE else View.GONE
            b.btnGrid.text = if (gridOn) "▦ ON" else "▦ OFF"
        }
        b.btnLook.setOnClickListener {
            lookOn = !lookOn
            b.btnLook.text = if (lookOn) "✨ VIVID" else "✨ RAW"
        }
        b.btnExt.setOnClickListener {
            extIdx = (extIdx + 1) % extModes.size
            b.btnExt.text = extModes[extIdx].first
            bindCamera()
        }
        b.btnQuality.setOnClickListener {
            qualityIdx = (qualityIdx + 1) % 3
            b.btnQuality.text = qualityLabels[qualityIdx]
            bindCamera()
        }
        b.btnPhoto.setOnClickListener { setVideoMode(false) }
        b.btnVideo.setOnClickListener { setVideoMode(true) }
        b.btnFlip.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            bindCamera()
        }
        b.shutter.setOnClickListener {
            if (videoMode) toggleRecord() else startPhotoWithTimer()
        }
        b.exposure.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val range = camera?.cameraInfo?.exposureState?.exposureCompensationRange ?: return
                camera?.cameraControl?.setExposureCompensationIndex(range.lower + p)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun setupGestures() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val c = camera ?: return true
                val z = c.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                c.cameraControl.setZoomRatio(z * d.scaleFactor)
                return true
            }
        })
        val tap = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val pt = b.preview.meteringPointFactory.createPoint(e.x, e.y)
                camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(pt).build())
                showFocusRing(e.x, e.y)
                return true
            }
        })
        b.preview.setOnTouchListener { v, e ->
            scale.onTouchEvent(e)
            tap.onTouchEvent(e)
            if (e.action == MotionEvent.ACTION_UP) v.performClick()
            true
        }
    }

    private fun showFocusRing(x: Float, y: Float) {
        val r = b.focusRing
        r.x = x - r.width / 2f
        r.y = y - r.height / 2f
        r.visibility = View.VISIBLE
        handler.removeCallbacksAndMessages("ring")
        handler.postAtTime({ r.visibility = View.GONE }, "ring", android.os.SystemClock.uptimeMillis() + 900)
    }

    private fun setVideoMode(video: Boolean) {
        if (recording != null) return
        videoMode = video
        b.btnQuality.visibility = if (video) View.VISIBLE else View.GONE
        b.btnExt.visibility = if (video) View.GONE else View.VISIBLE
        b.btnLook.visibility = if (video) View.GONE else View.VISIBLE
        bindCamera()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            provider = future.get()
            try {
                val ef = ExtensionsManager.getInstanceAsync(this, provider!!)
                ef.addListener({
                    try { extMgr = ef.get() } catch (_: Exception) {}
                    bindCamera()
                }, ContextCompat.getMainExecutor(this))
            } catch (_: Exception) {
                bindCamera()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera(allow60: Boolean = true) {
        val p = provider ?: return
        p.unbindAll()

        var selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val mode = extModes[extIdx].second
        val em = extMgr
        if (!videoMode && em != null && mode != ExtensionMode.NONE) {
            if (em.isExtensionAvailable(selector, mode)) {
                selector = em.getExtensionEnabledCameraSelector(selector, mode)
            } else {
                Toast.makeText(this, "${extModes[extIdx].first} not supported by this camera", Toast.LENGTH_SHORT).show()
                extIdx = 0
                b.btnExt.text = extModes[0].first
            }
        }

        val preview = Preview.Builder().build().also { it.setSurfaceProvider(b.preview.surfaceProvider) }

        try {
            if (videoMode) {
                val target = if (qualityIdx == 2) Quality.UHD else Quality.FHD
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(target, FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD))
                    ).build()
                val vb = VideoCapture.Builder(recorder)
                if (qualityIdx == 0 && allow60) vb.setTargetFrameRate(Range(60, 60))
                else vb.setTargetFrameRate(Range(30, 30))
                videoCapture = vb.build()
                camera = p.bindToLifecycle(this, selector, preview, videoCapture!!)
                camera?.cameraControl?.enableTorch(flashMode == ImageCapture.FLASH_MODE_ON)
            } else {
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setJpegQuality(100)
                    .setFlashMode(flashMode)
                    .build()
                camera = p.bindToLifecycle(this, selector, preview, imageCapture!!)
            }
        } catch (e: Exception) {
            if (videoMode && qualityIdx == 0 && allow60) {
                Toast.makeText(this, "60fps not available here, using 30fps", Toast.LENGTH_LONG).show()
                bindCamera(false)
            } else {
                Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_LONG).show()
            }
            return
        }

        // Exposure slider setup
        camera?.cameraInfo?.exposureState?.let { st ->
            val r = st.exposureCompensationRange
            b.exposure.max = r.upper - r.lower
            b.exposure.progress = st.exposureCompensationIndex - r.lower
            b.exposure.isEnabled = st.isExposureCompensationSupported
        }
    }

    private fun startPhotoWithTimer() {
        if (timerSec == 0) { takePhoto(); return }
        b.shutter.isEnabled = false
        b.countdown.visibility = View.VISIBLE
        var left = timerSec
        val tick = object : Runnable {
            override fun run() {
                if (left > 0) {
                    b.countdown.text = left.toString()
                    left--
                    handler.postDelayed(this, 1000)
                } else {
                    b.countdown.visibility = View.GONE
                    b.shutter.isEnabled = true
                    takePhoto()
                }
            }
        }
        tick.run()
    }

    private fun takePhoto() {
        val ic = imageCapture ?: return
        val tmp = File(cacheDir, "tmp_${System.currentTimeMillis()}.jpg")
        ic.takePicture(
            ImageCapture.OutputFileOptions.Builder(tmp).build(),
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                    try {
                        ImageEnhancer.saveToGallery(this@MainActivity, tmp, lookOn)
                        runOnUiThread { Toast.makeText(this@MainActivity, "Photo saved", Toast.LENGTH_SHORT).show() }
                    } catch (e: Throwable) {
                        runOnUiThread { Toast.makeText(this@MainActivity, "Save failed: ${e.message}", Toast.LENGTH_LONG).show() }
                    } finally {
                        tmp.delete()
                    }
                }

                override fun onError(e: ImageCaptureException) {
                    runOnUiThread { Toast.makeText(this@MainActivity, "Capture failed: ${e.message}", Toast.LENGTH_LONG).show() }
                }
            }
        )
    }

    private fun toggleRecord() {
        val vc = videoCapture ?: return
        val active = recording
        if (active != null) {
            active.stop()
            recording = null
            return
        }
        val name = "VID_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (android.os.Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/PixelCam")
        }
        val opts = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(cv).build()
        var pending = vc.output.prepareRecording(this, opts)
        if (hasPerm(Manifest.permission.RECORD_AUDIO)) pending = pending.withAudioEnabled()
        recording = pending.start(ContextCompat.getMainExecutor(this)) { ev ->
            when (ev) {
                is VideoRecordEvent.Start -> b.shutter.setBackgroundResource(R.drawable.shutter_rec)
                is VideoRecordEvent.Finalize -> {
                    b.shutter.setBackgroundResource(R.drawable.shutter_bg)
                    recording = null
                    val msg = if (ev.hasError()) "Recording error (${ev.error})" else "Video saved"
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
                else -> {}
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        recording?.stop()
        executor.shutdown()
    }
}
