package com.novasa.touchscaler

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.graphics.PointF
import android.os.Build
import android.util.Log
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
        private const val DEFAULT_ANIMATION_DURATION = 300L
        private val DEFAULT_INTERPOLATOR = DecelerateInterpolator()
    }

    interface OnChangeListener {
        fun onTouchScalerChange(scaler: TouchScaler)
    }

    interface OnModeChangeListener {
        fun onTouchScalerModeChange(scaler: TouchScaler, mode: Mode)
    }

    class Update {

        internal var x: Float? = null
        internal var y: Float? = null
        internal var relative = false
        internal var scale: Float? = null
        internal var duration: Long? = DEFAULT_ANIMATION_DURATION
        internal var delay: Long? = null
        internal var interpolator: TimeInterpolator? = null
        internal var next: Update? = null

        fun position(x: Float, y: Float) = this.also {
            it.x = x
            it.y = y
        }

        fun relative() = this.also {
            it.relative = true
        }

        fun scale(scale: Float) = this.also {
            it.scale = scale
        }

        fun duration(duration: Long) = this.also {
            it.duration = duration
        }

        fun delay(delay: Long) = this.also {
            it.delay = delay
        }

        fun interp(interpolator: TimeInterpolator) = this.also {
            it.interpolator = interpolator
        }

        fun animate(animate: Boolean) = this.also {
            it.duration = if (animate) it.duration ?: DEFAULT_ANIMATION_DURATION else null
        }

        fun noAnimation() = animate(false)

        fun reset() = position(.5f, .5f)
            .relative()
            .scale(1f)

        fun next() = Update().also {
            this.next = it
        }

        override fun toString(): String {
            return "Update(x=$x, y=$y, scale=$scale, duration=$duration, next?=${next != null})"
        }
    }

    enum class Mode {
        NONE,
        DRAG,
        ZOOM,
        FLING,
        ANIMATE
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

    val focusPointOffset = PointF(.5f, .5f)

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

    private fun scale(factor: Float, focus: PointF) {
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

    private fun cancelFling() {
        flingX?.cancel()?.run {
            flingX = null
            onFlingEnded()
        }

        flingY?.cancel()?.run {
            flingY = null
            onFlingEnded()
        }
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

    private fun applyScaleAndTranslation() {
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
        cancelUpdate()
        cancelFling()
    }

    private var updateAnimator: ValueAnimator? = null

    fun update(): Update = Update().also {
        targetView.post { applyUpdate(it) }
    }

    fun applyUpdate(update: Update) {

        cancelFling()
        updateSizes()

        Log.d(TAG, "Applying update: $update")

        val s1 = update.scale

        val sc1 = s1 ?: scale

        val x1 = update.x?.let { x ->
            (x.let {
                if (update.relative) it * contentSize.width
                else it
            } - overflowSize.width) * sc1 - focusPointOffset.x * viewSize.width
        }

        val y1 = update.y?.let { y ->
            (y.let {
                if (update.relative) it * contentSize.height
                else it
            } - overflowSize.height) * sc1 - focusPointOffset.y * viewSize.height
        }

        val duration = update.duration ?: 0L

        if (duration > 0L) {
            updateAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                this.duration = duration
                this.interpolator = update.interpolator ?: DEFAULT_INTERPOLATOR

                update.delay?.let { delay ->
                    this.startDelay = delay
                }

                val s0 = scale
                val x0 = targetView.translationX
                val y0 = targetView.translationY

                addUpdateListener { animator ->
                    val f = animator.animatedFraction

                    // Denormalize between start and end value
                    s1?.let { s1 ->
                        scale = f * (s1 - s0) + s0
                        applyScaleAndTranslation()
                    }

                    x1?.let {
                        targetView.translationX = (f * (it - x0) + x0)
                    }

                    y1?.let {
                        targetView.translationY = (f * (it - y0) + y0)
                    }

                    clampTranslation()
                    notifyChange()
                }

                addListener(object : AnimatorListenerAdapter() {

                    override fun onAnimationStart(animation: Animator) {
                        mode = Mode.ANIMATE
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        updateAnimator = null
                        onUpdateEnd(update)
                    }
                })

                start()
            }

        } else {
            s1?.let { s ->
                scale = s
                applyScaleAndTranslation()
            }

            x1?.let { x ->
                targetView.translationX = (x - focusPointOffset.x * viewSize.width) * scale
            }

            y1?.let { y ->
                targetView.translationY = (y - focusPointOffset.y * viewSize.height) * scale
            }

            clampTranslation()
            notifyChange()

            onUpdateEnd(update)
        }
    }

    private fun onUpdateEnd(update: Update) {
        update.next?.let { next ->
            applyUpdate(next)

        } ?: onUpdateEnd()
    }

    private fun onUpdateEnd() {
        if (mode == Mode.ANIMATE) {
            mode = Mode.NONE
        }
    }

    private fun cancelUpdate() {
        updateAnimator?.cancel()?.also {
            updateAnimator = null
        }

        onUpdateEnd()
    }

    // region Utility

    private fun clamp(v: Float, min: Float, max: Float): Float = min(max(v, min), max)

    // endregion
}