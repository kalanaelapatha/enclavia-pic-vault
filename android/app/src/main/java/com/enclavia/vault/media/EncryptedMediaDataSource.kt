package com.enclavia.vault.media

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.Build
import com.enclavia.vault.crypto.EncryptedFile
import java.io.File

/**
 * Lets Android's media framework (MediaExtractor, MediaMetadataRetriever) read a vault
 * video directly. Bytes are decrypted on demand, in memory.
 */
class EncryptedMediaDataSource(file: File, dek: ByteArray) : MediaDataSource() {
  private val reader = EncryptedFile.Reader(file, dek)

  @Synchronized
  override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
    if (position >= reader.plainLength) return -1
    var total = 0
    while (total < size) {
      val n = reader.read(position + total, buffer, offset + total, size - total)
      if (n <= 0) break
      total += n
    }
    return if (total == 0) -1 else total
  }

  override fun getSize(): Long = reader.plainLength

  @Synchronized
  override fun close() = reader.close()

  companion object {
    /** A video frame (scaled to fit [size]) taken straight from an encrypted vault file. */
    fun frameAt(file: File, dek: ByteArray, timeUs: Long, size: Int): Bitmap? {
      val source = EncryptedMediaDataSource(file, dek)
      val r = MediaMetadataRetriever()
      try {
        r.setDataSource(source)
        val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
          r.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, size, size)
        } else {
          r.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { Imaging.scaleDown(it, size) }
        }
        return frame
      } finally {
        r.release()
        source.close()
      }
    }

    fun durationMs(file: File, dek: ByteArray): Long {
      val source = EncryptedMediaDataSource(file, dek)
      val r = MediaMetadataRetriever()
      try {
        r.setDataSource(source)
        return r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
      } finally {
        r.release()
        source.close()
      }
    }
  }
}
