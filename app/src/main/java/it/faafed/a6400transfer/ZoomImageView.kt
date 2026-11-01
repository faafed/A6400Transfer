package it.faafed.a6400transfer

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.min

/**
 * Immagine con zoom a pizzico, trascinamento e doppio tap.
 * A zoom minimo uno swipe orizzontale chiama [onSwipe] (+1 successiva, -1 precedente).
 */
class ZoomImageView(context: Context) : ImageView(context) {

    var onTap: (() -> Unit)? = null
    var onSwipe: ((Int) -> Unit)? = null

    private val m = Matrix()
    private val v = FloatArray(9)
    private var bmp: Bitmap? = null
    private var minScale = 1f
    private val maxZoom = 6f // multiplo dello zoom minimo
    private var animator: ValueAnimator? = null

    init {
        scaleType = ScaleType.MATRIX
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomBy(detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        }
    )

    private val gestures = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (scaleDetector.isInProgress || !isZoomed()) return false
                m.postTranslate(-dx, -dy)
                apply()
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (isZoomed() || abs(vx) < 900f || abs(vx) < abs(vy)) return false
                onSwipe?.invoke(if (vx < 0) 1 else -1)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                animateTo(if (isZoomed()) minScale else minScale * 3f, e.x, e.y)
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onTap?.invoke()
                return true
            }
        }
    )

    /** Mostra [bm]. Con [keepView] mantiene zoom e posizione (es. miniatura -> originale). */
    fun show(bm: Bitmap, keepView: Boolean) {
        val old = bmp
        setImageBitmap(bm)
        bmp = bm
        if (keepView && old != null && width > 0 && height > 0) {
            val k = old.width.toFloat() / bm.width
            m.preScale(k, k)
            minScale = min(width.toFloat() / bm.width, height.toFloat() / bm.height)
            apply()
        } else {
            fit()
        }
    }

    fun clear() {
        animator?.cancel()
        bmp = null
        setImageDrawable(null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestures.onTouchEvent(event)
        return true
    }

    private fun currentScale(): Float {
        m.getValues(v)
        return v[Matrix.MSCALE_X]
    }

    private fun isZoomed(): Boolean = currentScale() > minScale * 1.05f

    private fun fit() {
        val b = bmp ?: return
        if (width == 0 || height == 0) return
        animator?.cancel()
        minScale = min(width.toFloat() / b.width, height.toFloat() / b.height)
        m.reset()
        m.postScale(minScale, minScale)
        apply()
    }

    private fun zoomBy(factor: Float, fx: Float, fy: Float) {
        val s = currentScale()
        val target = (s * factor).coerceIn(minScale, minScale * maxZoom)
        val k = target / s
        m.postScale(k, k, fx, fy)
        apply()
    }

    private fun animateTo(target: Float, fx: Float, fy: Float) {
        animator?.cancel()
        val start = currentScale()
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 220
        a.addUpdateListener {
            val s = start + (target - start) * (it.animatedValue as Float)
            val k = s / currentScale()
            m.postScale(k, k, fx, fy)
            apply()
        }
        animator = a
        a.start()
    }

    /** Tiene l'immagine dentro i bordi (centrata se più piccola della vista). */
    private fun apply() {
        val b = bmp
        if (b != null) {
            m.getValues(v)
            val s = v[Matrix.MSCALE_X]
            val w = b.width * s
            val h = b.height * s
            val tx = v[Matrix.MTRANS_X]
            val ty = v[Matrix.MTRANS_Y]
            val nx = if (w <= width) (width - w) / 2f else tx.coerceIn(width - w, 0f)
            val ny = if (h <= height) (height - h) / 2f else ty.coerceIn(height - h, 0f)
            m.postTranslate(nx - tx, ny - ty)
        }
        imageMatrix = m
    }
}
