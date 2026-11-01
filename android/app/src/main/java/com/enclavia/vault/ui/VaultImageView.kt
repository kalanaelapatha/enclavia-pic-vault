package com.enclavia.vault.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.UIManagerHelper
import com.facebook.react.uimanager.events.Event
import java.util.concurrent.Future
import kotlin.math.max
import kotlin.math.min

/**
 * Displays a vault image (thumbnail or full) or a gallery thumbnail.
 * Full images support pinch-zoom, pan and double-tap zoom.
 */
class VaultImageView(private val reactContext: ThemedReactContext) : View(reactContext) {

  var itemId = ""
  var galleryUri = ""
  var galleryIsVideo = false
  var variant = "thumb"
  var resizeMode = "cover"
  var zoomable = false

  private var current: VaultImageLoader.Request? = null
  private var pending: Future<*>? = null
  private var bitmap: Bitmap? = null

  private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
  private val baseMatrix = Matrix()
  private val userMatrix = Matrix()
  private val drawMatrix = Matrix()
  private val values = FloatArray(9)
  private val rect = RectF()
  private var zoomedReported = false
  private var animator: ValueAnimator? = null

  private val maxZoom = 5f

  // ---------------------------------------------------------------- props

  fun commitProps() {
    val full = variant == "full"
    val longSide = max(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
    val req = VaultImageLoader.Request(
      itemId = itemId,
      galleryUri = if (itemId.isEmpty()) galleryUri else "",
      galleryIsVideo = galleryIsVideo,
      full = full,
      maxSide = if (full) min((longSide * 1.5f).toInt(), 3072) else 0,
    )
    if (req == current && bitmap != null) {
      updateBaseMatrix()
      invalidate()
      return
    }
    current = req
    resetZoom(animate = false)
    startLoad()
  }

  private fun startLoad() {
    pending?.cancel(true)
    pending = null
    val req = current ?: return
    if (req.isEmpty) {
      setBitmap(null, fade = false)
      return
    }
    val cached = VaultImageLoader.cached(req)
    if (cached != null) {
      setBitmap(cached, fade = false)
      return
    }
    setBitmap(null, fade = false)
    if (!isAttachedToWindow) return
    pending = VaultImageLoader.load(context, req) { bmp ->
      if (req == current) setBitmap(bmp, fade = true)
    }
  }

  private fun setBitmap(bmp: Bitmap?, fade: Boolean) {
    bitmap = bmp
    updateBaseMatrix()
    if (fade && bmp != null) {
      alpha = 0f
      animate().alpha(1f).setDuration(140).start()
    } else {
      alpha = 1f
    }
    invalidate()
  }

  fun release() {
    pending?.cancel(true)
    pending = null
    bitmap = null
    animator?.cancel()
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    if (bitmap == null && current != null) startLoad()
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    release()
  }

  // ---------------------------------------------------------------- drawing

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    updateBaseMatrix()
  }

  private fun updateBaseMatrix() {
    val bmp = bitmap ?: return
    val vw = width.toFloat()
    val vh = height.toFloat()
    if (vw <= 0 || vh <= 0) return
    val bw = bmp.width.toFloat()
    val bh = bmp.height.toFloat()
    val scale = if (resizeMode == "contain") min(vw / bw, vh / bh) else max(vw / bw, vh / bh)
    baseMatrix.reset()
    baseMatrix.postScale(scale, scale)
    baseMatrix.postTranslate((vw - bw * scale) / 2f, (vh - bh * scale) / 2f)
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val bmp = bitmap ?: return
    if (bmp.isRecycled) return
    drawMatrix.set(baseMatrix)
    drawMatrix.postConcat(userMatrix)
    canvas.save()
    canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
    canvas.drawBitmap(bmp, drawMatrix, paint)
    canvas.restore()
  }

  // ---------------------------------------------------------------- zoom & gestures

  private fun userScale(): Float {
    userMatrix.getValues(values)
    return values[Matrix.MSCALE_X]
  }

