package com.novasa.touchscaler

import android.animation.ValueAnimator
import android.graphics.PointF
import android.util.SizeF
import android.view.*
import android.view.View.OnTouchListener
import android.view.animation.DecelerateInterpolator
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FlingAnimation
import androidx.dynamicanimation.animation.FloatPropertyCompat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

class TouchScaler(val targetView: View) : OnTouchListener {

    enum class Mode {
        NONE,
        DRAG,
        ZOOM
    }

    init {
        (targetView.parent as? ViewGroup)?.let {
            it.clipChildren = false
        }
    }

    var contentSize: SizeF = SizeF(0f, 0f)
        get() {
            if (field.width == 0f || field.height == 0f) {
                field = SizeF(targetView.width.toFloat(), targetView.height.toFloat())
            }
            return field
        }
        set(value) {
            field = SizeF(value.width, value.height)
        }

    val viewSize: SizeF
        get() = (targetView.parent as? View)?.let {
            SizeF(it.width.toFloat(), it.height.toFloat())
        } ?: SizeF(0f, 0f)


    var mode: Mode = Mode.NONE

    private var scale = 1f
    private var pScaleFactor = 0f
    private var scaleMin = 1f
    private var scaleMax = 3f

    // Where the finger first touches the screen
    private lateinit var prevEventPosition: PointF
    private var prevFocus: PointF? = null

    // How much to translate the canvas
    private val translation: PointF = PointF()
    private val translationMax: PointF = PointF()

    override fun onTouch(v: View, event: MotionEvent): Boolean {

        if (event.pointerCount > 2) {
            mode = Mode.NONE
            v.parent.requestDisallowInterceptTouchEvent(false)
            return false
        }

        val eventPosition = PointF(event.rawX, event.rawY)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelAnimations()

                v.parent.requestDisallowInterceptTouchEvent(true)

                mode = Mode.DRAG
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                mode = Mode.ZOOM
            }

            MotionEvent.ACTION_MOVE -> if (mode == Mode.DRAG) {
                translation.x = eventPosition.x - prevEventPosition.x
                translation.y = eventPosition.y - prevEventPosition.y
            }

            MotionEvent.ACTION_POINTER_UP -> {
                mode = Mode.DRAG
                prevFocus = null
//                // Action index is the index of the finger that is being lifted after scaling.
//                // We want to reset the focus point to the other, remaining finger, to avoid the view jumping from scale focus to event position.
//                val index = event.actionIndex xor 1
//                translation.x = event.getX(index) - translationPrev.x
//                translation.y = event.getY(index) - translationPrev.y
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mode = Mode.NONE
                v.parent.requestDisallowInterceptTouchEvent(false)
            }
        }

        updateTranslation()

        scaleDetector.onTouchEvent(event)
        flingDetector.onTouchEvent(event)

        if (mode != Mode.NONE) {
            applyScaleAndTranslation()
        }

        prevEventPosition = eventPosition

        return true
    }

    private val scaleDetector = ScaleGestureDetector(targetView.context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {

            val focus = PointF(d.focusX, d.focusY)

            val scaleFactor = d.scaleFactor
            if (pScaleFactor == 0f || sign(scaleFactor) == sign(pScaleFactor)) {
                val prevScale = scale
                scale *= scaleFactor
                scale = max(scaleMin, min(scale, scaleMax))
                pScaleFactor = scaleFactor

                val adjustedScaleFactor = scale / prevScale
//                prevFocus?.let {
//                    translation.x = (focus.x - it.x) * (adjustedScaleFactor - 1)
//                    translation.y = (focus.y - it.y) * (adjustedScaleFactor - 1)
//                }

            } else {
                pScaleFactor = 0f
            }

            prevFocus = focus

            return true
        }
    })

    private val flingDetector = GestureDetector(targetView.context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(e1: MotionEvent, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            fling(vx, vy)
            return true
        }
    })


    private var flingX: FlingAnimation? = null
    private var flingY: FlingAnimation? = null

    private fun fling(vx: Float, vy: Float) {
        flingX = createFlingAnimation(DynamicAnimation.TRANSLATION_X, translationMax.x, vx)
        flingY = createFlingAnimation(DynamicAnimation.TRANSLATION_Y, translationMax.y, vy)
    }

    private fun createFlingAnimation(property: FloatPropertyCompat<View>, max: Float, v: Float): FlingAnimation = FlingAnimation(targetView, property).apply {
        setMaxValue(max)
        setMinValue(-max)
        setStartVelocity(v)
        friction = 1.1f

        start()
    }

    private fun updateTranslation() {

        // Calculate the max translation based on scale and content size relative to view size
        translationMax.x = (contentSize.width - viewSize.width) * .5f * scale
        translationMax.y = (contentSize.height - viewSize.height) * .5f * scale
    }

    private fun applyScaleAndTranslation() {
        targetView.apply {

            pivotX = 0f
            pivotY = 0f

            scaleX = scale
            scaleY = scale

            // Clamp the translation according to the max values
            translationX = min(max(translationX + translation.x, -translationMax.x), translationMax.x)
            translationY = min(max(translationY + translation.y, -translationMax.y), translationMax.y)

//            // Scaling calculations are done with pivot point at (0,0), but we want to center the view when scale is < 1
//            pivotX = if (scale >= 1f) 0f else width * .5f
//            pivotY = if (scale >= 1f) 0f else height * .5f
        }
    }


    private fun cancelAnimations() {
        targetView.animate().cancel()

        flingX?.cancel()
        flingX = null

        flingY?.cancel()
        flingY = null
    }

    private var valueAnimator: ValueAnimator? = null

    fun reset() {
        resetAnimated()
    }

    private fun resetAnimated() {

        valueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200
            interpolator = DecelerateInterpolator()

            val s0 = scale
            val s1 = 1f

            val x0 = targetView.translationX
            val y0 = targetView.translationY

            addUpdateListener { animator ->
                val f = animator.animatedFraction

                // Denormalize between start and end value
                scale = f * (s1 - s0) + s0
                applyScaleAndTranslation()

                targetView.translationX = (1f - f) * x0
                targetView.translationY = (1f - f) * y0
            }

            start()
        }
    }
}