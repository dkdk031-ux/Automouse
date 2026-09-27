package com.koosy.autotouch

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** 좌표 보정 중 목표 지점(과녁)과 실제로 눌린 지점을 그려 주는 뷰. */
@SuppressLint("ViewConstructor")
class CalibrateView(context: Context) : View(context) {

    private val target = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = Color.parseColor("#FF3D6DF6")
    }
    private val targetDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FF3D6DF6")
    }
    private val landed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFE5484D")
    }
    private val landedRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.WHITE
    }
    private val gap = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.parseColor("#FFE5484D")
    }

    private val loc = IntArray(2)

    var targetX = -1f
    var targetY = -1f
    var landedX = -1f
    var landedY = -1f

    fun showTarget(x: Float, y: Float) {
        targetX = x; targetY = y
        landedX = -1f; landedY = -1f
        invalidate()
    }

    fun showLanded(x: Float, y: Float) {
        landedX = x; landedY = y
        invalidate()
    }

    fun clearAll() {
        targetX = -1f; targetY = -1f
        landedX = -1f; landedY = -1f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        getLocationOnScreen(loc)
        val ox = loc[0].toFloat()
        val oy = loc[1].toFloat()

        if (targetX >= 0f) {
            val x = targetX - ox
            val y = targetY - oy
            canvas.drawCircle(x, y, dp(30f), target)
            canvas.drawCircle(x, y, dp(16f), target)
            canvas.drawCircle(x, y, dp(4f), targetDot)
            canvas.drawLine(x - dp(44f), y, x - dp(34f), y, target)
            canvas.drawLine(x + dp(34f), y, x + dp(44f), y, target)
            canvas.drawLine(x, y - dp(44f), x, y - dp(34f), target)
            canvas.drawLine(x, y + dp(34f), x, y + dp(44f), target)
        }

        if (landedX >= 0f) {
            val lx = landedX - ox
            val ly = landedY - oy
            canvas.drawCircle(lx, ly, dp(7f), landed)
            canvas.drawCircle(lx, ly, dp(7f), landedRing)
            if (targetX >= 0f) {
                canvas.drawLine(targetX - ox, targetY - oy, lx, ly, gap)
            }
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
