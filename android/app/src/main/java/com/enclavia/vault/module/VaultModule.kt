package com.enclavia.vault.module

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.StatFs
import android.provider.MediaStore
import com.enclavia.vault.crypto.KeyManager
import com.enclavia.vault.crypto.VaultLockedException
import com.enclavia.vault.data.VaultAlbum
import com.enclavia.vault.data.VaultDatabase
import com.enclavia.vault.data.VaultItem
import com.enclavia.vault.data.VaultPaths
import com.enclavia.vault.media.Gallery
import com.enclavia.vault.media.MediaEditor
import com.enclavia.vault.media.MediaTransfer
import com.enclavia.vault.specs.NativeVaultSpec
import com.enclavia.vault.ui.VaultImageLoader
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.BaseActivityEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.UiThreadUtil
import com.facebook.react.bridge.WritableMap
import java.util.concurrent.Executors

class VaultModule(private val context: ReactApplicationContext) : NativeVaultSpec(context) {

  companion object {
    private const val REQ_DELETE = 0x5EC1
    private const val EVT_LOCKED = "EnclaviaLocked"
    private const val EVT_IMPORT = "EnclaviaImportProgress"
    private const val EVT_EXPORT = "EnclaviaExportProgress"
    private const val NAME_MAX = 60
  }

  private val keys = KeyManager.get(context)
  private val transfer = MediaTransfer(context)
  private val editor = MediaEditor(context)
  private val db get() = VaultDatabase.get(context)

  /** Every write to the vault is serialised on this thread. */
  private val io = Executors.newSingleThreadExecutor { Thread(it, "vault-io") }

  /** Reads, key derivation, gallery queries. */
  private val worker = Executors.newFixedThreadPool(2) { Thread(it, "vault-worker") }

  @Volatile private var cancelImportRequested = false
  private var pendingDelete: Promise? = null

  private val lockListener: () -> Unit = { context.emitDeviceEvent(EVT_LOCKED, null) }

