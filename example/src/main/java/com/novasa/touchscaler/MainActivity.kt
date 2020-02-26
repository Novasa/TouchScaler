package com.novasa.touchscaler

import android.os.Bundle
import android.util.Log
import android.util.SizeF
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

        touchScaler.contentSize = with(targetView.drawable) {
            SizeF(intrinsicWidth.toFloat(), intrinsicHeight.toFloat())
        }

        contentView.setOnTouchListener(touchScaler)

        buttonReset.setOnClickListener {
            touchScaler.update()
                .position(.5f, .5f)
                .relative()
        }

        buttonTest.setOnClickListener {
            touchScaler.update()
                .scale(2f)
        }

        touchScaler.onChangeListener = object : TouchScaler.OnChangeListener {
            override fun onTouchScalerChange(scaler: TouchScaler) {
                Log.d(TAG, "Translation: ${targetView.translationX}, ${targetView.translationY}")
            }
        }

        touchScaler.onModeChangeListener = object: TouchScaler.OnModeChangeListener {
            override fun onTouchScalerModeChange(scaler: TouchScaler, mode: TouchScaler.Mode) {
                Log.d(TAG, "Mode change: $mode")
            }
        }
    }
}
