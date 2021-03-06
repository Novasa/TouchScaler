package com.novasa.touchscaler

import android.graphics.PointF
import android.util.SizeF

operator fun PointF.plus(p: PointF): PointF = PointF(x + p.x, y + p.y)

operator fun PointF.minusAssign(p: PointF) {
    this.x -= p.x
    this.y -= p.y
}

operator fun PointF.timesAssign(s: SizeF) {
    x *= s.width
    y *= s.height
}

operator fun PointF.minus(p: PointF): PointF = PointF(x - p.x, y - p.y)

operator fun PointF.times(p: PointF) = PointF(x * p.x, y * p.y)

operator fun PointF.times(f: Float): PointF = PointF(x * f, y * f)


operator fun SizeF.times(p: PointF): SizeF = SizeF(width * p.x, height * p.y)

operator fun PointF.div(s: SizeF): PointF = PointF(x / s.width, y / s.height)