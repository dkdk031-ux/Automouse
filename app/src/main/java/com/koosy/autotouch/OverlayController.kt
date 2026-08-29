package com.koosy.autotouch

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

import kotlin.math.hypot

/**
 * 접근성 서비스에서 띄우는 화면 위 UI 를 모두 관리한다.
 *
 * - 플로팅 조작 패널 (시작/정지, 지점 추가, 드래그 추가, 마커, 앱 열기, 닫기)
 * - 좌표 선택 오버레이 (탭 한 번 / 드래그 한 번을 그대로 기록)
 * - 등록된 좌표를 번호로 보여주는 마커 오버레이
 *
 * TYPE_ACCESSIBILITY_OVERLAY 를 쓰기 때문에 '다른 앱 위에 표시' 권한이 없어도 동작한다.
 * (일부 기기에서 실패하면 TYPE_APPLICATION_OVERLAY 로 폴백)
 */
class OverlayController(private val service: AutoTouchService) {

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val inflater = LayoutInflater.from(service)

    private var panel: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var picker: View? = null
    private var markers: MarkerView? = null

    private var btnRun: TextView? = null

    // ------------------------------------------------------------------ 공통

    private fun overlayType(): Int =
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

    private fun fallbackType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

    private fun addView(view: View, params: WindowManager.LayoutParams): Boolean {
        return try {
            wm.addView(view, params)
            true
        } catch (t: Throwable) {
            // 접근성 오버레이가 거부되면 일반 오버레이로 재시도
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(service)) {
                try {
                    params.type = fallbackType()
                    wm.addView(view, params)
                    true
                } catch (t2: Throwable) {
                    service.toast("오버레이를 표시할 수 없습니다")
                    false
                }
            } else {
                service.toast("'다른 앱 위에 표시' 권한이 필요합니다")
                false
            }
        }
    }

    private fun removeView(view: View?) {
        if (view == null) return
        runCatching { wm.removeView(view) }
    }

    // ------------------------------------------------------------------ 플로팅 패널

    fun isPanelShown(): Boolean = panel != null

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    fun showPanel() {
        if (panel != null) return
        val v = inflater.inflate(R.layout.overlay_panel, null)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(180)
        }

        btnRun = v.findViewById(R.id.btnRun)
        updateRunButton(service.running)

        v.findViewById<View>(R.id.btnRun).setOnClickListener { service.toggle() }
        v.findViewById<View>(R.id.btnAddTap).setOnClickListener { showPicker(false) }
        v.findViewById<View>(R.id.btnAddSwipe).setOnClickListener { showPicker(true) }
        v.findViewById<View>(R.id.btnMarkers).setOnClickListener { toggleMarkers() }
        v.findViewById<View>(R.id.btnApp).setOnClickListener { openApp() }
        v.findViewById<View>(R.id.btnClose).setOnClickListener {
            service.stop()
            hidePanel()
        }

        // 손잡이를 잡고 패널 이동
        val handle = v.findViewById<View>(R.id.handle)
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y
                    touchX = e.rawX; touchY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - touchX).toInt()
                    lp.y = startY + (e.rawY - touchY).toInt()
                    runCatching { wm.updateViewLayout(v, lp) }
                    true
                }
                else -> true
            }
        }

        if (addView(v, lp)) {
            panel = v
            panelParams = lp
        }
    }

    fun hidePanel() {
        removeView(panel)
        panel = null
        panelParams = null
        btnRun = null
        hideMarkers()
    }

    fun onRunStateChanged(running: Boolean) {
        panel?.post { updateRunButton(running) }
        markers?.post { markers?.invalidate() }
    }

    private fun updateRunButton(running: Boolean) {
        btnRun?.apply {
            text = if (running) "■" else "▶"
            setBackgroundResource(
                if (running) R.drawable.bg_round_btn_active else R.drawable.bg_round_btn
            )
        }
    }

    private fun openApp() {
        val i = Intent(service, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        runCatching { service.startActivity(i) }
    }

    // ------------------------------------------------------------------ 마커

    fun toggleMarkers() {
        if (markers != null) hideMarkers() else showMarkers()
    }

    private fun showMarkers() {
        if (markers != null) return
        if (ScriptStore.actions.isEmpty()) {
            service.toast("등록된 동작이 없습니다")
            return
        }
        val v = MarkerView(service)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        if (addView(v, lp)) markers = v
    }

    private fun hideMarkers() {
        removeView(markers)
        markers = null
    }

    fun refreshMarkers() {
        markers?.invalidate()
    }

    // ------------------------------------------------------------------ 좌표 선택

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun showPicker(swipeMode: Boolean) {
        if (picker != null) return
        if (service.running) {
            service.toast("실행 중에는 추가할 수 없습니다")
            return
        }

        val root = inflater.inflate(R.layout.overlay_picker, null) as FrameLayout
        val pv = root.findViewById<PickerView>(R.id.pickerView)
        val hint = root.findViewById<TextView>(R.id.txtHint)
        hint.text = if (swipeMode)
            "드래그할 경로를 그대로 끌어 주세요"
        else
            "클릭할 위치를 탭하세요"

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        root.findViewById<View>(R.id.btnCancelPick).setOnClickListener { hidePicker() }

        var downRawX = 0f
        var downRawY = 0f
        var downTime = 0L
        var moved = false

        root.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX; downRawY = e.rawY
                    downTime = System.currentTimeMillis()
                    moved = false
                    pv.setStart(e.x, e.y)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (hypot(e.rawX - downRawX, e.rawY - downRawY) > dp(8f)) {
                        moved = true
                        pv.setEnd(e.x, e.y)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = System.currentTimeMillis() - downTime
                    val dist = hypot(e.rawX - downRawX, e.rawY - downRawY)
                    val item = if (swipeMode || (moved && dist > dp(24f))) {
                        if (dist < dp(12f)) {
                            service.toast("드래그 거리가 너무 짧습니다")
                            hidePicker(); return@setOnTouchListener true
                        }
                        ActionItem.swipe(downRawX, downRawY, e.rawX, e.rawY, elapsed)
                    } else {
                        ActionItem.tap(downRawX, downRawY).apply {
                            // 길게 누른 경우 롱프레스로 기록
                            if (elapsed > 600) duration = elapsed.coerceAtMost(5_000L)
                        }
                    }
                    ScriptStore.actions.add(item)
                    ScriptStore.save(service)
                    service.toast("${ScriptStore.actions.size}번 동작 추가: ${item.title()}")
                    refreshMarkers()
                    hidePicker()
                }
                MotionEvent.ACTION_CANCEL -> hidePicker()
            }
            true
        }

        if (addView(root, lp)) picker = root
    }

    private fun hidePicker() {
        removeView(picker)
        picker = null
    }

    // ------------------------------------------------------------------

    fun destroy() {
        hidePicker()
        hideMarkers()
        hidePanel()
    }

    private fun dp(v: Int): Int = (v * service.resources.displayMetrics.density).toInt()
    private fun dp(v: Float): Float = v * service.resources.displayMetrics.density
}
