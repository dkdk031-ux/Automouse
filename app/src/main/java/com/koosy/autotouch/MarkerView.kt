package com.koosy.autotouch

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 등록된 동작 위치를 화면 위에 번호로 표시하는 뷰.
 *
 * [editable] 이 true 면 터치를 받아
 *  - 마커를 끌어서 좌표 이동
 *  - 마커를 길게 눌러 [onLongPress] 호출 (시간 설정 시트)
 * 를 할 수 있다.
 */
@SuppressLint("ViewConstructor")
class MarkerView(
    context: Context,
    private val editable: Boolean = false,
    private val onChanged: (() -> Unit)? = null,
    private val onLongPress: ((Int) -> Unit)? = null
) : View(context) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#CC3D6DF6")
    }
    private val fillActive = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFE5484D")
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
    private val coordText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = dp(11f)
        isFakeBoldText = true
    }
    private val coordBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#CC202226")
    }

    private val loc = IntArray(2)

    private val handler = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private var dragIndex = -1
    private var dragIsEnd = false
    private var downX = 0f
    private var downY = 0f
    private var moved = false

    private val longPressRunnable = Runnable {
        val i = dragIndex
        if (i >= 0 && !moved) {
            dragIndex = -1
            invalidate()
            onLongPress?.invoke(i)
        }
    }

    // ------------------------------------------------------------------ 그리기

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        getLocationOnScreen(loc)
        val ox = loc[0].toFloat()
        val oy = loc[1].toFloat()
        val r = dp(if (editable) 17f else 14f)

        ScriptStore.actions.forEachIndexed { i, a ->
            if (!a.enabled) return@forEachIndexed
            val x = a.x1 - ox
            val y = a.y1 - oy
            val active = editable && i == dragIndex

            if (a.type == ActionType.SWIPE) {
                val ex = a.x2 - ox
                val ey = a.y2 - oy
                canvas.drawLine(x, y, ex, ey, line)
                canvas.drawCircle(ex, ey, r * 0.72f, if (active && dragIsEnd) fillActive else fillEnd)
                canvas.drawCircle(ex, ey, r * 0.72f, ring)
            }

            canvas.drawCircle(x, y, r, if (active && !dragIsEnd) fillActive else fill)
            canvas.drawCircle(x, y, r, ring)
            canvas.drawText("${i + 1}", x, y + text.textSize / 3f, text)

            if (active) drawCoordLabel(canvas, x, y, a)
        }
    }

    private fun drawCoordLabel(canvas: Canvas, x: Float, y: Float, a: ActionItem) {
        val label = if (dragIsEnd) "${a.x2}, ${a.y2}" else "${a.x1}, ${a.y1}"
        val padH = dp(8f)
        val padV = dp(5f)
        val w = coordText.measureText(label) + padH * 2
        val h = coordText.textSize + padV * 2
        val cx = x
        val top = y - dp(30f) - h
        canvas.drawRoundRect(
            cx - w / 2, top, cx + w / 2, top + h, dp(8f), dp(8f), coordBg
        )
        canvas.drawText(label, cx, top + h - padV - dp(1f), coordText)
    }

    // ------------------------------------------------------------------ 편집 터치

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!editable) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = hitTest(event.rawX, event.rawY) ?: return false
                dragIndex = hit
                dragIsEnd = lastHitWasEnd
                downX = event.rawX
                downY = event.rawY
                moved = false
                handler.postDelayed(longPressRunnable, 500L)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragIndex < 0) return false
                if (!moved && hypot(event.rawX - downX, event.rawY - downY) > slop) {
                    moved = true
                    handler.removeCallbacks(longPressRunnable)
                }
                if (moved) {
                    val a = ScriptStore.actions.getOrNull(dragIndex)
                    if (a != null) {
                        if (dragIsEnd) {
                            a.x2 = event.rawX.roundToInt()
                            a.y2 = event.rawY.roundToInt()
                        } else {
                            a.x1 = event.rawX.roundToInt()
                            a.y1 = event.rawY.roundToInt()
                        }
                        invalidate()
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                val wasDragging = dragIndex >= 0 && moved
                dragIndex = -1
                moved = false
                invalidate()
                if (wasDragging) onChanged?.invoke()
                return true
            }
        }
        return false
    }

    private var lastHitWasEnd = false

    /** 화면 좌표에서 가장 가까운 핸들을 찾는다. 없으면 null */
    private fun hitTest(x: Float, y: Float): Int? {
        val radius = dp(28f)
        var best: Int? = null
        var bestDist = radius
        var bestIsEnd = false

        ScriptStore.actions.forEachIndexed { i, a ->
            if (!a.enabled) return@forEachIndexed
            val d1 = hypot(x - a.x1, y - a.y1)
            if (d1 < bestDist) { bestDist = d1; best = i; bestIsEnd = false }
            if (a.type == ActionType.SWIPE) {
                val d2 = hypot(x - a.x2, y - a.y2)
                if (d2 < bestDist) { bestDist = d2; best = i; bestIsEnd = true }
            }
        }
        lastHitWasEnd = bestIsEnd
        return best
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(longPressRunnable)
        super.onDetachedFromWindow()
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
