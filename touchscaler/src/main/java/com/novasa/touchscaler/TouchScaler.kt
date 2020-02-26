package com.novasa.touchscaler

import android.animation.ValueAnimator
import android.graphics.PointF
import android.os.Build
import android.util.SizeF
import android.view.*
import android.view.View.OnTouchListener
import android.view.animation.DecelerateInterpolator
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FlingAnimation
import androidx.dynamicanimation.animation.FloatPropertyCompat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class TouchScaler(val targetView: View) : OnTouchListener {

    companion object {
        private const val TAG = "TouchScaler"

        private const val DEFAULT_SCALE_MIN = 1f
        private const val DEFAULT_SCALE_MAX = 3f
    }

    interface OnChangeListener {
        fun onTouchScalerChange(scaler: TouchScaler)
    }

    interface OnModeChangeListener {
        fun onTouchScalerModeChange(scaler: TouchScaler, mode: Mode)
    }

    enum class Mode {
        NONE,
        DRAG,
        ZOOM,
        FLING
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
            targetView.apply {
                val lp = layoutParams
                lp.width = value.width.roundToInt()
                lp.height = value.height.roundToInt()
                layoutParams = lp
            }
        }

    private var viewSize: SizeF = SizeF(0f, 0f)
    private var overflowSize: SizeF = SizeF(0f, 0f)

    var mode: Mode = Mode.NONE
        private set(value) {
            if (value != field) {
                field = value
                onModeChangeListener?.onTouchScalerModeChange(this, value)
            }
        }

    private lateinit var prevEventPosition: PointF
    private val translation: PointF = PointF()
    private val translationMin: PointF = PointF()
    private val translationMax: PointF = PointF()

    var scaleMin = DEFAULT_SCALE_MIN
    var scaleMax = DEFAULT_SCALE_MAX

    private var scale = 1f
    private var prevFocus: PointF? = null

    var onChangeListener: OnChangeListener? = null
    var onModeChangeListener: OnModeChangeListener? = null

    private fun notifyChange() {
        onChangeListener?.onTouchScalerChange(this)
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {

        if (event.pointerCount > 2) {
            mode = Mode.NONE
            v.parent.requestDisallowInterceptTouchEvent(false)
            return false
        }

        val action = event.actionMasked
        val eventPosition = PointF(event.rawX, event.rawY)

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                v.parent.requestDisallowInterceptTouchEvent(true)
                mode = Mode.DRAG
                cancelAnimations()
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

                // Action index is the index of the finger that is being lifted after scaling.
                // We want to reset the focus point to the other, remaining finger, to avoid the view jumping from scale focus to event position.
                val index = event.actionIndex xor 1

                if (index != 0) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        eventPosition.x = event.getRawX(index)
                        eventPosition.y = event.getRawY(index)

                    } else {
                        val location = IntArray(2)
                        v.getLocationOnScreen(location)

                        eventPosition.x = event.getX(index) + location[0]
                        eventPosition.y = event.getY(index) + location[1]
                    }
                }
            }
        }

        updateSizes()

        scaleDetector.onTouchEvent(event)
        flingDetector.onTouchEvent(event)

        if (mode != Mode.NONE) {
            applyScaleAndTranslation()
        }

        when (action) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isFlinging) {
                    mode = Mode.NONE
                }
                v.parent.requestDisallowInterceptTouchEvent(false)
            }
        }

        prevEventPosition = eventPosition

        return true
    }

    private val scaleDetector = ScaleGestureDetector(
        targetView.context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                scale(d.scaleFactor, PointF(d.focusX, d.focusY))
                return true
            }
        })

    private val flingDetector =
        GestureDetector(targetView.context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                fling(vx, vy)
                return true
            }
        })

    fun scale(factor: Float, focus: PointF) {
        val prevScale = scale
        scale *= factor
        scale = clamp(scale, scaleMin, scaleMax)

        if (scale != prevScale) {

            val scaled = factor - 1f

            // Pivot point is always at content (0,0), so we have to translate when scaling, to account for it.
            // The last term is to account for the overflow
            translation.x = (targetView.translationX - focus.x - overflowSize.width) * scaled
            translation.y = (targetView.translationY - focus.y - overflowSize.height) * scaled
        }

        prevFocus = focus
    }

    private var flingX: FlingAnimation? = null
    private var flingY: FlingAnimation? = null
    private val isFlinging: Boolean
        get() = flingX != null || flingY != null

    private fun fling(vx: Float, vy: Float) {
        mode = Mode.FLING

        flingX = createFlingAnimation(DynamicAnimation.TRANSLATION_X, translationMin.x, translationMax.x, vx, onEnd = {
            flingX = null
        })

        flingY = createFlingAnimation(DynamicAnimation.TRANSLATION_Y, translationMin.y, translationMax.y, vy, onEnd = {
            flingY = null
        })
    }

    private fun createFlingAnimation(property: FloatPropertyCompat<View>, min: Float, max: Float, v: Float, onEnd: () -> Unit): FlingAnimation = FlingAnimation(targetView, property).apply {

        setMinValue(min)
        setMaxValue(max)
        setStartVelocity(v)
        friction = 1.1f

        addEndListener { _, _, _, _ ->
            onEnd()
            onFlingEnded()
        }

        addUpdateListener { _, _, _ ->
            notifyChange()
        }

        start()
    }

    private fun onFlingEnded() {
        if (isFlinging && mode == Mode.FLING) {
            mode = Mode.NONE
        }
    }

    private fun updateSizes(force: Boolean = false) {

        if (force || viewSize.width == 0f || viewSize.height == 0f) {
            viewSize = (targetView.parent as? View)?.let {
                SizeF(it.width.toFloat(), it.height.toFloat())
            } ?: SizeF(0f, 0f)
        }

        if (force || overflowSize.width == 0f || overflowSize.height == 0f) {
            // Overflow size is how much the content spills over the view size on each side
            overflowSize = SizeF(
                (contentSize.width - viewSize.width) * .5f,
                (contentSize.height - viewSize.height) * .5f
            )
        }

        // Calculate the max translation based on scale and content size relative to view size
        translationMin.x = -(overflowSize.width + contentSize.width * (scale - 1f))
        translationMin.y = -(overflowSize.height + contentSize.height * (scale - 1f))

        translationMax.x = overflowSize.width
        translationMax.y = overflowSize.height
    }

    fun applyScaleAndTranslation() {
        targetView.apply {

            pivotX = 0f
            pivotY = 0f

            translationX += translation.x
            translationY += translation.y

            clampTranslation()

            scaleX = scale
            scaleY = scale

            notifyChange()

//            // Scaling calculations are done with pivot point at (0,0), but we want to center the view when scale is < 1
//            pivotX = if (scale >= 1f) 0f else width * .5f
//            pivotY = if (scale >= 1f) 0f else height * .5f
        }

        translation.x = 0f
        translation.y = 0f
    }

    private fun clampTranslation() {
        // Clamp the translation according to the max values
        targetView.apply {
            translationX = clamp(translationX, translationMin.x, translationMax.x)
            translationY = clamp(translationY, translationMin.y, translationMax.y)
        }
    }

    private fun cancelAnimations() {
        targetView.animate().cancel()

        flingX?.cancel()?.run {
            flingX = null
            onFlingEnded()
        }

        flingY?.cancel()?.run {
            flingY = null
            onFlingEnded()
        }
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
                clampTranslation()

                notifyChange()
            }

            start()
        }
    }

    // region Utility

    private fun clamp(v: Float, min: Float, max: Float): Float = min(max(v, min), max)

    // endregion
}