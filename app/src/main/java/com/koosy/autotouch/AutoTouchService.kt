package com.koosy.autotouch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
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

    /** 실행 상태가 바뀔 때 UI 에 알린다. */
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

    // ------------------------------------------------------------------ 오버레이 조작

    fun overlayController(): OverlayController? = overlay

    fun showPanel() { overlay?.showPanel() }
    fun hidePanel() { overlay?.hidePanel() }
    fun isPanelShown(): Boolean = overlay?.isPanelShown() == true

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

        // 한 사이클을 다 돌았을 때
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
        val path = Path()
        val sx = jitterPos(action.x1).toFloat()
        val sy = jitterPos(action.y1).toFloat()
        path.moveTo(sx, sy)
        if (action.type == ActionType.SWIPE) {
            path.lineTo(jitterPos(action.x2).toFloat(), jitterPos(action.y2).toFloat())
        }

        val maxDuration = runCatching { GestureDescription.getMaxGestureDuration() }
            .getOrDefault(60_000L)
        val duration = action.duration.coerceIn(1L, maxDuration)

        val gesture = try {
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
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

        // 시스템이 제스처를 거부한 경우(다른 제스처 진행 중 등)에는 조금 뒤 계속 진행
        if (!accepted) handler.postDelayed(Runnable { onDone() }, duration + 50L)
    }

    // ------------------------------------------------------------------ 유틸

    private fun jitterPos(v: Int): Int {
        val j = ScriptStore.jitterPx
        if (j <= 0) return v
        return (v + Random.nextInt(-j, j + 1)).coerceAtLeast(0)
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
