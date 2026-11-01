package com.enclavia.vault.media

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.content.ContentResolver

data class GalleryAlbum(val id: String, val name: String, val count: Int, val coverUri: String)

data class GalleryItem(
  val uri: String,
  val type: String,
  val mime: String,
  val name: String,
  val size: Long,
  val width: Int,
  val height: Int,
  val durationMs: Long,
  val takenAt: Long,
  val bucketId: String,
)

/** Read-only view of the phone's shared photo/video library (MediaStore). */
object Gallery {
  private val FILES: Uri = MediaStore.Files.getContentUri("external")
  private const val MEDIA_TYPE = MediaStore.Files.FileColumns.MEDIA_TYPE
  private const val IMAGE = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
  private const val VIDEO = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
  private const val BUCKET_ID = "bucket_id"
  private const val BUCKET_NAME = "bucket_display_name"
  private const val DATE_TAKEN = "datetaken"
  private const val DURATION = "duration"
  private const val SORT = "$DATE_TAKEN DESC, ${MediaStore.MediaColumns._ID} DESC"
  private val BASE_SELECTION = "($MEDIA_TYPE = $IMAGE OR $MEDIA_TYPE = $VIDEO) AND ${MediaStore.MediaColumns.SIZE} > 0"

  fun itemUri(id: Long, mediaType: Int): Uri =
    ContentUris.withAppendedId(
      if (mediaType == VIDEO) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
      else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
      id,
    )

  fun albums(ctx: Context): List<GalleryAlbum> {
    val projection = arrayOf(MediaStore.MediaColumns._ID, MEDIA_TYPE, BUCKET_ID, BUCKET_NAME)
    data class Acc(val name: String, val cover: String, var count: Int)
    val map = LinkedHashMap<String, Acc>()
    var total = 0
    var allCover: String? = null
    ctx.contentResolver.query(FILES, projection, BASE_SELECTION, null, SORT)?.use { c ->
      while (c.moveToNext()) {
        val uri = itemUri(c.getLong(0), c.getInt(1)).toString()
        val bucket = c.getString(2) ?: continue
        val acc = map.getOrPut(bucket) { Acc(c.getString(3) ?: "Unnamed", uri, 0) }
        acc.count++
        total++
        if (allCover == null) allCover = uri
      }
    }
    val result = ArrayList<GalleryAlbum>()
    allCover?.let { result.add(GalleryAlbum("", "All photos & videos", total, it)) }
    map.entries.sortedByDescending { it.value.count }.forEach { (id, acc) ->
      result.add(GalleryAlbum(id, acc.name, acc.count, acc.cover))
    }
    return result
  }

  /** [bucketId] empty = everything. Paged, newest first. */
  fun media(ctx: Context, bucketId: String, offset: Int, limit: Int): List<GalleryItem> {
    val projection = arrayOf(
      MediaStore.MediaColumns._ID, MEDIA_TYPE, MediaStore.MediaColumns.MIME_TYPE,
      MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
      MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT, DURATION,
      DATE_TAKEN, MediaStore.MediaColumns.DATE_MODIFIED, BUCKET_ID,
    )
    val selection = if (bucketId.isEmpty()) BASE_SELECTION else "$BASE_SELECTION AND $BUCKET_ID = ?"
    val args = if (bucketId.isEmpty()) null else arrayOf(bucketId)

    val cursor: Cursor? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      val queryArgs = Bundle().apply {
        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
        putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
        putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, SORT)
        putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
      }
      ctx.contentResolver.query(FILES, projection, queryArgs, null)
    } else {
      ctx.contentResolver.query(FILES, projection, selection, args, "$SORT LIMIT $limit OFFSET $offset")
    }

    return cursor?.use { c ->
      buildList {
        while (c.moveToNext()) {
          val mediaType = c.getInt(1)
          val taken = c.getLong(8).takeIf { it > 0 } ?: (c.getLong(9) * 1000)
          add(
            GalleryItem(
              uri = itemUri(c.getLong(0), mediaType).toString(),
              type = if (mediaType == VIDEO) "video" else "image",
              mime = c.getString(2) ?: "",
              name = c.getString(3) ?: "",
              size = c.getLong(4),
              width = c.getInt(5),
              height = c.getInt(6),
              durationMs = if (mediaType == VIDEO) c.getLong(7) else 0L,
              takenAt = taken,
              bucketId = c.getString(10) ?: "",
            )
          )
        }
      }
    } ?: emptyList()
  }

  /** Only MediaStore image/video URIs are accepted from JS. */
  fun parseMediaUri(value: String): Uri {
    val uri = Uri.parse(value)
    require(uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY) { "Not a gallery URI" }
    ContentUris.parseId(uri)
    return uri
  }
}
