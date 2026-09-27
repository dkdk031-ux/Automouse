package com.koosy.autotouch

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 접근성 서비스에서 띄우는 화면 위 UI 를 모두 관리한다.
 *
 * - 플로팅 조작 패널
 * - 좌표 선택 오버레이 (탭/드래그 기록)
 * - 마커 오버레이: 보기 → 편집(끌어서 이동 · 길게 눌러 시간 설정) → 끄기 3단 전환
 * - 시간 설정 시트
 * - 좌표 보정 오버레이 (2점 자동 측정)
 *
 * TYPE_ACCESSIBILITY_OVERLAY 를 쓰기 때문에 '다른 앱 위에 표시' 권한이 없어도 동작한다.
 * (일부 기기에서 실패하면 TYPE_APPLICATION_OVERLAY 로 폴백)
 */
class OverlayController(private val service: AutoTouchService) {

    companion object {
        const val MARKER_OFF = 0
        const val MARKER_VIEW = 1
        const val MARKER_EDIT = 2

        private val DURATION_PRESETS = listOf(50L, 100L, 300L, 600L, 1000L)
        private val DELAY_PRESETS = listOf(100L, 300L, 500L, 1000L, 3000L)
    }

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val inflater = LayoutInflater.from(service)
    private val handler = Handler(Looper.getMainLooper())

    private var panel: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var picker: View? = null
    private var markers: MarkerView? = null
    private var sheet: View? = null
    private var calibrator: View? = null

    private var btnRun: TextView? = null
    private var btnMarkers: TextView? = null

    private var markerMode = MARKER_OFF
    private var sheetIndex = -1

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

    private fun fullScreenParams(touchable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            flags,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
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
            x = dpi(12)
            y = dpi(180)
        }

        btnRun = v.findViewById(R.id.btnRun)
        btnMarkers = v.findViewById(R.id.btnMarkers)
        updateRunButton(service.running)
        updateMarkerButton()

        v.findViewById<View>(R.id.btnRun).setOnClickListener { service.toggle() }
        v.findViewById<View>(R.id.btnAddTap).setOnClickListener { showPicker(false) }
        v.findViewById<View>(R.id.btnAddSwipe).setOnClickListener { showPicker(true) }
        v.findViewById<View>(R.id.btnMarkers).apply {
            setOnClickListener { cycleMarkerMode() }
            setOnLongClickListener { startCalibration(); true }
        }
        v.findViewById<View>(R.id.btnApp).setOnClickListener { openApp() }
        v.findViewById<View>(R.id.btnClose).setOnClickListener {
            service.stop()
            hidePanel()
        }

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

    /** 전체화면 오버레이를 띄운 뒤 패널이 그 아래 깔리지 않도록 다시 최상단으로 올린다 */
    private fun bringPanelToFront() {
        val v = panel ?: return
        val lp = panelParams ?: return
        runCatching { wm.removeView(v) }
        if (!addView(v, lp)) {
            panel = null
            panelParams = null
            btnRun = null
            btnMarkers = null
        }
    }

