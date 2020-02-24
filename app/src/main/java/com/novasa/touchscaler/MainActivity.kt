package com.novasa.touchscaler

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.SizeF
import kotlinx.android.synthetic.main.activity_main.*

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val touchScaler = TouchScaler(targetView)

        touchScaler.contentSize = with (targetView.drawable) {
            SizeF(intrinsicWidth.toFloat(), intrinsicHeight.toFloat())
        }

        contentView.setOnTouchListener(touchScaler)

        buttonReset.setOnClickListener {
            touchScaler.reset()
        }
    }
}
