package com.bizarrewind.doorsentinel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Singleton bitmap cache that loads thumbnails off the main thread
 * and keeps them in an LRU cache (~1/8 of max heap) so scrolling
 * through already-seen photos is instant.
 */
object ThumbnailCache {

    private val maxMemKB = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemKB / 8  // ~20-30 MB on most devices

    private val cache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int =
            bitmap.byteCount / 1024
    }

    private fun cacheKey(file: File): String =
        "${file.absolutePath}:${file.lastModified()}"

    /** Fast synchronous lookup — returns null on cache miss. */
    fun getFromCache(file: File): Bitmap? =
        cache.get(cacheKey(file))

    /** Loads a thumbnail asynchronously. Returns cached bitmap if available. */
    suspend fun getThumbnail(file: File, reqSize: Int = 180): Bitmap? {
        val key = cacheKey(file)
        cache.get(key)?.let { return it }

        return withContext(Dispatchers.IO) {
            val bmp = decodeBitmapWithExif(file, reqSize, reqSize) ?: return@withContext null
            cache.put(key, bmp)
            bmp
        }
    }

    /** Loads full-resolution (for the image viewer). Not cached. */
    suspend fun getFullResolution(file: File, maxDim: Int = 2048): Bitmap? =
        withContext(Dispatchers.IO) {
            decodeBitmapWithExif(file, maxDim, maxDim)
        }

    // ── Decode helpers ───────────────────────────────────────────────────────

    fun decodeBitmapWithExif(file: File, reqW: Int, reqH: Int): Bitmap? {
        if (!file.exists()) return null

        val opts = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.absolutePath, this)
            inSampleSize       = calcInSampleSize(outWidth, outHeight, reqW, reqH)
            inJustDecodeBounds = false
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null

        return try {
            val exif = ExifInterface(file.absolutePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90  -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (degrees != 0f) {
                val mx = Matrix().apply { postRotate(degrees) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, mx, true)
            } else bitmap
        } catch (_: Exception) {
            bitmap
        }
    }

    private fun calcInSampleSize(w: Int, h: Int, reqW: Int, reqH: Int): Int {
        var sample = 1
        if (h > reqH || w > reqW) {
            val halfH = h / 2
            val halfW = w / 2
            while (halfH / sample >= reqH && halfW / sample >= reqW) {
                sample *= 2
            }
        }
        return sample
    }
}
