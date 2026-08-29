package com.koosy.autotouch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** 등록된 동작 위치를 화면 위에 번호로 표시하는(터치는 통과시키는) 뷰. */
class MarkerView(context: Context) : View(context) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#CC3D6DF6")
    }
    private val fillEnd = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#CC2FA36B")
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.WHITE
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#993D6DF6")
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = dp(12f)
        isFakeBoldText = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = dp(14f)
        ScriptStore.actions.forEachIndexed { i, a ->
            if (!a.enabled) return@forEachIndexed
            val x = a.x1.toFloat()
            val y = a.y1.toFloat()
            if (a.type == ActionType.SWIPE) {
                canvas.drawLine(x, y, a.x2.toFloat(), a.y2.toFloat(), line)
                canvas.drawCircle(a.x2.toFloat(), a.y2.toFloat(), r * 0.7f, fillEnd)
                canvas.drawCircle(a.x2.toFloat(), a.y2.toFloat(), r * 0.7f, ring)
            }
            canvas.drawCircle(x, y, r, fill)
            canvas.drawCircle(x, y, r, ring)
            canvas.drawText("${i + 1}", x, y + text.textSize / 3f, text)
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
