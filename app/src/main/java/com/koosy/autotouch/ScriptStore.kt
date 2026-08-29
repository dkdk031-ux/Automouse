package com.koosy.autotouch

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 동작 목록과 전역 설정을 담는 저장소. SharedPreferences 에 JSON 으로 영속화한다.
 * 액티비티와 접근성 서비스가 같은 프로세스에서 이 객체를 공유한다.
 */
object ScriptStore {

    private const val PREF = "autotouch_pref"
    private const val KEY_ACTIONS = "actions"
    private const val KEY_LOOP_COUNT = "loopCount"
    private const val KEY_LOOP_DELAY = "loopDelay"
    private const val KEY_JITTER_PX = "jitterPx"
    private const val KEY_JITTER_MS = "jitterMs"
    private const val KEY_START_DELAY = "startDelay"

    val actions = mutableListOf<ActionItem>()

    /** 0 이면 무한 반복 */
    var loopCount: Int = 0
    var loopDelay: Long = 500L
    var jitterPx: Int = 0
    var jitterMs: Long = 0L
    var startDelay: Long = 1000L

    private var loaded = false

    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    fun notifyChanged() { listeners.toList().forEach { it() } }

    fun ensureLoaded(ctx: Context) {
        if (!loaded) load(ctx)
    }

    fun load(ctx: Context) {
        val p = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        actions.clear()
        runCatching {
            val arr = JSONArray(p.getString(KEY_ACTIONS, "[]"))
            for (i in 0 until arr.length()) {
                actions.add(ActionItem.fromJson(arr.getJSONObject(i)))
            }
        }
        loopCount = p.getInt(KEY_LOOP_COUNT, 0)
        loopDelay = p.getLong(KEY_LOOP_DELAY, 500L)
        jitterPx = p.getInt(KEY_JITTER_PX, 0)
        jitterMs = p.getLong(KEY_JITTER_MS, 0L)
        startDelay = p.getLong(KEY_START_DELAY, 1000L)
        loaded = true
    }

    fun save(ctx: Context) {
        val arr = JSONArray()
        actions.forEach { arr.put(it.toJson()) }
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIONS, arr.toString())
            .putInt(KEY_LOOP_COUNT, loopCount)
            .putLong(KEY_LOOP_DELAY, loopDelay)
            .putInt(KEY_JITTER_PX, jitterPx)
            .putLong(KEY_JITTER_MS, jitterMs)
            .putLong(KEY_START_DELAY, startDelay)
            .apply()
        loaded = true
        notifyChanged()
    }

    fun exportJson(): String {
        val arr = JSONArray()
        actions.forEach { arr.put(it.toJson()) }
        return JSONObject().apply {
            put("loopCount", loopCount)
            put("loopDelay", loopDelay)
            put("jitterPx", jitterPx)
            put("jitterMs", jitterMs)
            put("startDelay", startDelay)
            put("actions", arr)
        }.toString(2)
    }
}
