package com.enclavia.vault.video

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.enclavia.vault.crypto.EncryptedFile
import com.enclavia.vault.crypto.KeyManager
import com.enclavia.vault.data.VaultPaths
import com.enclavia.vault.ui.VaultViewEvent
import com.facebook.react.bridge.Arguments
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.UIManagerHelper
import java.io.IOException

/**
 * ExoPlayer data source that decrypts vault files chunk-by-chunk while playing.
 * Supports seeking. Nothing decrypted ever touches the disk.
 */
@OptIn(UnstableApi::class)
class EncryptedDataSource(private val context: Context) : BaseDataSource(false) {
  private var reader: EncryptedFile.Reader? = null
  private var uri: Uri? = null
  private var position = 0L
  private var bytesRemaining = 0L
  private var opened = false

  override fun open(dataSpec: DataSpec): Long {
    uri = dataSpec.uri
    transferInitializing(dataSpec)
    val id = dataSpec.uri.lastPathSegment ?: throw IOException("Bad uri")
    val file = VaultPaths.blob(context, id)
    val r = KeyManager.get(context).withDek { EncryptedFile.Reader(file, it) }
    reader = r
    position = dataSpec.position
    if (position > r.plainLength) {
      throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
    }
    bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length
    else r.plainLength - position
    opened = true
    transferStarted(dataSpec)
    return bytesRemaining
  }

  override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
    if (length == 0) return 0
    if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
    val r = reader ?: throw IOException("Closed")
    val n = r.read(position, buffer, offset, minOf(length.toLong(), bytesRemaining).toInt())
    if (n <= 0) return C.RESULT_END_OF_INPUT
    position += n
    bytesRemaining -= n
    bytesTransferred(n)
    return n
  }

  override fun getUri(): Uri? = uri

  override fun close() {
    uri = null
    reader?.close()
    reader = null
    if (opened) {
      opened = false
      transferEnded()
    }
  }
}

@OptIn(UnstableApi::class)
class VaultVideoView(private val reactContext: ThemedReactContext) : FrameLayout(reactContext) {

  var itemId = ""
  var paused = false

  private var loadedId = ""
  private var player: ExoPlayer? = null
  private val keys = KeyManager.get(reactContext)
  private val onLock: () -> Unit = { post { releasePlayer() } }

  private val playerView = PlayerView(reactContext).apply {
    useController = true
    setShowNextButton(false)
    setShowPreviousButton(false)
    setShowShuffleButton(false)
    setShowSubtitleButton(false)
    controllerAutoShow = true
    keepScreenOn = true
    setBackgroundColor(Color.BLACK)
  }

  init {
    setBackgroundColor(Color.BLACK)
    addView(playerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
  }

  fun commitProps() {
    if (itemId != loadedId) preparePlayer()
    player?.playWhenReady = !paused
  }

  private fun preparePlayer() {
    releasePlayer()
    loadedId = itemId
    if (itemId.isEmpty() || !isAttachedToWindow) return
    val appContext = context.applicationContext
    val factory = DataSource.Factory { EncryptedDataSource(appContext) }
    val source = ProgressiveMediaSource.Factory(factory)
      .createMediaSource(MediaItem.fromUri(Uri.parse("vault://item/$itemId")))
    val p = ExoPlayer.Builder(context)
      .setMediaSourceFactory(ProgressiveMediaSource.Factory(factory))
      .setAudioAttributes(AudioAttributes.DEFAULT, true)
      .setHandleAudioBecomingNoisy(true)
      .build()
    p.addListener(object : Player.Listener {
      override fun onPlayerError(error: PlaybackException) {
        val surfaceId = UIManagerHelper.getSurfaceId(reactContext)
        UIManagerHelper.getEventDispatcherForReactTag(reactContext, id)?.dispatchEvent(
          VaultViewEvent(surfaceId, id, "topPlaybackError",
            Arguments.createMap().apply { putString("message", error.errorCodeName) })
        )
      }
    })
    p.setMediaSource(source)
    p.prepare()
    p.playWhenReady = !paused
    playerView.player = p
    player = p
  }

  fun releasePlayer() {
    playerView.player = null
    player?.release()
    player = null
    loadedId = ""
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    keys.addLockListener(onLock)
    if (player == null && itemId.isNotEmpty()) preparePlayer()
  }

  override fun onDetachedFromWindow() {
    keys.removeLockListener(onLock)
    releasePlayer()
    super.onDetachedFromWindow()
  }

  // React Native lays out native views itself; native children (PlayerView) need a manual pass.
  private val measureAndLayout = Runnable {
    measure(
      MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
      MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
    )
    layout(left, top, right, bottom)
  }

  override fun requestLayout() {
    super.requestLayout()
    post(measureAndLayout)
  }
}
