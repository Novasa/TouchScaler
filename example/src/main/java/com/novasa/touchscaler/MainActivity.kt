package com.novasa.touchscaler

import android.graphics.PointF
import android.os.Bundle
import android.util.Log
import android.util.SizeF
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import kotlinx.android.synthetic.main.activity_main.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TouchScaler"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val touchScaler = TouchScaler(targetView)

        touchScaler.setContentSizeWithImageViewDrawable()

        contentView.setOnTouchListener(touchScaler)

        buttonReset.setOnClickListener {
            touchScaler.update()
                .position(.5f, .5f)
                .scale(1f)
                .relative()
                .next()
                .position(0f, 100f)
                .duration(1000)
                .scale(2f)
                .next()
                .delay(200)
                .position(.5f, .5f)
                .duration(1000)
                .relative()
                .interp(AccelerateDecelerateInterpolator())
                .scale(1f)
        }

        buttonTest.setOnClickListener {
            touchScaler.updateFocusPointOffset(PointF(.5f, .25f), true)
        }

        touchScaler.onChangeListener = object : TouchScaler.OnChangeListener {
            override fun onTouchScalerChange(scaler: TouchScaler) {
                Log.d(TAG, "Translation: ${scaler.currentTranslation}, scale: ${scaler.currentScale}, focus: ${scaler.currentFocusPoint} relative: ${scaler.currentFocusPoint / scaler.contentSize}")
            }
        }

        touchScaler.onModeChangeListener = object: TouchScaler.OnModeChangeListener {
            override fun onTouchScalerModeChange(scaler: TouchScaler, mode: TouchScaler.Mode) {
                Log.d(TAG, "Mode change: $mode")
            }
        }
    }
}
