package com.enclavia.vault.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.enclavia.vault.crypto.EncryptedFile
import com.enclavia.vault.data.VaultDatabase
import com.enclavia.vault.data.VaultItem
import com.enclavia.vault.data.VaultPaths
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

/** Moves media between the shared gallery and the encrypted vault. */
class MediaTransfer(private val ctx: Context) {

  private val resolver = ctx.contentResolver

  private data class SourceMeta(
    val name: String, val mime: String, val size: Long,
    val width: Int, val height: Int, val durationMs: Long, val takenAt: Long,
  )

  /**
   * Copies one gallery item into the vault: stream-encrypt -> decrypt & hash-compare -> thumbnail -> DB row.
   * The original is untouched; deleting it is a separate, user-confirmed step.
   */
  fun import(uri: Uri, albumId: String?, dek: ByteArray): VaultItem {
    val meta = readMeta(uri)
    val type = when {
      meta.mime.startsWith("image/") -> "image"
      meta.mime.startsWith("video/") -> "video"
      else -> throw IOException("Unsupported file type")
    }
    ensureFreeSpace(meta.size)

    val id = UUID.randomUUID().toString()
    val part = File(VaultPaths.blobs(ctx), "$id.part")
    val blob = VaultPaths.blob(ctx, id)
    val thumb = VaultPaths.thumb(ctx, id)
    try {
      val sourceHash = MessageDigest.getInstance("SHA-256")
      val written = resolver.openInputStream(uri)?.use { EncryptedFile.encrypt(it, part, dek, sourceHash) }
        ?: throw IOException("Cannot open source")
      if (written == 0L) throw IOException("Source file is empty")

      // Verify: decrypt what we wrote and compare hashes before the original can ever be deleted.
      val verifyHash = MessageDigest.getInstance("SHA-256")
      val read = EncryptedFile.decryptTo(part, dek, NullOutputStream, verifyHash)
      if (read != written || !MessageDigest.isEqual(sourceHash.digest(), verifyHash.digest())) {
        throw IOException("Verification failed")
      }
      if (!part.renameTo(blob)) throw IOException("Cannot finalise file")

      var width = meta.width
      var height = meta.height
      var duration = meta.durationMs
      if (type == "video" && (duration <= 0 || width <= 0)) {
        Imaging.videoMeta(ctx, uri)?.let { width = it.width; height = it.height; duration = it.durationMs }
      }

      val hasThumb = runCatching {
        val bmp = Imaging.galleryThumbnail(ctx, uri, type == "video") ?: return@runCatching false
        EncryptedFile.encryptBytes(Imaging.toJpeg(bmp), thumb, dek)
        true
      }.getOrDefault(false)

      val item = VaultItem(
        id = id, albumId = albumId, type = type, mime = meta.mime, name = meta.name,
        size = written, width = width, height = height, durationMs = duration,
        takenAt = meta.takenAt, importedAt = System.currentTimeMillis(), hasThumb = hasThumb,
      )
      VaultDatabase.get(ctx).insertItem(item, dek)
      return item
    } catch (e: Throwable) {
      part.delete(); blob.delete(); thumb.delete()
      throw e
    }
  }

  /** Writes a decrypted copy back to the shared gallery (Pictures/Enclavia or Movies/Enclavia). */
  fun export(item: VaultItem, dek: ByteArray): Uri {
    val blob = VaultPaths.blob(ctx, item.id)
    val isVideo = item.type == "video"
    val folder = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val collection = if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
      else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
      val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
        put(MediaStore.MediaColumns.MIME_TYPE, item.mime)
        put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/Enclavia")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
        if (item.takenAt > 0) put(MediaStore.MediaColumns.DATE_TAKEN, item.takenAt)
      }
      val uri = resolver.insert(collection, values) ?: throw IOException("Gallery refused the file")
      try {
        resolver.openOutputStream(uri)?.use { EncryptedFile.decryptTo(blob, dek, it) }
          ?: throw IOException("Cannot write to gallery")
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
      } catch (e: Throwable) {
        resolver.delete(uri, null, null)
        throw e
      }
    }

    @Suppress("DEPRECATION")
    val dir = File(Environment.getExternalStoragePublicDirectory(folder), "Enclavia").apply { mkdirs() }
    val target = uniqueFile(dir, item.name)
    try {
      FileOutputStream(target).use { EncryptedFile.decryptTo(blob, dek, it) }
    } catch (e: Throwable) {
      target.delete()
      throw e
    }
    MediaScannerConnection.scanFile(ctx, arrayOf(target.absolutePath), arrayOf(item.mime), null)
    return Uri.fromFile(target)
  }

  private fun readMeta(uri: Uri): SourceMeta {
    resolver.query(uri, null, null, null, null)?.use { c ->
      if (c.moveToFirst()) {
        fun str(col: String) = c.getColumnIndex(col).takeIf { it >= 0 }?.let { c.getString(it) }
        fun long(col: String) = c.getColumnIndex(col).takeIf { it >= 0 }?.let { c.getLong(it) } ?: 0L
        val name = str(OpenableColumns.DISPLAY_NAME) ?: "file"
        val mime = str(MediaStore.MediaColumns.MIME_TYPE) ?: resolver.getType(uri) ?: ""
        val taken = long("datetaken").takeIf { it > 0 }
          ?: (long(MediaStore.MediaColumns.DATE_MODIFIED) * 1000).takeIf { it > 0 }
          ?: System.currentTimeMillis()
        return SourceMeta(
          name = sanitiseName(name), mime = mime, size = long(OpenableColumns.SIZE),
          width = long(MediaStore.MediaColumns.WIDTH).toInt(), height = long(MediaStore.MediaColumns.HEIGHT).toInt(),
          durationMs = long("duration"), takenAt = taken,
        )
      }
    }
    throw IOException("Item no longer exists in the gallery")
  }

  private fun ensureFreeSpace(size: Long) {
    val free = StatFs(VaultPaths.root(ctx).absolutePath).availableBytes
    if (free < size + 64L * 1024 * 1024) throw IOException("Not enough free storage")
  }

  private fun uniqueFile(dir: File, name: String): File {
    var f = File(dir, name)
    if (!f.exists()) return f
    val dot = name.lastIndexOf('.')
    val base = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var i = 1
    while (f.exists()) f = File(dir, "$base ($i)$ext").also { i++ }
    return f
  }

  private fun sanitiseName(name: String): String =
    name.replace(Regex("[/\\\\:*?\"<>|\\u0000-\\u001F]"), "_").take(200).ifBlank { "file" }

  private object NullOutputStream : OutputStream() {
    override fun write(b: Int) = Unit
    override fun write(b: ByteArray, off: Int, len: Int) = Unit
  }
}