    fun hidePanel() {
        setMarkerMode(MARKER_OFF)
        hideSheet()
        removeView(panel)
        panel = null
        panelParams = null
        btnRun = null
        btnMarkers = null
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

    private fun updateMarkerButton() {
        btnMarkers?.apply {
            text = when (markerMode) {
                MARKER_EDIT -> "✥"
                else -> "◉"
            }
            setBackgroundResource(
                if (markerMode == MARKER_EDIT) R.drawable.bg_round_btn_active
                else R.drawable.bg_round_btn
            )
        }
    }

    private fun openApp() {
        val i = Intent(service, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        runCatching { service.startActivity(i) }
    }

    // ------------------------------------------------------------------ 마커 / 편집 모드

    /** 보기 → 편집 → 끄기 순환 */
    fun cycleMarkerMode() {
        if (ScriptStore.actions.isEmpty()) {
            service.toast("등록된 동작이 없습니다")
            setMarkerMode(MARKER_OFF)
            return
        }
        setMarkerMode((markerMode + 1) % 3)
        when (markerMode) {
            MARKER_VIEW -> service.toast("마커 표시")
            MARKER_EDIT -> service.toast("편집 모드 — 끌어서 이동, 길게 눌러 시간 설정")
            else -> service.toast("마커 숨김")
        }
    }

    fun setMarkerMode(mode: Int) {
        markerMode = mode
        removeView(markers)
        markers = null
        hideSheet()

        if (mode != MARKER_OFF) {
            val editable = mode == MARKER_EDIT
            val v = MarkerView(
                service,
                editable = editable,
                onChanged = {
                    ScriptStore.save(service)
                    markers?.invalidate()
                },
                onLongPress = { index -> showSheet(index) }
            )
            if (addView(v, fullScreenParams(touchable = editable))) {
                markers = v
                // 편집 모드의 마커 창은 터치를 가로채므로 조작 패널을 다시 위로 올린다
                if (editable) bringPanelToFront()
            }
        }
        updateMarkerButton()
    }

    fun refreshMarkers() {
        markers?.invalidate()
        if (markerMode != MARKER_OFF && ScriptStore.actions.isEmpty()) setMarkerMode(MARKER_OFF)
    }

    // ------------------------------------------------------------------ 시간 설정 시트

    @SuppressLint("InflateParams")
    private fun showSheet(index: Int) {
        hideSheet()
        val action = ScriptStore.actions.getOrNull(index) ?: return
        sheetIndex = index

        val v = inflater.inflate(R.layout.overlay_sheet, null)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }

        val title = v.findViewById<TextView>(R.id.sheetTitle)
        val sub = v.findViewById<TextView>(R.id.sheetSub)
        val txtDuration = v.findViewById<TextView>(R.id.txtDuration)
        val txtDelay = v.findViewById<TextView>(R.id.txtDelay)
        val txtRepeat = v.findViewById<TextView>(R.id.txtRepeat)
        val chipsDuration = v.findViewById<LinearLayout>(R.id.chipsDuration)
        val chipsDelay = v.findViewById<LinearLayout>(R.id.chipsDelay)

        title.text = "동작 ${index + 1} · ${action.shortName()}"
        sub.text = if (action.type == ActionType.SWIPE)
            "(${action.x1}, ${action.y1}) → (${action.x2}, ${action.y2})"
        else
            "(${action.x1}, ${action.y1})"

        fun commit() {
            ScriptStore.save(service)
            markers?.invalidate()
            title.text = "동작 ${index + 1} · ${action.shortName()}"
        }

        fun renderDuration() {
            txtDuration.text = action.duration.toString()
            paintChips(chipsDuration, DURATION_PRESETS, action.duration)
        }

        fun renderDelay() {
            txtDelay.text = action.delayAfter.toString()
            paintChips(chipsDelay, DELAY_PRESETS, action.delayAfter)
        }

        fun renderRepeat() {
            txtRepeat.text = action.repeat.toString()
        }

        buildChips(chipsDuration, DURATION_PRESETS) { value ->
            action.duration = value
            renderDuration(); commit()
        }
        buildChips(chipsDelay, DELAY_PRESETS) { value ->
            action.delayAfter = value
            renderDelay(); commit()
        }

        v.findViewById<View>(R.id.btnDurMinus).setOnClickListener {
            action.duration = (action.duration - stepFor(action.duration)).coerceAtLeast(10L)
            renderDuration(); commit()
        }
        v.findViewById<View>(R.id.btnDurPlus).setOnClickListener {
            action.duration = (action.duration + stepFor(action.duration)).coerceAtMost(60_000L)
            renderDuration(); commit()
        }
        v.findViewById<View>(R.id.btnDelayMinus).setOnClickListener {
            action.delayAfter = (action.delayAfter - stepFor(action.delayAfter)).coerceAtLeast(0L)
            renderDelay(); commit()
        }
        v.findViewById<View>(R.id.btnDelayPlus).setOnClickListener {
            action.delayAfter = (action.delayAfter + stepFor(action.delayAfter)).coerceAtMost(600_000L)
            renderDelay(); commit()
        }
        v.findViewById<View>(R.id.btnRepMinus).setOnClickListener {
            action.repeat = (action.repeat - 1).coerceAtLeast(1)
            renderRepeat(); commit()
        }
        v.findViewById<View>(R.id.btnRepPlus).setOnClickListener {
            action.repeat = (action.repeat + 1).coerceAtMost(9999)
            renderRepeat(); commit()
        }

        v.findViewById<View>(R.id.btnSheetDelete).setOnClickListener {
            if (index in ScriptStore.actions.indices) {
                ScriptStore.actions.removeAt(index)
                ScriptStore.save(service)
                service.toast("삭제했습니다")
            }
            hideSheet()
            refreshMarkers()
        }
        v.findViewById<View>(R.id.btnSheetClose).setOnClickListener { hideSheet() }

        renderDuration()
        renderDelay()
        renderRepeat()

        if (addView(v, lp)) sheet = v
    }

    private fun hideSheet() {
        removeView(sheet)
        sheet = null
        sheetIndex = -1
    }

    private fun stepFor(v: Long): Long = when {
        v < 200L -> 10L
        v < 1000L -> 50L
        v < 5000L -> 250L
        else -> 1000L
    }

    private fun buildChips(container: LinearLayout, values: List<Long>, onPick: (Long) -> Unit) {
        container.removeAllViews()
        values.forEach { value ->
            val chip = TextView(service).apply {
                text = value.toString()
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundResource(R.drawable.bg_chip)
                setPadding(dpi(4), dpi(7), dpi(4), dpi(7))
                setOnClickListener { onPick(value) }
            }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dpi(2), 0, dpi(2), 0)
            container.addView(chip, lp)
        }
    }

