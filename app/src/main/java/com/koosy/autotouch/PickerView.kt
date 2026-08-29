package com.koosy.autotouch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** 좌표 선택 중에 누른 지점과 드래그 경로를 그려 주는 뷰. */
class PickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#3D6DF6")
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        color = Color.WHITE
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#CC3D6DF6")
    }

    private var hasStart = false
    private var sx = 0f
    private var sy = 0f
    private var hasEnd = false
    private var ex = 0f
    private var ey = 0f

    fun setStart(x: Float, y: Float) {
        hasStart = true; sx = x; sy = y
        hasEnd = false
        invalidate()
    }

    fun setEnd(x: Float, y: Float) {
        hasEnd = true; ex = x; ey = y
        invalidate()
    }

    fun clearPoints() {
        hasStart = false
        hasEnd = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!hasStart) return
        if (hasEnd) {
            canvas.drawLine(sx, sy, ex, ey, line)
            dot(canvas, ex, ey, dp(11f))
        }
        dot(canvas, sx, sy, dp(14f))
    }

    private fun dot(canvas: Canvas, x: Float, y: Float, r: Float) {
        canvas.drawCircle(x, y, r, fill)
        canvas.drawCircle(x, y, r, ring)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
