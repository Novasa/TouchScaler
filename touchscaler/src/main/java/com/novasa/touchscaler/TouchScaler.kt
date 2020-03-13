package com.novasa.touchscaler

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.graphics.PointF
import android.os.Build
import android.util.SizeF
import android.view.*
import android.view.View.OnTouchListener
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.plus
import androidx.core.view.doOnLayout
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FlingAnimation
import androidx.dynamicanimation.animation.FloatPropertyCompat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Suppress("unused", "MemberVisibilityCanBePrivate")
class TouchScaler(val targetView: View) : OnTouchListener {

    companion object {
        private const val TAG = "TouchScaler"

        private const val DEFAULT_SCALE_MIN = 1f
        private const val DEFAULT_SCALE_MAX = 3f
        private const val DEFAULT_ANIMATION_DURATION = 300L
        private val DEFAULT_INTERPOLATOR = DecelerateInterpolator()

        private val SIZE_NONE = SizeF(0f, 0f)
        private val ORIGIN = PointF()
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
        FLING,
        ANIMATE
    }

    var contentSize: SizeF = SIZE_NONE
        set(value) {
            field = SizeF(value.width, value.height)
            updateSize()
        }

    private var viewSize: SizeF = SIZE_NONE

    private fun updateSize() {
        if (contentSize.width == 0f || contentSize.height == 0f) {
            targetView.doOnLayout {
                contentSize = SizeF(targetView.width.toFloat(), targetView.height.toFloat())
            }
            return
        }

        targetView.apply {
            val lp = layoutParams
            lp.width = contentSize.width.roundToInt()
            lp.height = contentSize.height.roundToInt()
            layoutParams = lp
        }

        targetView.doOnLayout {
            viewSize = (targetView.parent as? View)?.let { parent ->
                SizeF(parent.width.toFloat(), parent.height.toFloat())
            } ?: SizeF(0f, 0f)

            updateTranslationBoundaries()
            currentTranslation = focusPointToTranslation(PointF(contentSize.width * .5f, contentSize.height * .5f), currentScale)
        }
    }

    init {
        targetView.apply {
            pivotX = 0f
            pivotY = 0f

            (parent as? ViewGroup)?.clipChildren = false
        }

        updateSize()
    }


    var mode: Mode = Mode.NONE
        private set(value) {
            if (value != field) {
                field = value
                onModeChangeListener?.onTouchScalerModeChange(this, value)
            }
        }

    var onChangeListener: OnChangeListener? = null
    var onModeChangeListener: OnModeChangeListener? = null

    private fun notifyChange() {
        onChangeListener?.onTouchScalerChange(this)
    }

    private fun cancelAnimations() {
        cancelUpdate()
        cancelFling()
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
                startDrag(v)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                mode = Mode.ZOOM
            }

            MotionEvent.ACTION_MOVE -> if (mode == Mode.NONE) {
                // It's possible to receive a move event as the initial event, if the event has been delegated to another view before we receive it.
                startDrag(v)

            } else if (mode == Mode.DRAG) {
                translation.x = eventPosition.x - prevEventPosition.x
                translation.y = eventPosition.y - prevEventPosition.y
            }