    private fun paintChips(container: LinearLayout, values: List<Long>, current: Long) {
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            child.setBackgroundResource(
                if (values.getOrNull(i) == current) R.drawable.bg_chip_on else R.drawable.bg_chip
            )
            child.setPadding(dpi(4), dpi(7), dpi(4), dpi(7))
        }
    }

    // ------------------------------------------------------------------ 좌표 선택

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun showPicker(swipeMode: Boolean) {
        if (picker != null) return
        if (service.running) {
            service.toast("실행 중에는 추가할 수 없습니다")
            return
        }
        hideSheet()
        setMarkerMode(MARKER_OFF)

        val root = inflater.inflate(R.layout.overlay_picker, null) as FrameLayout
        val pv = root.findViewById<PickerView>(R.id.pickerView)
        val hint = root.findViewById<TextView>(R.id.txtHint)
        hint.text = if (swipeMode)
            "드래그할 경로를 그대로 끌어 주세요"
        else
            "클릭할 위치를 탭하세요"

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
                    val size = service.screenSize()
                    val item = if (swipeMode || (moved && dist > dp(24f))) {
                        if (dist < dp(12f)) {
                            service.toast("드래그 거리가 너무 짧습니다")
                            hidePicker(); return@setOnTouchListener true
                        }
                        ActionItem.swipe(downRawX, downRawY, e.rawX, e.rawY, elapsed, size.x, size.y)
                    } else {
                        ActionItem.tap(downRawX, downRawY, size.x, size.y).apply {
                            if (elapsed > 600) duration = elapsed.coerceAtMost(5_000L)
                        }
                    }
                    ScriptStore.actions.add(item)
                    ScriptStore.save(service)
                    service.toast("${ScriptStore.actions.size}번 동작 추가: ${item.title()}")
                    hidePicker()
                }
                MotionEvent.ACTION_CANCEL -> hidePicker()
            }
            true
        }

        if (addView(root, fullScreenParams(touchable = true))) picker = root
    }

    private fun hidePicker() {
        removeView(picker)
        picker = null
    }

    // ------------------------------------------------------------------ 좌표 보정

    private var calStep = 0
    private var calView: CalibrateView? = null
    private var calHint: TextView? = null
    private val calTargets = arrayOf(floatArrayOf(0f, 0f), floatArrayOf(0f, 0f))
    private val calLanded = arrayOf(floatArrayOf(0f, 0f), floatArrayOf(0f, 0f))
    private var calWaiting = false

    private val calTimeout = Runnable {
        if (calWaiting) {
            calWaiting = false
            finishCalibration(
                if (calStep >= 2) "보정값은 저장했지만 검증 터치를 읽지 못했습니다"
                else "자동 보정 실패 — 주입된 터치를 읽지 못했습니다. 수동 보정을 써 주세요."
            )
        }
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    fun startCalibration() {
        if (calibrator != null) return
        if (service.running) {
            service.toast("실행 중에는 보정할 수 없습니다")
            return
        }
        hideSheet()
        setMarkerMode(MARKER_OFF)
        hidePicker()

        val root = inflater.inflate(R.layout.overlay_calibrate, null) as FrameLayout
        calView = root.findViewById(R.id.calView)
        calHint = root.findViewById(R.id.calHint)
        root.findViewById<View>(R.id.btnCalCancel).setOnClickListener {
            finishCalibration("보정을 취소했습니다")
        }

        root.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN && calWaiting) {
                calWaiting = false
                handler.removeCallbacks(calTimeout)
                onCalPoint(e.rawX, e.rawY)
            }
            true
        }

        if (!addView(root, fullScreenParams(touchable = true))) return
        calibrator = root

        val size = service.screenSize()
        calTargets[0][0] = size.x * 0.22f
        calTargets[0][1] = size.y * 0.72f
        calTargets[1][0] = size.x * 0.78f
        calTargets[1][1] = size.y * 0.32f

        calStep = 0
        calHint?.text = "좌표 보정을 시작합니다.\n화면을 만지지 말고 잠시 기다려 주세요."
        handler.postDelayed({ runCalStep() }, 1200L)
    }

    private fun runCalStep() {
        if (calibrator == null) return
        if (calStep >= 2) {
            computeCalibration()
            return
        }
        val t = calTargets[calStep]
        calView?.showTarget(t[0], t[1])
        calHint?.text = "측정 중 ${calStep + 1}/2 …\n표시된 과녁을 앱이 스스로 누릅니다"
        calWaiting = true
        handler.postDelayed(calTimeout, 2500L)
        handler.postDelayed({
            service.dispatchRawTap(t[0], t[1], 60L)
        }, 400L)
    }

    private fun onCalPoint(x: Float, y: Float) {
        if (calStep >= 2) {
            // 검증 단계
            val t = calTargets[0]
            val err = hypot(x - t[0], y - t[1])
            finishCalibration(
                if (err <= 12f) "보정 완료 · 검증 오차 ${err.roundToInt()}px"
                else "보정 적용됨 · 남은 오차 ${err.roundToInt()}px"
            )
            return
        }
        calLanded[calStep][0] = x
        calLanded[calStep][1] = y
        calView?.showLanded(x, y)
        calStep++
        handler.postDelayed({ runCalStep() }, 700L)
    }

    private fun computeCalibration() {
        val t1 = calTargets[0]; val t2 = calTargets[1]
        val l1 = calLanded[0]; val l2 = calLanded[1]

        val dtx = t2[0] - t1[0]
        val dty = t2[1] - t1[1]
        if (abs(dtx) < 50f || abs(dty) < 50f) {
            finishCalibration("보정 실패 — 측정 지점이 너무 가깝습니다")
            return
        }

        val ax = (l2[0] - l1[0]) / dtx
        val ay = (l2[1] - l1[1]) / dty
        if (ax < 0.5f || ax > 2f || ay < 0.5f || ay > 2f) {
            finishCalibration("보정 실패 — 측정값이 비정상입니다 (배율 ${fmt(ax)}, ${fmt(ay)})")
            return
        }

        val bx = l1[0] - ax * t1[0]
        val by = l1[1] - ay * t1[1]

        if (abs(bx) < 2f && abs(by) < 2f && abs(ax - 1f) < 0.005f && abs(ay - 1f) < 0.005f) {
            ScriptStore.resetCalibration()
            ScriptStore.save(service)
            finishCalibration("이 화면은 보정이 필요 없습니다 (오차 없음)")
            return
        }

        ScriptStore.calScaleX = ax
        ScriptStore.calScaleY = ay
        ScriptStore.calOffsetX = bx
        ScriptStore.calOffsetY = by
        ScriptStore.save(service)

        // 검증: 첫 번째 과녁을 보정 적용해서 다시 눌러 본다
        calHint?.text = "보정값 적용 — 검증 중…"
        calView?.showTarget(calTargets[0][0], calTargets[0][1])
        calWaiting = true
        handler.postDelayed(calTimeout, 2500L)
        handler.postDelayed({
            service.dispatchCalibratedTap(calTargets[0][0], calTargets[0][1])
        }, 500L)
    }

    private fun finishCalibration(message: String) {
        calWaiting = false
        handler.removeCallbacks(calTimeout)
        calView?.clearAll()
        removeView(calibrator)
        calibrator = null
        calView = null
        calHint = null
        calStep = 0
        service.toast(message)
        ScriptStore.notifyChanged()
    }

    private fun fmt(v: Float): String = String.format("%.3f", v)

    // ------------------------------------------------------------------

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        hidePicker()
        hideSheet()
        removeView(calibrator)
        calibrator = null
        setMarkerMode(MARKER_OFF)
        removeView(panel)
        panel = null
        panelParams = null
        btnRun = null
        btnMarkers = null
    }

    private fun dpi(v: Int): Int = (v * service.resources.displayMetrics.density).toInt()
    private fun dp(v: Float): Float = v * service.resources.displayMetrics.density
}
