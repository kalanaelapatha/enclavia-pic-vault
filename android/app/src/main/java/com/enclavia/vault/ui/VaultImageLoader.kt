package com.enclavia.vault.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.enclavia.vault.crypto.EncryptedFile
import com.enclavia.vault.crypto.KeyManager
import com.enclavia.vault.data.VaultPaths
import com.enclavia.vault.media.Imaging
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Decrypts and decodes images off the main thread, entirely in memory.
 * LIFO scheduling keeps a fast-scrolling grid responsive: what is on screen now loads first.
 * All caches are dropped the moment the vault locks.
 */
object VaultImageLoader {

  data class Request(
    val itemId: String,
    val galleryUri: String,
    val galleryIsVideo: Boolean,
    val full: Boolean,
    val maxSide: Int,
  ) {
    val key: String = when {
      itemId.isNotEmpty() && full -> "f:$itemId:$maxSide"
      itemId.isNotEmpty() -> "t:$itemId"
      else -> "g:$galleryUri"
    }
    val isEmpty get() = itemId.isEmpty() && galleryUri.isEmpty()
  }

  private val main = Handler(Looper.getMainLooper())
  private val maxMemory = Runtime.getRuntime().maxMemory()

  private val thumbCache = object : LruCache<String, Bitmap>((maxMemory / 8).toInt()) {
    override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
  }
  private val fullCache = object : LruCache<String, Bitmap>((maxMemory / 5).toInt()) {
    override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
  }

  private val queue = object : LinkedBlockingDeque<Runnable>() {
    override fun offer(e: Runnable): Boolean = offerFirst(e)
  }
  private val executor = ThreadPoolExecutor(3, 3, 30, TimeUnit.SECONDS, queue) { r ->
    Thread(r, "vault-image").apply { priority = Thread.NORM_PRIORITY - 1 }
  }.apply { allowCoreThreadTimeOut(true) }

  @Volatile private var registered = false

  fun ensureLockHook(ctx: Context) {
    if (registered) return
    registered = true
    KeyManager.get(ctx).addLockListener { clear() }
  }

  fun cached(req: Request): Bitmap? = cacheFor(req).get(req.key)

  fun load(ctx: Context, req: Request, callback: (Bitmap?) -> Unit): Future<*> {
    val app = ctx.applicationContext
    ensureLockHook(app)
    return executor.submit {
      val bmp = runCatching { decode(app, req) }.getOrNull()
      if (bmp != null) cacheFor(req).put(req.key, bmp)
      if (!Thread.currentThread().isInterrupted) main.post { callback(bmp) }
    }
  }

  fun clear() {
    thumbCache.evictAll()
    fullCache.evictAll()
  }

  private fun cacheFor(req: Request) = if (req.full) fullCache else thumbCache

  private fun decode(ctx: Context, req: Request): Bitmap? {
    if (req.galleryUri.isNotEmpty()) {
      return Imaging.galleryThumbnail(ctx, Uri.parse(req.galleryUri), req.galleryIsVideo)
    }
    val keys = KeyManager.get(ctx)
    return keys.withDek { dek ->
      if (req.full) {
        val bytes = EncryptedFile.decryptToBytes(VaultPaths.blob(ctx, req.itemId), dek)
        try {
          Imaging.decodeFull(bytes, req.maxSide)
        } finally {
          bytes.fill(0)
        }
      } else {
        val file = VaultPaths.thumb(ctx, req.itemId)
        if (!file.exists()) return@withDek null
        val bytes = EncryptedFile.decryptToBytes(file, dek)
        try {
          Imaging.decodeThumb(bytes)
        } finally {
          bytes.fill(0)
        }
      }
    }
  }
}
