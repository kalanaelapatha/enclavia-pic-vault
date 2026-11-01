package com.enclavia.vault.ui

import com.enclavia.vault.video.VaultVideoView
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.ViewManagerDelegate
import com.facebook.react.uimanager.annotations.ReactProp
import com.facebook.react.viewmanagers.VaultImageViewManagerDelegate
import com.facebook.react.viewmanagers.VaultImageViewManagerInterface
import com.facebook.react.viewmanagers.VaultVideoViewManagerDelegate
import com.facebook.react.viewmanagers.VaultVideoViewManagerInterface

@ReactModule(name = VaultImageViewManager.NAME)
class VaultImageViewManager :
  SimpleViewManager<VaultImageView>(), VaultImageViewManagerInterface<VaultImageView> {

  companion object {
    const val NAME = "VaultImageView"
  }

  private val delegate = VaultImageViewManagerDelegate(this)

  override fun getDelegate(): ViewManagerDelegate<VaultImageView> = delegate

  override fun getName(): String = NAME

  override fun createViewInstance(context: ThemedReactContext) = VaultImageView(context)

  @ReactProp(name = "itemId")
  override fun setItemId(view: VaultImageView, value: String?) {
    view.itemId = value ?: ""
  }

  @ReactProp(name = "galleryUri")
  override fun setGalleryUri(view: VaultImageView, value: String?) {
    view.galleryUri = value ?: ""
  }

  @ReactProp(name = "galleryIsVideo")
  override fun setGalleryIsVideo(view: VaultImageView, value: Boolean) {
    view.galleryIsVideo = value
  }

  @ReactProp(name = "variant")
  override fun setVariant(view: VaultImageView, value: String?) {
    view.variant = value ?: "thumb"
  }

  @ReactProp(name = "frameTimeMs", defaultDouble = 0.0)
  override fun setFrameTimeMs(view: VaultImageView, value: Double) {
    view.frameTimeMs = value.toLong()
  }

  @ReactProp(name = "version", defaultDouble = 0.0)
  override fun setVersion(view: VaultImageView, value: Double) {
    view.version = value.toLong()
  }

  @ReactProp(name = "resizeMode")
  override fun setResizeMode(view: VaultImageView, value: String?) {
    view.resizeMode = value ?: "cover"
  }

  @ReactProp(name = "zoomable")
  override fun setZoomable(view: VaultImageView, value: Boolean) {
    view.zoomable = value
  }

  override fun onAfterUpdateTransaction(view: VaultImageView) {
    super.onAfterUpdateTransaction(view)
    view.commitProps()
  }

  override fun onDropViewInstance(view: VaultImageView) {
    view.release()
    super.onDropViewInstance(view)
  }

  override fun getExportedCustomDirectEventTypeConstants(): Map<String, Any> = mapOf(
    "topSingleTap" to mapOf("registrationName" to "onSingleTap"),
    "topZoomChange" to mapOf("registrationName" to "onZoomChange"),
  )
}

@ReactModule(name = VaultVideoViewManager.NAME)
class VaultVideoViewManager :
  SimpleViewManager<VaultVideoView>(), VaultVideoViewManagerInterface<VaultVideoView> {

  companion object {
    const val NAME = "VaultVideoView"
  }

  private val delegate = VaultVideoViewManagerDelegate(this)

  override fun getDelegate(): ViewManagerDelegate<VaultVideoView> = delegate

  override fun getName(): String = NAME

  override fun createViewInstance(context: ThemedReactContext) = VaultVideoView(context)

  @ReactProp(name = "itemId")
  override fun setItemId(view: VaultVideoView, value: String?) {
    view.itemId = value ?: ""
  }

  @ReactProp(name = "paused")
  override fun setPaused(view: VaultVideoView, value: Boolean) {
    view.paused = value
  }

  @ReactProp(name = "clipStartMs", defaultDouble = -1.0)
  override fun setClipStartMs(view: VaultVideoView, value: Double) {
    view.clipStartMs = value.toLong()
  }

  @ReactProp(name = "clipEndMs", defaultDouble = -1.0)
  override fun setClipEndMs(view: VaultVideoView, value: Double) {
    view.clipEndMs = value.toLong()
  }

  @ReactProp(name = "repeat")
  override fun setRepeat(view: VaultVideoView, value: Boolean) {
    view.repeat = value
  }

  override fun onAfterUpdateTransaction(view: VaultVideoView) {
    super.onAfterUpdateTransaction(view)
    view.commitProps()
  }

  override fun onDropViewInstance(view: VaultVideoView) {
    view.releasePlayer()
    super.onDropViewInstance(view)
  }

  override fun getExportedCustomDirectEventTypeConstants(): Map<String, Any> = mapOf(
    "topPlaybackError" to mapOf("registrationName" to "onPlaybackError"),
  )
}
