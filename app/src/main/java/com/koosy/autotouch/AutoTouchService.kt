package com.koosy.autotouch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import kotlin.math.max
import kotlin.random.Random

/**
 * 실제로 화면을 탭/드래그하는 접근성 서비스.
 * dispatchGesture() 로 제스처를 주입하며, 콜백을 이어 붙여 시퀀스를 진행한다.
 */
class AutoTouchService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AutoTouchService? = null
            private set

        /** 서비스가 켜져 있는지 (시스템 설정 기준) */
        fun isEnabled(ctx: Context): Boolean {
            val expected = "${ctx.packageName}/${AutoTouchService::class.java.name}"
            val enabled = Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            while (splitter.hasNext()) {
                if (splitter.next().equals(expected, ignoreCase = true)) return true
            }
            return false
        }

        fun isRunning(): Boolean = instance?.running == true
    }

    private val handler = Handler(Looper.getMainLooper())

    var running = false
        private set

    private var snapshot: List<ActionItem> = emptyList()
    private var actionIndex = 0
    private var repeatIndex = 0
    private var loopIndex = 0

    private var overlay: OverlayController? = null

    private val stateListeners = mutableListOf<(Boolean) -> Unit>()

    fun addStateListener(l: (Boolean) -> Unit) { stateListeners.add(l) }
    fun removeStateListener(l: (Boolean) -> Unit) { stateListeners.remove(l) }

    private fun notifyState() {
        val r = running
        handler.post { stateListeners.toList().forEach { it(r) } }
    }

    // ------------------------------------------------------------------ 생명주기

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        ScriptStore.ensureLoaded(this)
        overlay = OverlayController(this).also { it.showPanel() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* 사용하지 않음 */ }

    override fun onInterrupt() {
        stop()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stop()
        overlay?.destroy()
        overlay = null
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stop()
        overlay?.destroy()
        overlay = null
        instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 화면 크기

    fun screenSize(): Point {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            val p = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
            p
        }
    }

    // ------------------------------------------------------------------ 오버레이 조작

    fun overlayController(): OverlayController? = overlay

    fun showPanel() { overlay?.showPanel() }
    fun hidePanel() { overlay?.hidePanel() }
    fun isPanelShown(): Boolean = overlay?.isPanelShown() == true

    /** 앱 화면에서 '자동 보정' 을 눌렀을 때 */
    fun startCalibration() {
        if (running) { toast("실행 중에는 보정할 수 없습니다"); return }
        overlay?.startCalibration()
    }

    // ------------------------------------------------------------------ 좌표 변환

    /**
     * 저장된 좌표 → 실제로 주입할 좌표.
     * 1) 기록 당시 화면 크기와 지금이 다르면 비례 보정 (폴더블 대응)
     * 2) 보정값(offset/scale) 역변환 적용
     */
    private fun mapPoint(x: Int, y: Int, recW: Int, recH: Int): Pair<Float, Float> {
        val size = screenSize()
        var fx = x.toFloat()
        var fy = y.toFloat()

        if (ScriptStore.autoScale && recW > 0 && recH > 0 && size.x > 0 && size.y > 0 &&
            (recW != size.x || recH != size.y)
        ) {
            fx = fx * size.x / recW
            fy = fy * size.y / recH
        }

        val sx = if (ScriptStore.calScaleX == 0f) 1f else ScriptStore.calScaleX
        val sy = if (ScriptStore.calScaleY == 0f) 1f else ScriptStore.calScaleY
        fx = (fx - ScriptStore.calOffsetX) / sx
        fy = (fy - ScriptStore.calOffsetY) / sy

        val maxX = (size.x - 1).coerceAtLeast(0).toFloat()
        val maxY = (size.y - 1).coerceAtLeast(0).toFloat()
        return Pair(fx.coerceIn(0f, maxX), fy.coerceIn(0f, maxY))
    }

    // ------------------------------------------------------------------ 실행 엔진

    fun toggle() {
        if (running) stop() else start()
    }

    fun start() {
        if (running) return
        ScriptStore.ensureLoaded(this)
        snapshot = ScriptStore.actions.filter { it.enabled }.map { it.copyOf() }
        if (snapshot.isEmpty()) {
            toast("실행할 동작이 없습니다")
            return
        }
        running = true
        actionIndex = 0
        repeatIndex = 0
        loopIndex = 0
        notifyState()
        overlay?.onRunStateChanged(true)
        val wait = ScriptStore.startDelay.coerceAtLeast(0L)
        if (wait > 0) toast("${wait}ms 후 시작합니다")
        handler.postDelayed({ step() }, max(100L, wait))
    }

    fun stop() {
        if (!running) {
            overlay?.onRunStateChanged(false)
            return
        }
        running = false
        handler.removeCallbacksAndMessages(null)
        notifyState()
        overlay?.onRunStateChanged(false)
    }

    private fun step() {
        if (!running) return

        if (actionIndex >= snapshot.size) {
            actionIndex = 0
            repeatIndex = 0
            loopIndex++
            val limit = ScriptStore.loopCount
            if (limit > 0 && loopIndex >= limit) {
                stop()
                toast("완료 (${loopIndex}회)")
                return
            }
            handler.postDelayed({ step() }, jitterTime(ScriptStore.loopDelay))
            return
        }

        val action = snapshot[actionIndex]
        dispatch(action) {
            if (!running) return@dispatch
            repeatIndex++
            if (repeatIndex >= max(1, action.repeat)) {
                repeatIndex = 0
                actionIndex++
            }
            handler.postDelayed({ step() }, jitterTime(action.delayAfter))
        }
    }

    private fun dispatch(action: ActionItem, onDone: () -> Unit) {
        val start = mapPoint(action.x1, action.y1, action.recW, action.recH)
        val path = Path()
        path.moveTo(jitterPos(start.first), jitterPos(start.second))
        if (action.type == ActionType.SWIPE) {
            val end = mapPoint(action.x2, action.y2, action.recW, action.recH)
            path.lineTo(jitterPos(end.first), jitterPos(end.second))
        }

        val maxDuration = runCatching { GestureDescription.getMaxGestureDuration() }
            .getOrDefault(60_000L)
        val duration = action.duration.coerceIn(1L, maxDuration)

        strokeGesture(path, duration, onDone)
    }

    /** 보정 등에서 화면 좌표를 그대로(보정 없이) 탭할 때 사용 */
    fun dispatchRawTap(x: Float, y: Float, durationMs: Long = 60L, onDone: () -> Unit = {}) {
        val path = Path()
        path.moveTo(x, y)
        strokeGesture(path, durationMs, onDone)
    }

    /** 보정을 적용해서 특정 목표 지점을 탭 (보정 검증용) */
    fun dispatchCalibratedTap(x: Float, y: Float, onDone: () -> Unit = {}) {
        val p = mapPoint(x.toInt(), y.toInt(), 0, 0)
        dispatchRawTap(p.first, p.second, 60L, onDone)
    }

    private fun strokeGesture(path: Path, duration: Long, onDone: () -> Unit) {
        val gesture = try {
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, duration.coerceAtLeast(1L)))
                .build()
        } catch (t: Throwable) {
            handler.postDelayed(Runnable { onDone() }, 50L)
            return
        }

        val accepted = try {
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    onDone()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    onDone()
                }
            }, null)
        } catch (t: Throwable) {
            false
        }

        if (!accepted) handler.postDelayed(Runnable { onDone() }, duration + 50L)
    }

    // ------------------------------------------------------------------ 유틸

    private fun jitterPos(v: Float): Float {
        val j = ScriptStore.jitterPx
        if (j <= 0) return v
        return (v + Random.nextInt(-j, j + 1)).coerceAtLeast(0f)
    }

    private fun jitterTime(v: Long): Long {
        val j = ScriptStore.jitterMs
        val base = if (j <= 0L) v else v + Random.nextLong(-j, j + 1)
        return base.coerceAtLeast(1L)
    }

    fun toast(msg: String) {
        handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }
}
