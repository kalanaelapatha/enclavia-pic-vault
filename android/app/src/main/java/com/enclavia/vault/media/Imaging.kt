package com.enclavia.vault.media

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max

object Imaging {
  const val THUMB_SIZE = 384

  // ---------------------------------------------------------------- thumbnails from the gallery

  /** Thumbnail for a MediaStore item (used both for browsing and for creating vault thumbnails). */
  fun galleryThumbnail(ctx: Context, uri: Uri, isVideo: Boolean, size: Int = THUMB_SIZE): Bitmap? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      runCatching { return ctx.contentResolver.loadThumbnail(uri, Size(size, size), null) }
    } else {
      runCatching {
        val id = ContentUris.parseId(uri)
        @Suppress("DEPRECATION")
        val bmp = if (isVideo) {
          MediaStore.Video.Thumbnails.getThumbnail(ctx.contentResolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
        } else {
          MediaStore.Images.Thumbnails.getThumbnail(ctx.contentResolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
        }
        if (bmp != null) return bmp
      }
    }
    return if (isVideo) videoFrame(ctx, uri, size) else decodeSampledFromUri(ctx, uri, size)
  }

  private fun videoFrame(ctx: Context, uri: Uri, size: Int): Bitmap? = runCatching {
    val r = MediaMetadataRetriever()
    try {
      r.setDataSource(ctx, uri)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        r.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, size, size)
      } else {
        r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { scaleDown(it, size) }
      }
    } finally {
      r.release()
    }
  }.getOrNull()

  private fun decodeSampledFromUri(ctx: Context, uri: Uri, size: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, size) }
    val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
      ?: return null
    val orientation = ctx.contentResolver.openInputStream(uri)?.use {
      ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } ?: ExifInterface.ORIENTATION_NORMAL
    scaleDown(rotate(bmp, orientation), size)
  }.getOrNull()

  fun toJpeg(bmp: Bitmap, quality: Int = 82): ByteArray {
    val safe = if (bmp.config == Bitmap.Config.HARDWARE) bmp.copy(Bitmap.Config.ARGB_8888, false) else bmp
    return ByteArrayOutputStream(64 * 1024).also { safe.compress(Bitmap.CompressFormat.JPEG, quality, it) }
      .toByteArray()
  }

  // ---------------------------------------------------------------- decoding decrypted bytes

  fun decodeThumb(bytes: ByteArray): Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

  /** Decodes a full image (already decrypted, in memory) bounded by [maxSide], EXIF-orientated. */
  fun decodeFull(bytes: ByteArray, maxSide: Int): Bitmap? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      runCatching {
        val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
          val w = info.size.width
          val h = info.size.height
          val scale = maxSide.toFloat() / max(w, h)
          if (scale < 1f) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
          decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
      }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide) }
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
    val orientation = runCatching {
      ExifInterface(ByteArrayInputStream(bytes))
        .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    return rotate(bmp, orientation)
  }

  // ---------------------------------------------------------------- video metadata

  data class VideoMeta(val width: Int, val height: Int, val durationMs: Long)

  fun videoMeta(ctx: Context, uri: Uri): VideoMeta? = runCatching {
    val r = MediaMetadataRetriever()
    try {
      r.setDataSource(ctx, uri)
      var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
      var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
      val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
      if (rotation == 90 || rotation == 270) { val t = w; w = h; h = t }
      val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
      VideoMeta(w, h, d)
    } finally {
      r.release()
    }
  }.getOrNull()

  // ---------------------------------------------------------------- helpers

  private fun sampleSize(w: Int, h: Int, target: Int): Int {
    var sample = 1
    while (w / (sample * 2) >= target && h / (sample * 2) >= target) sample *= 2
    return sample
  }

  private fun scaleDown(bmp: Bitmap, size: Int): Bitmap {
    val longest = max(bmp.width, bmp.height)
    if (longest <= size) return bmp
    val s = size.toFloat() / longest
    return Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true)
  }

  private fun rotate(bmp: Bitmap, orientation: Int): Bitmap {
    val m = Matrix()
    when (orientation) {
      ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
      ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
      ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
      ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
      ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
      else -> return bmp
    }
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
  }
}
