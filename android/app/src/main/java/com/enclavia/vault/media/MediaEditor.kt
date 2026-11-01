package com.enclavia.vault.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.Build
import android.system.Os
import android.system.OsConstants
import androidx.exifinterface.media.ExifInterface
import com.enclavia.vault.crypto.EncryptedFile
import com.enclavia.vault.data.VaultDatabase
import com.enclavia.vault.data.VaultItem
import com.enclavia.vault.data.VaultPaths
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.roundToInt

/**
 * In-vault editing: crop/rotate photos and cut videos.
 * Results are re-encrypted and verified; the result is either a new item or replaces the original.
 */
class MediaEditor(private val ctx: Context) {

  data class MediaInfo(val width: Int, val height: Int, val durationMs: Long)

  companion object {
    /** Cropping works on an image of at most this size (12 MP photos are 4000 px). */
    private const val MAX_EDIT_SIDE = 4096
    /** Below this size the cut video is assembled in RAM only (memfd) on Android 11+. */
    private const val MEMFD_LIMIT = 400L * 1024 * 1024
  }

  private val db get() = VaultDatabase.get(ctx)

  // ================================================================ info

  /** Display (EXIF-oriented) dimensions, and duration for videos. */
  fun info(item: VaultItem, dek: ByteArray): MediaInfo {
    val blob = VaultPaths.blob(ctx, item.id)
    if (item.type == "video") {
      val source = EncryptedMediaDataSource(blob, dek)
      val r = MediaMetadataRetriever()
      try {
        r.setDataSource(source)
        var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: item.width
        var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: item.height
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
        val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: item.durationMs
        return MediaInfo(w, h, d)
      } finally {
        r.release()
        source.close()
      }
    }
    val bytes = EncryptedFile.decryptToBytes(blob, dek)
    try {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
      val orientation = runCatching {
        ExifInterface(ByteArrayInputStream(bytes))
          .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
      }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
      val swap = orientation in setOf(
        ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270,
        ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE,
      )
      return if (swap) MediaInfo(bounds.outHeight, bounds.outWidth, 0) else MediaInfo(bounds.outWidth, bounds.outHeight, 0)
    } finally {
      bytes.fill(0)
    }
  }

  // ================================================================ crop