  private val activityListener = object : BaseActivityEventListener() {
    override fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
      if (requestCode != REQ_DELETE) return
      pendingDelete?.resolve(resultCode == Activity.RESULT_OK)
      pendingDelete = null
    }
  }

  init {
    keys.addLockListener(lockListener)
    VaultImageLoader.ensureLockHook(context)
    context.addActivityEventListener(activityListener)
    io.execute {
      // Remove leftovers of an import interrupted by a crash or power loss.
      VaultPaths.blobs(context).listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
    }
  }

  override fun invalidate() {
    keys.removeLockListener(lockListener)
    context.removeActivityEventListener(activityListener)
    io.shutdown()
    worker.shutdown()
    super.invalidate()
  }

  private fun runOn(executor: java.util.concurrent.Executor, promise: Promise, block: () -> Any?) {
    executor.execute {
      try {
        promise.resolve(block())
      } catch (e: VaultLockedException) {
        promise.reject("E_LOCKED", "Vault is locked")
      } catch (e: IllegalArgumentException) {
        promise.reject("E_INVALID", e.message ?: "Invalid input")
      } catch (e: Throwable) {
        promise.reject("E_VAULT", e.message ?: e.javaClass.simpleName)
      }
    }
  }

  private fun cleanName(name: String): String {
    val n = name.trim().replace(Regex("\\s+"), " ")
    require(n.isNotEmpty()) { "Name cannot be empty" }
    return n.take(NAME_MAX)
  }

  private fun ReadableArray.strings(): List<String> = (0 until size()).mapNotNull { getString(it) }

  // ================================================================ session

  override fun getSessionState(promise: Promise) = runOn(worker, promise) {
    Arguments.createMap().apply {
      putBoolean("setup", keys.isSetup())
      putBoolean("unlocked", keys.isUnlocked())
      putInt("failedAttempts", keys.failedAttempts())
      putDouble("lockoutUntil", keys.lockoutUntil().toDouble())
      putDouble("autoLockMs", keys.autoLockMs().toDouble())
      putInt("minPinLength", KeyManager.MIN_PIN_LENGTH)
      putInt("maxPinLength", KeyManager.MAX_PIN_LENGTH)
    }
  }

  override fun setupPin(pin: String, promise: Promise) = runOn(worker, promise) {
    keys.setupPin(pin)
    null
  }

  override fun unlockWithPin(pin: String, promise: Promise) = runOn(worker, promise) {
    val r = keys.unlockWithPin(pin)
    Arguments.createMap().apply {
      putBoolean("ok", r.ok)
      putInt("failedAttempts", r.failedAttempts)
      putDouble("lockoutUntil", r.lockoutUntil.toDouble())
    }
  }

  override fun changePin(oldPin: String, newPin: String, promise: Promise) = runOn(worker, promise) {
    if (!keys.isUnlocked()) throw VaultLockedException()
    keys.changePin(oldPin, newPin)
  }

  override fun lock() = keys.lock()

  override fun setAutoLockMs(ms: Double) = keys.setAutoLockMs(ms.toLong())

  // ================================================================ gallery

  override fun getGalleryAlbums(promise: Promise) = runOn(worker, promise) {
    Arguments.createArray().apply {
      Gallery.albums(context).forEach { a ->
        pushMap(Arguments.createMap().apply {
          putString("id", a.id)
          putString("name", a.name)
          putInt("count", a.count)
          putString("coverUri", a.coverUri)
        })
      }
    }
  }

  override fun getGalleryMedia(bucketId: String, offset: Double, limit: Double, promise: Promise) =
    runOn(worker, promise) {
      Arguments.createArray().apply {
        Gallery.media(context, bucketId, offset.toInt().coerceAtLeast(0), limit.toInt().coerceIn(1, 500)).forEach { m ->
          pushMap(Arguments.createMap().apply {
            putString("uri", m.uri)
            putString("type", m.type)
            putString("mime", m.mime)
            putString("name", m.name)
            putDouble("size", m.size.toDouble())
            putInt("width", m.width)
            putInt("height", m.height)
            putDouble("durationMs", m.durationMs.toDouble())
            putDouble("takenAt", m.takenAt.toDouble())
            putString("bucketId", m.bucketId)
          })
        }
      }
    }

  override fun deleteFromGallery(uris: ReadableArray, promise: Promise) {
    val parsed = try {
      uris.strings().map { Gallery.parseMediaUri(it) }
    } catch (e: Throwable) {
      promise.reject("E_INVALID", "Invalid gallery URI")
      return
    }
    if (parsed.isEmpty()) {
      promise.resolve(true)
      return
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      // Android 11+: the system shows its own confirmation dialog and performs the delete.
      UiThreadUtil.runOnUiThread {
        val activity = getCurrentActivity()
        if (activity == null) {
          promise.reject("E_NO_ACTIVITY", "App is not in the foreground")
          return@runOnUiThread
        }
        try {
          val request = MediaStore.createDeleteRequest(context.contentResolver, parsed)
          pendingDelete?.resolve(false)
          pendingDelete = promise
          activity.startIntentSenderForResult(request.intentSender, REQ_DELETE, null, 0, 0, 0)
        } catch (e: Throwable) {
          pendingDelete = null
          promise.reject("E_DELETE", e.message ?: "Cannot delete")
        }
      }
    } else {
      // Android 8-10: WRITE_EXTERNAL_STORAGE (legacy storage) lets us delete directly.
      runOn(io, promise) {
        parsed.count { runCatching { context.contentResolver.delete(it, null, null) > 0 }.getOrDefault(false) } ==
          parsed.size
      }
    }
  }

  // ================================================================ vault

  override fun importMedia(uris: ReadableArray, albumId: String?, promise: Promise) {
    val list = uris.strings()
    cancelImportRequested = false
    runOn(io, promise) {
      if (!keys.isUnlocked()) throw VaultLockedException()
      if (albumId != null) require(db.albumExists(albumId)) { "Album not found" }
      val imported = Arguments.createArray()
      val importedUris = Arguments.createArray()
      val failed = Arguments.createArray()
      var cancelled = false
      list.forEachIndexed { index, value ->
        if (cancelImportRequested || !keys.isUnlocked()) {
          cancelled = true
          return@forEachIndexed
        }
        try {
          val uri = Gallery.parseMediaUri(value)
          val item = keys.withDek { transfer.import(uri, albumId, it) }
          imported.pushMap(item.toMap())
          importedUris.pushString(value)
        } catch (e: Throwable) {
          failed.pushMap(Arguments.createMap().apply {
            putString("uri", value)
            putString("error", if (e is VaultLockedException) "Vault locked" else e.message ?: "Import failed")
          })
        }
        context.emitDeviceEvent(EVT_IMPORT, Arguments.createMap().apply {
          putInt("done", index + 1)
          putInt("total", list.size)
        })
      }
      Arguments.createMap().apply {
        putArray("imported", imported)
        putArray("importedUris", importedUris)
        putArray("failed", failed)
        putBoolean("cancelled", cancelled)
      }
    }
  }

  override fun cancelImport() {
    cancelImportRequested = true
  }

  override fun exportItems(ids: ReadableArray, promise: Promise) {
    val list = ids.strings()
    runOn(io, promise) {
      val items = keys.withDek { db.getItems(list, it) }
      var ok = 0
      var failed = list.size - items.size
      items.forEachIndexed { index, item ->
        val success = runCatching { keys.withDek { transfer.export(item, it) } }.isSuccess
        if (success) ok++ else failed++
        context.emitDeviceEvent(EVT_EXPORT, Arguments.createMap().apply {
          putInt("done", index + 1)
          putInt("total", items.size)
        })
      }
      Arguments.createMap().apply {
        putInt("exported", ok)
        putInt("failed", failed)
      }
    }
  }

  override fun listAlbums(promise: Promise) = runOn(worker, promise) {
    val albums = keys.withDek { db.listAlbums(it) }
    Arguments.createArray().apply { albums.forEach { pushMap(it.toMap()) } }
  }

  override fun createAlbum(name: String, promise: Promise) = runOn(io, promise) {
    val clean = cleanName(name)
    keys.withDek { db.createAlbum(clean, it) }.toMap()
  }

  override fun renameAlbum(id: String, name: String, promise: Promise) = runOn(io, promise) {
    val clean = cleanName(name)
    keys.withDek { db.renameAlbum(id, clean, it) }
    null
  }

  override fun deleteAlbum(id: String, deleteItems: Boolean, promise: Promise) = runOn(io, promise) {
    if (!keys.isUnlocked()) throw VaultLockedException()
    if (deleteItems) db.itemIdsInAlbum(id).forEach { deleteItemFiles(it) } else db.clearAlbum(id)
    db.deleteAlbum(id)
    null
  }

  override fun listItems(albumId: String?, promise: Promise) = runOn(worker, promise) {
    val items = keys.withDek { db.listItems(albumId, it) }
    Arguments.createArray().apply { items.forEach { pushMap(it.toMap()) } }
  }

  override fun moveItems(ids: ReadableArray, albumId: String?, promise: Promise) {
    val list = ids.strings()
    runOn(io, promise) {
      if (!keys.isUnlocked()) throw VaultLockedException()
      if (albumId != null) require(db.albumExists(albumId)) { "Album not found" }
      db.moveItems(list, albumId)
      null
    }
  }

  override fun deleteItems(ids: ReadableArray, promise: Promise) {
    val list = ids.strings()
    runOn(io, promise) {
      if (!keys.isUnlocked()) throw VaultLockedException()
      list.forEach { deleteItemFiles(it) }
      VaultImageLoader.clear()
      null
    }
  }

  private fun deleteItemFiles(id: String) {
    VaultPaths.blob(context, id).delete()
    VaultPaths.thumb(context, id).delete()
    db.deleteItem(id)
  }

  override fun getStats(promise: Promise) = runOn(worker, promise) {
    if (!keys.isUnlocked()) throw VaultLockedException()
    val s = db.stats()
    Arguments.createMap().apply {
      putInt("itemCount", s.itemCount)
      putInt("imageCount", s.imageCount)
      putInt("videoCount", s.videoCount)
      putDouble("totalBytes", s.totalBytes.toDouble())
      putDouble("freeBytes", StatFs(VaultPaths.root(context).absolutePath).availableBytes.toDouble())
      if (s.latestItemId != null) putString("latestItemId", s.latestItemId) else putNull("latestItemId")
    }
  }

  // ================================================================ editing

  private fun requireItem(id: String, dek: ByteArray): VaultItem =
    db.getItems(listOf(id), dek).firstOrNull() ?: throw IllegalArgumentException("Item not found")

  override fun getMediaInfo(id: String, promise: Promise) = runOn(worker, promise) {
    val info = keys.withDek { dek -> editor.info(requireItem(id, dek), dek) }
    Arguments.createMap().apply {
      putInt("width", info.width)
      putInt("height", info.height)
      putDouble("durationMs", info.durationMs.toDouble())
    }
  }

  override fun cropImage(
    id: String, rotation: Double, x: Double, y: Double, width: Double, height: Double,
    replace: Boolean, promise: Promise,
  ) = runOn(io, promise) {
    val item = keys.withDek { dek ->
      editor.crop(requireItem(id, dek), rotation.toInt(), x, y, width, height, replace, dek)
    }
    VaultImageLoader.clear()
    item.toMap()
  }

  override fun trimVideo(id: String, startMs: Double, endMs: Double, replace: Boolean, promise: Promise) =
    runOn(io, promise) {
      val item = keys.withDek { dek ->
        editor.trim(requireItem(id, dek), startMs.toLong(), endMs.toLong(), replace, dek)
      }
      VaultImageLoader.clear()
      item.toMap()
    }

  override fun wipeVault(promise: Promise) = runOn(io, promise) {
    if (!keys.isUnlocked()) throw VaultLockedException()
    VaultDatabase.reset()
    VaultImageLoader.clear()
    VaultPaths.root(context).deleteRecursively()
    keys.wipeKeys()
    null
  }

  // ================================================================ mapping

  private fun VaultItem.toMap(): WritableMap = Arguments.createMap().apply {
    putString("id", id)
    if (albumId != null) putString("albumId", albumId) else putNull("albumId")
    putString("type", type)
    putString("mime", mime)
    putString("name", name)
    putDouble("size", size.toDouble())
    putInt("width", width)
    putInt("height", height)
    putDouble("durationMs", durationMs.toDouble())
    putDouble("takenAt", takenAt.toDouble())
    putDouble("importedAt", importedAt.toDouble())
    putBoolean("hasThumb", hasThumb)
  }

  private fun VaultAlbum.toMap(): WritableMap = Arguments.createMap().apply {
    putString("id", id)
    putString("name", name)
    putDouble("createdAt", createdAt.toDouble())
    putInt("itemCount", itemCount)
    if (coverItemId != null) putString("coverItemId", coverItemId) else putNull("coverItemId")
  }
}
