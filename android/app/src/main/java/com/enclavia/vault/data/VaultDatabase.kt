package com.enclavia.vault.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.enclavia.vault.crypto.FieldCipher
import java.io.File
import java.util.UUID

/** App-private storage layout. Lives in noBackupFilesDir: never backed up, invisible to other apps. */
object VaultPaths {
  fun root(ctx: Context): File = File(ctx.noBackupFilesDir, "vault").apply { mkdirs() }
  fun blobs(ctx: Context): File = File(root(ctx), "blobs").apply { mkdirs() }
  fun thumbs(ctx: Context): File = File(root(ctx), "thumbs").apply { mkdirs() }
  fun blob(ctx: Context, id: String): File = File(blobs(ctx), safeId(id))
  fun thumb(ctx: Context, id: String): File = File(thumbs(ctx), safeId(id))
  fun db(ctx: Context): File = File(root(ctx), "vault.db")

  /** Ids are UUIDs; reject anything else so an id can never escape the vault folder. */
  fun safeId(id: String): String {
    require(ID_PATTERN.matches(id)) { "Invalid id" }
    return id
  }

  private val ID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
}

data class VaultItem(
  val id: String,
  val albumId: String?,
  val type: String, // "image" | "video"
  val mime: String,
  val name: String,
  val size: Long,
  val width: Int,
  val height: Int,
  val durationMs: Long,
  val takenAt: Long,
  val importedAt: Long,
  val hasThumb: Boolean,
)

data class VaultAlbum(
  val id: String,
  val name: String,
  val createdAt: Long,
  val itemCount: Int,
  val coverItemId: String?,
)

data class VaultStats(
  val itemCount: Int,
  val imageCount: Int,
  val videoCount: Int,
  val totalBytes: Long,
  val latestItemId: String?,
)

/**
 * Metadata store. Sensitive text (album names, original file names) is encrypted
 * column-by-column with a key derived from the DEK, so the database file alone reveals nothing useful.
 */
