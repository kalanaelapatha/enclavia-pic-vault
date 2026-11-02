package com.enclavia.vault.media

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.system.Os
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.Mp4OrientationData
import androidx.media3.datasource.DataSource
import androidx.media3.effect.Crop
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Mp4Muxer
import androidx.media3.muxer.Muxer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMp4Muxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.enclavia.vault.crypto.KeyManager
import com.enclavia.vault.video.EncryptedDataSource
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import kotlin.math.roundToInt

/**
 * Re-encodes a vault video with rotate + crop applied, using the device's hardware codecs.
 * Input is decrypted on the fly; output goes to a caller-supplied descriptor (RAM or temp file).
 * One instance runs one job. [cancel] (or locking the vault) stops it.
 */
@OptIn(UnstableApi::class)
class VideoTranscoder(private val ctx: Context) {

  class Result(val width: Int, val height: Int, val durationMs: Long)

  @Volatile private var cancelled = false
  @Volatile private var handler: Handler? = null
  @Volatile private var transformer: Transformer? = null
  @Volatile private var latch: CountDownLatch? = null

  fun cancel() {
    cancelled = true
    val h = handler ?: return
    h.post {
      transformer?.cancel()
      latch?.countDown()
    }
  }

  /**
   * @param displayW, displayH the video's on-screen (rotation-corrected) size.
   * @param rotation clockwise degrees (0/90/180/270), applied before cropping.
   * @param nx..nh crop rect normalised to the rotated frame.
   */
  fun run(
    itemId: String, displayW: Int, displayH: Int, rotation: Int,
    nx: Double, ny: Double, nw: Double, nh: Double,
    out: FileDescriptor, onProgress: (Int) -> Unit,
  ): Result {
    val rw = if (rotation % 180 == 0) displayW else displayH
    val rh = if (rotation % 180 == 0) displayH else displayW
    val x0 = nx.coerceIn(0.0, 1.0)
    val y0 = ny.coerceIn(0.0, 1.0)
    val x1 = (nx + nw).coerceIn(x0, 1.0)
    val y1 = (ny + nh).coerceIn(y0, 1.0)
    // Encoders want even dimensions.
    val outW = ((rw * (x1 - x0)).roundToInt() and 1.inv()).coerceAtLeast(2)
    val outH = ((rh * (y1 - y0)).roundToInt() and 1.inv()).coerceAtLeast(2)

    val effects = ArrayList<Effect>()
    if (rotation != 0) {
      // Media3 rotates counter-clockwise.
      effects += ScaleAndRotateTransformation.Builder().setRotationDegrees((360 - rotation).toFloat()).build()
    }
    val fullFrame = x0 <= 0.0005 && y0 <= 0.0005 && x1 >= 0.9995 && y1 >= 0.9995
    if (!fullFrame) {
      // Normalised device coordinates: x -1..1 left→right, y -1..1 bottom→top.
      effects += Crop(
        (x0 * 2 - 1).toFloat(), (x1 * 2 - 1).toFloat(),
        (1 - y1 * 2).toFloat(), (1 - y0 * 2).toFloat(),
      )
    }
    effects += Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_STRETCH_TO_FIT)

    val keys = KeyManager.get(ctx)
    val onLock: () -> Unit = { cancel() }
    keys.addLockListener(onLock)

    val thread = HandlerThread("vault-transcode").apply { start() }
    val h = Handler(thread.looper)
    val done = CountDownLatch(1)
    handler = h
    latch = done
    var result: ExportResult? = null
    var error: Throwable? = null

    h.post {
      if (cancelled) {
        done.countDown()
        return@post
      }
      try {
        val app = ctx.applicationContext
        val sources = DefaultMediaSourceFactory(DataSource.Factory { EncryptedDataSource(app) })
        val t = Transformer.Builder(app)
          .setLooper(thread.looper)
          .setVideoMimeType(MimeTypes.VIDEO_H264) // plays everywhere, including after export
          .setAssetLoaderFactory(
            DefaultAssetLoaderFactory(app, DefaultDecoderFactory.Builder(app).build(), Clock.DEFAULT, sources, NoBitmaps),
          )
          .setMuxerFactory(FdMuxerFactory(out))
          .addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
              result = exportResult
              done.countDown()
            }

            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
              error = exportException
              done.countDown()
            }
          })
          .build()
        transformer = t
        val media = EditedMediaItem.Builder(MediaItem.fromUri(Uri.parse("vault://item/$itemId")))
          .setEffects(Effects(emptyList(), effects))
          .build()
        // The path is never opened: FdMuxerFactory writes to [out].
        t.start(media, java.io.File(app.cacheDir, "transcode.unused").absolutePath)

        val holder = ProgressHolder()
        val poll = object : Runnable {
          override fun run() {
            if (done.count == 0L) return
            if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
            h.postDelayed(this, 300)
          }
        }
        h.postDelayed(poll, 300)
      } catch (e: Throwable) {
        error = e
        done.countDown()
      }
    }

    try {
      done.await()
    } finally {
      keys.removeLockListener(onLock)
      h.removeCallbacksAndMessages(null)
      thread.quitSafely()
      handler = null
    }

    if (cancelled) throw CancellationException("Cancelled")
    error?.let { e ->
      val code = (e as? ExportException)?.errorCodeName ?: e.javaClass.simpleName
      throw IOException("This video couldn’t be processed on this phone ($code)", e)
    }
    val r = result ?: throw IOException("Video processing failed")
    return Result(
      width = if (r.width > 0) r.width else outW,
      height = if (r.height > 0) r.height else outH,
      durationMs = r.durationMs.coerceAtLeast(0),
    )
  }

  /** Writes the MP4 to our own descriptor so it can live in RAM (memfd) and never on storage. */
  private class FdMuxerFactory(private val fd: FileDescriptor) : Muxer.Factory {
    private val defaults = InAppMp4Muxer.Factory()

    override fun create(path: String): Muxer {
      // A dup so closing the muxer's stream doesn't close the caller's descriptor.
      val stream = FileOutputStream(Os.dup(fd))
      return MetadataFilter(Mp4Muxer.Builder(stream).build())
    }

    override fun getSupportedSampleMimeTypes(trackType: Int): ImmutableList<String> =
      defaults.getSupportedSampleMimeTypes(trackType)
  }

  /** Keeps only orientation; drops location and every other piece of source metadata. */
  private class MetadataFilter(private val inner: Muxer) : Muxer {
    override fun addTrack(format: Format): Int {
      // The encoder may store frames rotated and ask for display rotation via the format.
      if (MimeTypes.isVideo(format.sampleMimeType) && format.rotationDegrees != 0) {
        inner.addMetadataEntry(Mp4OrientationData(format.rotationDegrees))
      }
      return inner.addTrack(format)
    }
    override fun writeSampleData(trackId: Int, data: ByteBuffer, bufferInfo: BufferInfo) =
      inner.writeSampleData(trackId, data, bufferInfo)
    override fun addMetadataEntry(metadataEntry: Metadata.Entry) {
      if (metadataEntry is Mp4OrientationData) inner.addMetadataEntry(metadataEntry)
    }
    override fun close() = inner.close()
  }

  /** Only video items are transcoded; image loading (which could use other data sources) is refused. */
  private object NoBitmaps : BitmapLoader {
    override fun supportsMimeType(mimeType: String) = false
    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
      Futures.immediateFailedFuture(UnsupportedOperationException())
    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
      Futures.immediateFailedFuture(UnsupportedOperationException())
  }
}
