package com.pixelcam.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Post-processing that gives photos a punchy, warm, sharp "phone flagship" look. */
object ImageEnhancer {

    fun saveToGallery(context: Context, src: File, enhance: Boolean) {
        val name = "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PixelCam")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "PixelCam"
                )
                dir.mkdirs()
                put(MediaStore.Images.Media.DATA, File(dir, "$name.jpg").absolutePath)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create gallery entry")
        resolver.openOutputStream(uri)?.use { out: OutputStream ->
            if (enhance) {
                val bmp = enhance(src)
                bmp.compress(Bitmap.CompressFormat.JPEG, 96, out)
                bmp.recycle()
            } else {
                src.inputStream().use { it.copyTo(out) }
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        }
    }

    private fun enhance(file: File): Bitmap {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        var bmp = BitmapFactory.decodeFile(file.absolutePath, opts)

        // Apply EXIF rotation
        val rotation = when (ExifInterface(file.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotation != 0f) {
            val m = Matrix().apply { postRotate(rotation) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            bmp.recycle()
            bmp = rotated
        }

        // Colour grade: slightly richer colour, more contrast, gentle warmth, small shadow lift
        val sat = ColorMatrix().apply { setSaturation(1.12f) }
        val c = 1.08f
        val t = (1f - c) * 128f + 5f
        val con = ColorMatrix(
            floatArrayOf(
                c * 1.02f, 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c * 0.97f, 0f, t,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val cm = ColorMatrix().apply { postConcat(sat); postConcat(con) }
        val graded = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        Canvas(graded).drawBitmap(bmp, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
        bmp.recycle()

        val sharp = sharpen(graded, 0.6f)
        graded.recycle()
        return sharp
    }

    /** Unsharp mask using a 3x3 box blur, processed row by row to keep memory low. */
    private fun sharpen(src: Bitmap, amount: Float): Bitmap {
        val w = src.width
        val h = src.height
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        var prev = IntArray(w)
        var cur = IntArray(w)
        var next = IntArray(w)
        val result = IntArray(w)
        val n = IntArray(9)
        src.getPixels(prev, 0, w, 0, 0, w, 1)
        src.getPixels(cur, 0, w, 0, 0, w, 1)
        for (y in 0 until h) {
            val ny = if (y + 1 < h) y + 1 else y
            src.getPixels(next, 0, w, 0, ny, w, 1)
            for (x in 0 until w) {
                val xl = if (x > 0) x - 1 else x
                val xr = if (x < w - 1) x + 1 else x
                n[0] = prev[xl]; n[1] = prev[x]; n[2] = prev[xr]
                n[3] = cur[xl]; n[4] = cur[x]; n[5] = cur[xr]
                n[6] = next[xl]; n[7] = next[x]; n[8] = next[xr]
                val center = cur[x]
                var outPx = center and (0xFF shl 24)
                var shift = 16
                while (shift >= 0) {
                    var sum = 0
                    for (i in 0 until 9) sum += (n[i] shr shift) and 0xFF
                    val blur = sum / 9
                    val orig = (center shr shift) and 0xFF
                    var v = (orig + amount * (orig - blur)).toInt()
                    if (v < 0) v = 0 else if (v > 255) v = 255
                    outPx = outPx or (v shl shift)
                    shift -= 8
                }
                result[x] = outPx
            }
            out.setPixels(result, 0, w, 0, y, w, 1)
            val tmp = prev; prev = cur; cur = next; next = tmp
        }
        return out
    }
}
