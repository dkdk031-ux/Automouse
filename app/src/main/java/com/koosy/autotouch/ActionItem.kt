package com.koosy.autotouch

import org.json.JSONObject
import kotlin.math.roundToInt

enum class ActionType { TAP, SWIPE }

/**
 * 하나의 자동 동작.
 *
 * TAP   : (x1, y1) 을 [duration] ms 동안 누른다. duration 을 600 이상으로 주면 롱프레스.
 * SWIPE : (x1, y1) → (x2, y2) 로 [duration] ms 동안 드래그한다.
 *
 * [repeat]     이 동작 자체를 몇 번 반복할지
 * [delayAfter] 이 동작이 끝난 뒤 다음 동작까지 쉬는 시간(ms)
 * [recW]/[recH] 좌표를 기록할 당시의 화면 크기(px). 폴더블처럼 화면이 바뀌는 기기에서
 *               실행 시점 화면 크기에 맞춰 좌표를 비례 보정하는 데 쓴다. 0 이면 미지정.
 */
data class ActionItem(
    var type: ActionType = ActionType.TAP,
    var x1: Int = 0,
    var y1: Int = 0,
    var x2: Int = 0,
    var y2: Int = 0,
    var duration: Long = 60L,
    var delayAfter: Long = 500L,
    var repeat: Int = 1,
    var enabled: Boolean = true,
    var label: String = "",
    var recW: Int = 0,
    var recH: Int = 0
) {

    fun title(): String = when (type) {
        ActionType.TAP -> if (duration >= 600) "길게 누르기 ($x1, $y1)" else "탭 ($x1, $y1)"
        ActionType.SWIPE -> "드래그 ($x1, $y1) → ($x2, $y2)"
    }

    fun subtitle(): String {
        val base = when (type) {
            ActionType.TAP -> "누름 ${duration}ms · 대기 ${delayAfter}ms · ×$repeat"
            ActionType.SWIPE -> "이동 ${duration}ms · 대기 ${delayAfter}ms · ×$repeat"
        }
        return if (label.isBlank()) base else "$label · $base"
    }

    fun shortName(): String = when (type) {
        ActionType.TAP -> if (duration >= 600) "길게 누르기" else "탭"
        ActionType.SWIPE -> "드래그"
    }

    fun copyOf(): ActionItem = copy()

    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type.name)
        put("x1", x1)
        put("y1", y1)
        put("x2", x2)
        put("y2", y2)
        put("duration", duration)
        put("delayAfter", delayAfter)
        put("repeat", repeat)
        put("enabled", enabled)
        put("label", label)
        put("recW", recW)
        put("recH", recH)
    }

    companion object {
        fun fromJson(o: JSONObject): ActionItem = ActionItem(
            type = runCatching { ActionType.valueOf(o.optString("type", "TAP")) }
                .getOrDefault(ActionType.TAP),
            x1 = o.optInt("x1", 0),
            y1 = o.optInt("y1", 0),
            x2 = o.optInt("x2", 0),
            y2 = o.optInt("y2", 0),
            duration = o.optLong("duration", 60L),
            delayAfter = o.optLong("delayAfter", 500L),
            repeat = o.optInt("repeat", 1),
            enabled = o.optBoolean("enabled", true),
            label = o.optString("label", ""),
            recW = o.optInt("recW", 0),
            recH = o.optInt("recH", 0)
        )

        fun tap(x: Float, y: Float, screenW: Int, screenH: Int) = ActionItem(
            type = ActionType.TAP,
            x1 = x.roundToInt(),
            y1 = y.roundToInt(),
            recW = screenW,
            recH = screenH
        )

        fun swipe(
            x1: Float, y1: Float, x2: Float, y2: Float,
            durationMs: Long, screenW: Int, screenH: Int
        ) = ActionItem(
            type = ActionType.SWIPE,
            x1 = x1.roundToInt(),
            y1 = y1.roundToInt(),
            x2 = x2.roundToInt(),
            y2 = y2.roundToInt(),
            duration = durationMs.coerceIn(80L, 10_000L),
            recW = screenW,
            recH = screenH
        )
    }
}