class VaultDatabase private constructor(ctx: Context) :
  SQLiteOpenHelper(ctx, VaultPaths.db(ctx).absolutePath, null, 1) {

  companion object {
    @Volatile private var instance: VaultDatabase? = null

    fun get(ctx: Context): VaultDatabase =
      instance ?: synchronized(this) {
        instance ?: VaultDatabase(ctx.applicationContext).also { instance = it }
      }

    fun reset() = synchronized(this) {
      instance?.close()
      instance = null
    }

    private const val ITEM_COLUMNS =
      "id, album_id, type, mime, name_enc, size, width, height, duration_ms, taken_at, imported_at, has_thumb"
  }

  override fun onConfigure(db: SQLiteDatabase) {
    db.setForeignKeyConstraintsEnabled(false)
    db.enableWriteAheadLogging()
  }

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(
      """CREATE TABLE albums (
           id TEXT PRIMARY KEY NOT NULL,
           name_enc TEXT NOT NULL,
           created_at INTEGER NOT NULL)"""
    )
    db.execSQL(
      """CREATE TABLE items (
           id TEXT PRIMARY KEY NOT NULL,
           album_id TEXT,
           type TEXT NOT NULL,
           mime TEXT NOT NULL,
           name_enc TEXT NOT NULL,
           size INTEGER NOT NULL,
           width INTEGER NOT NULL DEFAULT 0,
           height INTEGER NOT NULL DEFAULT 0,
           duration_ms INTEGER NOT NULL DEFAULT 0,
           taken_at INTEGER NOT NULL,
           imported_at INTEGER NOT NULL,
           has_thumb INTEGER NOT NULL DEFAULT 0)"""
    )
    db.execSQL("CREATE INDEX idx_items_album ON items(album_id, taken_at DESC)")
    db.execSQL("CREATE INDEX idx_items_taken ON items(taken_at DESC)")
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

  // ---------------------------------------------------------------- albums

  fun createAlbum(name: String, dek: ByteArray): VaultAlbum {
    val album = VaultAlbum(UUID.randomUUID().toString(), name, System.currentTimeMillis(), 0, null)
    writableDatabase.insertOrThrow("albums", null, ContentValues().apply {
      put("id", album.id)
      put("name_enc", FieldCipher.encrypt(name, dek))
      put("created_at", album.createdAt)
    })
    return album
  }

  fun renameAlbum(id: String, name: String, dek: ByteArray) {
    writableDatabase.update("albums", ContentValues().apply {
      put("name_enc", FieldCipher.encrypt(name, dek))
    }, "id = ?", arrayOf(id))
  }

  fun albumExists(id: String): Boolean =
    readableDatabase.rawQuery("SELECT 1 FROM albums WHERE id = ?", arrayOf(id)).use { it.moveToFirst() }

  fun deleteAlbum(id: String) {
    writableDatabase.delete("albums", "id = ?", arrayOf(id))
  }

  fun listAlbums(dek: ByteArray): List<VaultAlbum> {
    val sql = """
      SELECT a.id, a.name_enc, a.created_at,
             (SELECT COUNT(*) FROM items i WHERE i.album_id = a.id),
             (SELECT i.id FROM items i WHERE i.album_id = a.id ORDER BY i.taken_at DESC LIMIT 1)
      FROM albums a ORDER BY a.created_at ASC"""
    return readableDatabase.rawQuery(sql, null).use { c ->
      buildList {
        while (c.moveToNext()) {
          add(
            VaultAlbum(
              id = c.getString(0),
              name = runCatching { FieldCipher.decrypt(c.getString(1), dek) }.getOrDefault("Album"),
              createdAt = c.getLong(2),
              itemCount = c.getInt(3),
              coverItemId = c.getString(4),
            )
          )
        }
      }
    }
  }

  // ---------------------------------------------------------------- items

  fun insertItem(item: VaultItem, dek: ByteArray) {
    writableDatabase.insertOrThrow("items", null, ContentValues().apply {
      put("id", item.id)
      put("album_id", item.albumId)
      put("type", item.type)
      put("mime", item.mime)
      put("name_enc", FieldCipher.encrypt(item.name, dek))
      put("size", item.size)
      put("width", item.width)
      put("height", item.height)
      put("duration_ms", item.durationMs)
      put("taken_at", item.takenAt)
      put("imported_at", item.importedAt)
      put("has_thumb", if (item.hasThumb) 1 else 0)
    })
  }

  /** [albumId] null = every item in the vault. */
  fun listItems(albumId: String?, dek: ByteArray): List<VaultItem> {
    val (where, args) = if (albumId == null) "" to null else "WHERE album_id = ?" to arrayOf(albumId)
    val sql = "SELECT $ITEM_COLUMNS FROM items $where ORDER BY taken_at DESC, imported_at DESC"
    return readableDatabase.rawQuery(sql, args).use { c ->
      buildList {
        while (c.moveToNext()) {
          add(
            VaultItem(
              id = c.getString(0),
              albumId = c.getString(1),
              type = c.getString(2),
              mime = c.getString(3),
              name = runCatching { FieldCipher.decrypt(c.getString(4), dek) }.getOrDefault("file"),
              size = c.getLong(5),
              width = c.getInt(6),
              height = c.getInt(7),
              durationMs = c.getLong(8),
              takenAt = c.getLong(9),
              importedAt = c.getLong(10),
              hasThumb = c.getInt(11) == 1,
            )
          )
        }
      }
    }
  }

  fun getItems(ids: List<String>, dek: ByteArray): List<VaultItem> =
    ids.chunked(500).flatMap { chunk ->
      val placeholders = chunk.joinToString(",") { "?" }
      readableDatabase.rawQuery(
        "SELECT $ITEM_COLUMNS FROM items WHERE id IN ($placeholders)", chunk.toTypedArray()
      ).use { c ->
        buildList {
          while (c.moveToNext()) {
            add(
              VaultItem(
                c.getString(0), c.getString(1), c.getString(2), c.getString(3),
                runCatching { FieldCipher.decrypt(c.getString(4), dek) }.getOrDefault("file"),
                c.getLong(5), c.getInt(6), c.getInt(7), c.getLong(8), c.getLong(9), c.getLong(10),
                c.getInt(11) == 1,
              )
            )
          }
        }
      }
    }

  fun itemIdsInAlbum(albumId: String): List<String> =
    readableDatabase.rawQuery("SELECT id FROM items WHERE album_id = ?", arrayOf(albumId)).use { c ->
      buildList { while (c.moveToNext()) add(c.getString(0)) }
    }

  fun moveItems(ids: List<String>, albumId: String?) {
    val db = writableDatabase
    db.beginTransaction()
    try {
      ids.chunked(500).forEach { chunk ->
        val placeholders = chunk.joinToString(",") { "?" }
        db.update("items", ContentValues().apply { put("album_id", albumId) },
          "id IN ($placeholders)", chunk.toTypedArray())
      }
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
  }

  fun clearAlbum(albumId: String) {
    writableDatabase.update("items", ContentValues().apply { putNull("album_id") },
      "album_id = ?", arrayOf(albumId))
  }

  fun deleteItem(id: String) {
    writableDatabase.delete("items", "id = ?", arrayOf(id))
  }

  fun stats(): VaultStats =
    readableDatabase.rawQuery(
      """SELECT COUNT(*),
                SUM(CASE WHEN type = 'image' THEN 1 ELSE 0 END),
                SUM(CASE WHEN type = 'video' THEN 1 ELSE 0 END),
                COALESCE(SUM(size), 0),
                (SELECT id FROM items ORDER BY taken_at DESC LIMIT 1)
         FROM items""", null
    ).use { c ->
      c.moveToFirst()
      VaultStats(c.getInt(0), c.getInt(1), c.getInt(2), c.getLong(3), c.getString(4))
    }
}