            MotionEvent.ACTION_POINTER_UP -> {
                mode = Mode.DRAG
                prevScaleFocus = null

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

        scaleDetector.onTouchEvent(event)
        flingDetector.onTouchEvent(event)

        if (mode != Mode.NONE) {
            applyTranslation()
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

    private fun startDrag(v: View) {
        v.parent.requestDisallowInterceptTouchEvent(true)
        mode = Mode.DRAG
        cancelAnimations()
    }


    // region Focus

    var focusPointOffset = PointF(.5f, .5f)
        private set

    var currentFocusPoint: PointF
        get() = translationToFocusPoint(currentTranslation, currentScale)
        private set(value) {
            currentTranslation = focusPointToTranslation(value, currentScale)
        }

    fun updateFocusPointOffset(offset: PointF, update: Boolean) {
        if (update && mode != Mode.ANIMATE) {
            // Animation will handle the focus offset update

            val focusPoint = currentFocusPoint
            focusPointOffset = offset

            // Restore the focus point with the new offset
            currentFocusPoint = focusPoint

        } else {
            focusPointOffset = offset
        }
    }

    // endregion


    // region Translation

    private lateinit var prevEventPosition: PointF
    private val translation: PointF = PointF()
    private val translationMin: PointF = PointF()
    private val translationMax: PointF = PointF()

    var currentTranslation: PointF
        get() = PointF(targetView.x, targetView.y)
        private set(value) = targetView.run {
            x = value.x
            y = value.y
        }


    private fun updateTranslationBoundaries() {

        // Calculate the max translation based on scale and content size relative to view size
        translationMin.x = -contentSize.width * currentScale + viewSize.width
        translationMin.y = -contentSize.height * currentScale + viewSize.height

        translationMax.x = 0f
        translationMax.y = 0f
    }

    private fun applyTranslation() {
        targetView.apply {

            currentTranslation += translation

            clampTranslation()
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
        currentTranslation = clamp(currentTranslation, translationMin, translationMax)
    }

    // endregion


    // region Scaling

    var scaleMin = DEFAULT_SCALE_MIN
    var scaleMax = DEFAULT_SCALE_MAX

    var currentScale: Float
        get() = targetView.scaleX
        set(value) {
            targetView.apply {
                scaleX = value
                scaleY = value
            }
            updateTranslationBoundaries()
        }

    private var prevScaleFocus: PointF? = null

    private val scaleDetector = ScaleGestureDetector(
            targetView.context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    scale(d.scaleFactor, PointF(d.focusX, d.focusY))
                    return true
                }
            })

    private fun scale(factor: Float, focus: PointF) {
        val s0 = currentScale
        val s1 = clamp(s0 * factor, scaleMin, scaleMax)

        // The adjusted amount that the scale will change.
        // This will be 0 if there is no change, so the only translation happening will be focus point change
        val scaled = (s1 / s0) - 1f

        // We add translation for focus point change
        val dFocus = prevScaleFocus?.let {
            focus - it
        } ?: ORIGIN

        // Pivot point is always at content (0,0), so we have to translate when scaling, to account for it.
        val currentTranslation = currentTranslation
        translation.x = (currentTranslation.x - focus.x) * scaled + dFocus.x
        translation.y = (currentTranslation.y - focus.y) * scaled + dFocus.y

        if (s1 != currentScale) {
            currentScale = s1
        }

        prevScaleFocus = focus
    }

    // endregion


    // region Fling

    private var flingX: FlingAnimation? = null
    private var flingY: FlingAnimation? = null
    private val isFlinging: Boolean
        get() = flingX != null || flingY != null

    private val flingDetector = GestureDetector(targetView.context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(e1: MotionEvent?, e2: MotionEvent?, vx: Float, vy: Float): Boolean {
            fling(vx, vy)
            return true
        }
    })

    private fun fling(vx: Float, vy: Float) {
        mode = Mode.FLING

        flingX = createFlingAnimation(DynamicAnimation.X, translationMin.x, translationMax.x, vx, onEnd = {
            flingX = null
        })

        flingY = createFlingAnimation(DynamicAnimation.Y, translationMin.y, translationMax.y, vy, onEnd = {
            flingY = null
        })
    }

