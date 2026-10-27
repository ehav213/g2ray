package com.a340.technicalassistant

import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.max
import kotlin.math.min

class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val matrixValue = Matrix()
    private var baseScale = 1f
    private var currentScale = 1f
    private var lastX = 0f
    private var lastY = 0f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val target = (currentScale * detector.scaleFactor).coerceIn(baseScale, baseScale * 6f)
            val factor = target / currentScale
            matrixValue.postScale(factor, factor, detector.focusX, detector.focusY)
            currentScale = target
            constrain()
            imageMatrix = matrixValue
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val target = if (currentScale > baseScale * 1.4f) baseScale else min(baseScale * 2.5f, baseScale * 6f)
            zoomTo(target, e.x, e.y)
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        post { resetZoom() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { resetZoom() }
    }

    fun resetZoom() {
        val d = drawable ?: return
        if (width <= 0 || height <= 0 || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return
        val sx = width.toFloat() / d.intrinsicWidth.toFloat()
        val sy = height.toFloat() / d.intrinsicHeight.toFloat()
        baseScale = min(sx, sy)
        currentScale = baseScale
        matrixValue.reset()
        matrixValue.postScale(baseScale, baseScale)
        val dx = (width - d.intrinsicWidth * baseScale) / 2f
        val dy = (height - d.intrinsicHeight * baseScale) / 2f
        matrixValue.postTranslate(dx, dy)
        imageMatrix = matrixValue
    }

    fun zoomIn() = zoomTo(min(currentScale * 1.35f, baseScale * 6f), width / 2f, height / 2f)
    fun zoomOut() = zoomTo(max(currentScale / 1.35f, baseScale), width / 2f, height / 2f)

    private fun zoomTo(target: Float, px: Float, py: Float) {
        val clamped = target.coerceIn(baseScale, baseScale * 6f)
        val factor = clamped / currentScale
        matrixValue.postScale(factor, factor, px, py)
        currentScale = clamped
        constrain()
        imageMatrix = matrixValue
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> if (!scaleDetector.isInProgress && currentScale > baseScale) {
                val dx = event.x - lastX
                val dy = event.y - lastY
                matrixValue.postTranslate(dx, dy)
                constrain()
                imageMatrix = matrixValue
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    private fun constrain() {
        val d = drawable ?: return
        val values = FloatArray(9)
        matrixValue.getValues(values)
        val scale = values[Matrix.MSCALE_X]
        var tx = values[Matrix.MTRANS_X]
        var ty = values[Matrix.MTRANS_Y]
        val contentW = d.intrinsicWidth * scale
        val contentH = d.intrinsicHeight * scale

        tx = if (contentW <= width) (width - contentW) / 2f else tx.coerceIn(width - contentW, 0f)
        ty = if (contentH <= height) (height - contentH) / 2f else ty.coerceIn(height - contentH, 0f)
        values[Matrix.MTRANS_X] = tx
        values[Matrix.MTRANS_Y] = ty
        matrixValue.setValues(values)
    }
}