  private fun clampMatrix() {
    val bmp = bitmap ?: return
    var s = userScale()
    if (s < 1f) {
      userMatrix.postScale(1f / s, 1f / s, width / 2f, height / 2f)
      s = 1f
    }
    if (s > maxZoom) userMatrix.postScale(maxZoom / s, maxZoom / s, width / 2f, height / 2f)

    drawMatrix.set(baseMatrix)
    drawMatrix.postConcat(userMatrix)
    rect.set(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
    drawMatrix.mapRect(rect)
    var dx = 0f
    var dy = 0f
    if (rect.width() <= width) dx = (width - rect.width()) / 2f - rect.left
    else if (rect.left > 0) dx = -rect.left
    else if (rect.right < width) dx = width - rect.right
    if (rect.height() <= height) dy = (height - rect.height()) / 2f - rect.top
    else if (rect.top > 0) dy = -rect.top
    else if (rect.bottom < height) dy = height - rect.bottom
    userMatrix.postTranslate(dx, dy)
    reportZoom()
  }

  private fun reportZoom() {
    val zoomed = userScale() > 1.01f
    if (zoomed != zoomedReported) {
      zoomedReported = zoomed
      dispatch("topZoomChange", Arguments.createMap().apply { putBoolean("zoomed", zoomed) })
    }
  }

  private fun resetZoom(animate: Boolean) {
    animator?.cancel()
    if (!animate) {
      userMatrix.reset()
      reportZoom()
      invalidate()
      return
    }
    animateZoomTo(1f, width / 2f, height / 2f)
  }

  private fun animateZoomTo(target: Float, fx: Float, fy: Float) {
    animator?.cancel()
    val start = userScale()
    animator = ValueAnimator.ofFloat(start, target).apply {
      duration = 220
      interpolator = DecelerateInterpolator()
      addUpdateListener {
        val next = it.animatedValue as Float
        val cur = userScale()
        userMatrix.postScale(next / cur, next / cur, fx, fy)
        clampMatrix()
        invalidate()
      }
      start()
    }
  }

  private val scaleDetector = ScaleGestureDetector(reactContext, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
    override fun onScale(d: ScaleGestureDetector): Boolean {
      userMatrix.postScale(d.scaleFactor, d.scaleFactor, d.focusX, d.focusY)
      clampMatrix()
      invalidate()
      return true
    }
  })

  private val gestureDetector = GestureDetector(reactContext, object : GestureDetector.SimpleOnGestureListener() {
    override fun onDown(e: MotionEvent) = true

    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
      dispatch("topSingleTap", Arguments.createMap().apply {
        putDouble("x", e.x.toDouble())
        putDouble("y", e.y.toDouble())
      })
      return true
    }

    override fun onDoubleTap(e: MotionEvent): Boolean {
      if (!zoomable) return false
      if (userScale() > 1.01f) animateZoomTo(1f, e.x, e.y) else animateZoomTo(2.5f, e.x, e.y)
      return true
    }

    override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
      if (!zoomable || userScale() <= 1.01f) return false
      userMatrix.postTranslate(-dx, -dy)
      clampMatrix()
      invalidate()
      return true
    }
  })

  @SuppressLint("ClickableViewAccessibility")
  override fun onTouchEvent(event: MotionEvent): Boolean {
    if (!zoomable) return super.onTouchEvent(event)
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> if (userScale() > 1.01f) parent?.requestDisallowInterceptTouchEvent(true)
      MotionEvent.ACTION_POINTER_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
    }
    scaleDetector.onTouchEvent(event)
    gestureDetector.onTouchEvent(event)
    return true
  }

  // ---------------------------------------------------------------- events

  private fun dispatch(name: String, data: WritableMap) {
    val surfaceId = UIManagerHelper.getSurfaceId(reactContext)
    UIManagerHelper.getEventDispatcherForReactTag(reactContext, id)
      ?.dispatchEvent(VaultViewEvent(surfaceId, id, name, data))
  }
}

class VaultViewEvent(surfaceId: Int, viewId: Int, private val name: String, private val data: WritableMap) :
  Event<VaultViewEvent>(surfaceId, viewId) {
  override fun getEventName(): String = name
  override fun getEventData(): WritableMap = data
}