    private fun createFlingAnimation(
            property: FloatPropertyCompat<View>,
            min: Float,
            max: Float,
            v: Float,
            onEnd: () -> Unit
    ): FlingAnimation = FlingAnimation(targetView, property).apply {

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

    // endregion


    // region Updates

    class Update {

        @JvmSynthetic
        internal var position: PointF? = null

        @JvmSynthetic
        internal var relative = false

        @JvmSynthetic
        internal var scale: Float? = null

        @JvmSynthetic
        internal var duration: Long? = DEFAULT_ANIMATION_DURATION

        @JvmSynthetic
        internal var delay: Long? = null

        @JvmSynthetic
        internal var interpolator: TimeInterpolator? = null

        @JvmSynthetic
        internal var next: Update? = null

        fun position(position: PointF) = this.apply {
            this.position = position
        }

        fun position(x: Float, y: Float) = position(PointF(x, y))

        fun relative() = this.apply {
            this.relative = true
        }

        fun scale(scale: Float) = this.apply {
            this.scale = scale
        }

        fun duration(duration: Long) = this.apply {
            this.duration = duration
        }

        fun delay(delay: Long) = this.apply {
            this.delay = delay
        }

        fun interp(interpolator: TimeInterpolator) = this.apply {
            this.interpolator = interpolator
        }

        fun animate(animate: Boolean) = this.apply {
            this.duration = if (animate) this.duration ?: DEFAULT_ANIMATION_DURATION else null
        }

        fun noAnimation() = animate(false)

        fun reset() = position(.5f, .5f)
                .relative()
                .scale(1f)

        fun next() = Update().also {
            this.next = it
        }

        fun next(update: Update) = this.apply {
            this.next = update
        }

        override fun toString(): String {
            return "Update(position=$position, scale=$scale, duration=$duration, next?=${next != null})"
        }
    }

    private var updateAnimator: UpdateAnimator? = null

    fun update(): Update = Update().also {
        targetView.post { applyUpdate(it) }
    }

    fun applyUpdate(update: Update) {

        cancelFling()

        val s1 = update.scale ?: currentScale
        val p1 = update.position?.let {
            PointF(it.x, it.y).also { p1 ->
                if (update.relative) {
                    p1 *= contentSize
                }

                // We animate the focus point, and translate it after
                focusPointToTranslation(p1, s1)
            }
        }

        val duration = update.duration ?: 0L

        if (duration > 0L) {
            updateAnimator = UpdateAnimator().apply {
                this.duration = duration
                this.interpolator = update.interpolator ?: DEFAULT_INTERPOLATOR

                update.delay?.let { delay ->
                    this.startDelay = delay
                }

                val s0 = currentScale
                val p0 = currentFocusPoint

                addUpdateListener { animator ->

                    val f = animator.animatedFraction

                    val s = f * (s1 - s0) + s0
                    if (s1 != s0) {
                        currentScale = s
                    }

                    p1?.let { p1: PointF ->
                        val p = PointF().apply {
                            x = (f * (p1.x - p0.x) + p0.x)
                            y = (f * (p1.y - p0.y) + p0.y)
                        }

                        currentTranslation = focusPointToTranslation(p, s)
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

                        (animation as? UpdateAnimator)?.let {
                            if (!it.isCancelled) {
                                onUpdateEnd(update)
                            }
                        }
                    }
                })

                start()
            }

        } else {
            if (s1 != currentScale) {
                currentScale = s1
            }

            p1?.let {
                currentTranslation = focusPointToTranslation(it, currentScale)
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

    private class UpdateAnimator : ValueAnimator() {

        var isCancelled = false

        init {
            setFloatValues(0f, 1f)
        }

        override fun cancel() {
            isCancelled = true
            super.cancel()
        }
    }

    // endregion


    // region Utility

    private fun clamp(v: PointF, min: PointF, max: PointF): PointF =
            PointF(min(max(v.x, min.x), max.x), min(max(v.y, min.y), max.y))

    private fun clamp(v: Float, min: Float, max: Float): Float = min(max(v, min), max)

    private fun focusPointToTranslation(focus: PointF, scale: Float): PointF = PointF().apply {
        x = -focus.x * scale + focusPointOffset.x * viewSize.width
        y = -focus.y * scale + focusPointOffset.y * viewSize.height
    }

    private fun translationToFocusPoint(translation: PointF, scale: Float) = PointF().apply {
        x = -(translation.x - focusPointOffset.x * viewSize.width) / scale
        y = -(translation.y - focusPointOffset.y * viewSize.height) / scale
    }

    // endregion
}