  /**
   * Rotates the photo by [rotation] (0/90/180/270, clockwise) then crops the normalised rect
   * (0..1, relative to the rotated photo). Metadata such as GPS location is not carried over.
   */
  fun crop(
    item: VaultItem, rotation: Int, nx: Double, ny: Double, nw: Double, nh: Double,
    replace: Boolean, dek: ByteArray,
  ): VaultItem {
    require(item.type == "image") { "Not a photo" }
    require(rotation in setOf(0, 90, 180, 270)) { "Invalid rotation" }
    require(nw > 0 && nh > 0) { "Empty crop" }

    val bytes = EncryptedFile.decryptToBytes(VaultPaths.blob(ctx, item.id), dek)
    val source = try {
      Imaging.decodeFull(bytes, MAX_EDIT_SIDE) ?: throw IOException("Cannot read photo")
    } finally {
      bytes.fill(0)
    }
    val rotated = if (rotation == 0) source else
      Bitmap.createBitmap(source, 0, 0, source.width, source.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)

    val x = (nx.coerceIn(0.0, 1.0) * rotated.width).roundToInt().coerceIn(0, rotated.width - 1)
    val y = (ny.coerceIn(0.0, 1.0) * rotated.height).roundToInt().coerceIn(0, rotated.height - 1)
    val w = (nw * rotated.width).roundToInt().coerceIn(1, rotated.width - x)
    val h = (nh * rotated.height).roundToInt().coerceIn(1, rotated.height - y)
    val cropped = Bitmap.createBitmap(rotated, x, y, w, h)

    val png = item.mime == "image/png"
    val encoded = ByteArrayOutputStream(w * h / 2).also {
      cropped.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, it)
    }.toByteArray()
    val thumb = Imaging.toJpeg(Imaging.scaleDown(cropped, Imaging.THUMB_SIZE))
    val mime = if (png) "image/png" else "image/jpeg"
    val name = editedName(item.name, if (png) ".png" else ".jpg", "crop", replace)
    try {
      return commit(item, replace, encoded.inputStream(), thumb, mime, name, w, h, 0L, dek)
    } finally {
      encoded.fill(0)
    }
  }

  // ================================================================ cut

  /**
   * Cuts the video to [startMs, endMs] without re-encoding (fast and lossless).
   * The cut starts at the keyframe at or before [startMs].
   */
  fun trim(item: VaultItem, startMs: Long, endMs: Long, replace: Boolean, dek: ByteArray): VaultItem {
    require(item.type == "video") { "Not a video" }
    require(endMs - startMs >= 500) { "Clip is too short" }
    val blob = VaultPaths.blob(ctx, item.id)

    val estimate = (item.size.toDouble() * (endMs - startMs) / item.durationMs.coerceAtLeast(1)).toLong()
    val useMemory = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && estimate < MEMFD_LIMIT
    val tmpFile = File(VaultPaths.blobs(ctx), "${UUID.randomUUID()}.part")
    val fd: FileDescriptor = if (useMemory) {
      Os.memfd_create("enclavia-cut", 0) // anonymous RAM-backed file: never touches storage
    } else {
      Os.open(tmpFile.absolutePath, OsConstants.O_RDWR or OsConstants.O_CREAT or OsConstants.O_TRUNC, 0x180)
    }

    try {
      val outDurationMs = remux(blob, dek, fd, startMs * 1000, endMs * 1000) / 1000
      Os.lseek(fd, 0, OsConstants.SEEK_SET)

      val info = info(item, dek)

      // Thumbnail from the cut itself (read straight from the RAM/temp copy).
      val thumb = runCatching {
        val r = MediaMetadataRetriever()
        try {
          r.setDataSource(fd)
          val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1)
            r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, Imaging.THUMB_SIZE, Imaging.THUMB_SIZE)
          else r.getFrameAtTime(0)?.let { Imaging.scaleDown(it, Imaging.THUMB_SIZE) }
          bmp?.let { Imaging.toJpeg(it) }
        } finally {
          r.release()
        }
      }.getOrNull()

      Os.lseek(fd, 0, OsConstants.SEEK_SET)
      val name = editedName(item.name, ".mp4", "cut", replace)
      return FdInputStream(fd).use { input ->
        commit(item, replace, input, thumb, "video/mp4", name, info.width, info.height, outDurationMs, dek)
      }
    } finally {
      runCatching { Os.close(fd) }
      tmpFile.delete()
    }
  }

  /** Copies samples in [startUs, endUs] into an MP4 written to [out]. Returns the output duration (µs). */
  private fun remux(blob: File, dek: ByteArray, out: FileDescriptor, startUs: Long, endUs: Long): Long {
    val source = EncryptedMediaDataSource(blob, dek)
    val extractor = MediaExtractor()
    var muxer: MediaMuxer? = null
    try {
      extractor.setDataSource(source)
      muxer = MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
      val trackMap = HashMap<Int, Int>()
      var videoTrack = -1
      var maxInput = 1 * 1024 * 1024
      for (i in 0 until extractor.trackCount) {
        val format = extractor.getTrackFormat(i)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
        if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
        if (mime.startsWith("video/")) {
          if (videoTrack >= 0) continue
          videoTrack = i
          if (format.containsKey(MediaFormat.KEY_ROTATION)) muxer.setOrientationHint(format.getInteger(MediaFormat.KEY_ROTATION))
        }
        if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
          maxInput = maxOf(maxInput, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
        }
        trackMap[i] = try {
          muxer.addTrack(format)
        } catch (e: Exception) {
          throw IOException("This video format can’t be cut")
        }
        extractor.selectTrack(i)
      }
      if (trackMap.isEmpty()) throw IOException("No playable tracks")
      muxer.start()

      extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
      val buffer = ByteBuffer.allocateDirect(maxInput)
      val info = MediaCodec.BufferInfo()
      var base = -1L
      var lastPts = 0L
      val finished = HashSet<Int>()
      while (finished.size < trackMap.size) {
        val track = extractor.sampleTrackIndex
        if (track < 0) break
        val time = extractor.sampleTime
        val isVideo = track == videoTrack
        if (base < 0) {
          // The cut begins at the first video keyframe (or first sample if there's no video).
          if (isVideo || videoTrack < 0) base = time else { extractor.advance(); continue }
        }
        if (time > endUs) {
          finished.add(track)
          extractor.advance()
          continue
        }
        if (time < base || track in finished) {
          extractor.advance()
          continue
        }
        info.offset = 0
        info.size = extractor.readSampleData(buffer, 0)
        if (info.size < 0) break
        info.presentationTimeUs = time - base
        info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        muxer.writeSampleData(trackMap.getValue(track), buffer, info)
        lastPts = maxOf(lastPts, info.presentationTimeUs)
        extractor.advance()
      }
      muxer.stop()
      return lastPts
    } finally {
      runCatching { muxer?.release() }
      extractor.release()
      source.close()
    }
  }

  // ================================================================ shared

  /** Encrypts [content], verifies it, then inserts a new item or swaps it in place of the original. */
  private fun commit(
    original: VaultItem, replace: Boolean, content: InputStream, thumbJpeg: ByteArray?,
    mime: String, name: String, width: Int, height: Int, durationMs: Long, dek: ByteArray,
  ): VaultItem {
    val id = if (replace) original.id else UUID.randomUUID().toString()
    val part = File(VaultPaths.blobs(ctx), "$id.${UUID.randomUUID()}.part")
    val thumbPart = File(VaultPaths.thumbs(ctx), "$id.${UUID.randomUUID()}.part")
    try {
      val hash = MessageDigest.getInstance("SHA-256")
      val written = EncryptedFile.encrypt(content, part, dek, hash)
      if (written == 0L) throw IOException("Edit produced an empty file")
      val check = MessageDigest.getInstance("SHA-256")
      val read = EncryptedFile.decryptTo(part, dek, NullStream, check)
      if (read != written || !MessageDigest.isEqual(hash.digest(), check.digest())) throw IOException("Verification failed")

      val hasThumb = thumbJpeg != null
      if (thumbJpeg != null) EncryptedFile.encryptBytes(thumbJpeg, thumbPart, dek)

      val now = System.currentTimeMillis()
      val item = original.copy(
        id = id, mime = mime, name = name, size = written, width = width, height = height,
        durationMs = durationMs, importedAt = now, hasThumb = hasThumb,
      )
      if (!part.renameTo(VaultPaths.blob(ctx, id))) throw IOException("Cannot save edit")
      if (hasThumb) thumbPart.renameTo(VaultPaths.thumb(ctx, id)) else VaultPaths.thumb(ctx, id).delete()
      if (replace) db.updateItem(item, dek) else db.insertItem(item, dek)
      return item
    } finally {
      part.delete()
      thumbPart.delete()
    }
  }

  private fun editedName(name: String, ext: String, tag: String, replace: Boolean): String {
    val dot = name.lastIndexOf('.')
    val base = if (dot > 0) name.substring(0, dot) else name
    return if (replace) "$base$ext" else "${base}_$tag$ext"
  }

  private object NullStream : OutputStream() {
    override fun write(b: Int) = Unit
    override fun write(b: ByteArray, off: Int, len: Int) = Unit
  }

  /** InputStream over a raw descriptor that we close ourselves. */
  private class FdInputStream(private val fd: FileDescriptor) : InputStream() {
    private val inner = FileInputStream(fd)
    override fun read(): Int = inner.read()
    override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
    override fun close() = Unit
  }
}
