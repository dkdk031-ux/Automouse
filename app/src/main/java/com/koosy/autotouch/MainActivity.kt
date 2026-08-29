package com.koosy.autotouch

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var list: RecyclerView
    private lateinit var adapter: ActionAdapter
    private lateinit var txtStatus: TextView
    private lateinit var txtEmpty: TextView
    private lateinit var btnPanel: Button

    private lateinit var edLoopCount: EditText
    private lateinit var edLoopDelay: EditText
    private lateinit var edJitterPx: EditText
    private lateinit var edJitterMs: EditText
    private lateinit var edStartDelay: EditText

    private val storeListener: () -> Unit = { runOnUiThread { refresh() } }
    private val runListener: (Boolean) -> Unit = { runOnUiThread { updateStatus() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ScriptStore.ensureLoaded(this)

        txtStatus = findViewById(R.id.txtStatus)
        txtEmpty = findViewById(R.id.txtEmpty)
        btnPanel = findViewById(R.id.btnPanel)
        list = findViewById(R.id.list)

        edLoopCount = findViewById(R.id.edLoopCount)
        edLoopDelay = findViewById(R.id.edLoopDelay)
        edJitterPx = findViewById(R.id.edJitterPx)
        edJitterMs = findViewById(R.id.edJitterMs)
        edStartDelay = findViewById(R.id.edStartDelay)

        adapter = ActionAdapter(
            ScriptStore.actions,
            onChanged = { persist() },
            onEdit = { pos -> showEditDialog(pos) }
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "목록에서 '오토터치'를 켜 주세요", Toast.LENGTH_LONG).show()
        }

        btnPanel.setOnClickListener { togglePanel() }

        findViewById<Button>(R.id.btnAdd).setOnClickListener { showEditDialog(-1) }

        findViewById<Button>(R.id.btnClear).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("동작을 전부 삭제할까요?")
                .setPositiveButton("삭제") { _, _ ->
                    ScriptStore.actions.clear()
                    persist()
                    adapter.notifyDataSetChanged()
                    refresh()
                }
                .setNegativeButton("취소", null)
                .show()
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            val svc = AutoTouchService.instance
            if (svc == null) {
                promptEnableService()
            } else {
                readSettings()
                svc.showPanel()
                svc.start()
                moveTaskToBack(true)
            }
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            AutoTouchService.instance?.stop()
            updateStatus()
        }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = readSettings()
        }
        listOf(edLoopCount, edLoopDelay, edJitterPx, edJitterMs, edStartDelay)
            .forEach { it.addTextChangedListener(watcher) }

        ScriptStore.addListener(storeListener)
    }

    override fun onResume() {
        super.onResume()
        ScriptStore.load(this)
        adapter.notifyDataSetChanged()
        fillSettings()
        AutoTouchService.instance?.addStateListener(runListener)
        refresh()
    }

    override fun onPause() {
        AutoTouchService.instance?.removeStateListener(runListener)
        persist()
        super.onPause()
    }

    override fun onDestroy() {
        ScriptStore.removeListener(storeListener)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 상태 표시

    private fun refresh() {
        // 레이아웃 계산 중 호출되는 것을 피하려고 한 프레임 뒤에 갱신한다.
        list.post { adapter.notifyDataSetChanged() }
        txtEmpty.visibility = if (ScriptStore.actions.isEmpty()) TextView.VISIBLE else TextView.GONE
        findViewById<TextView>(R.id.txtListTitle).text = "동작 목록 (${ScriptStore.actions.size})"
        updateStatus()
        AutoTouchService.instance?.overlayController()?.refreshMarkers()
    }

    private fun updateStatus() {
        val svc = AutoTouchService.instance
        val enabled = AutoTouchService.isEnabled(this)
        txtStatus.text = when {
            svc == null && !enabled -> "① 접근성 서비스가 꺼져 있습니다. 먼저 켜 주세요."
            svc == null -> "접근성 서비스 연결 대기 중… 설정에서 껐다 켜 보세요."
            svc.running -> "실행 중 ●"
            else -> "준비됨 · 플로팅 버튼으로 좌표를 추가하세요"
        }
        btnPanel.text = if (svc?.isPanelShown() == true) "② 플로팅 끄기" else "② 플로팅 버튼"
    }

    private fun togglePanel() {
        val svc = AutoTouchService.instance
        if (svc == null) { promptEnableService(); return }
        if (svc.isPanelShown()) svc.hidePanel() else svc.showPanel()
        updateStatus()
    }

    private fun promptEnableService() {
        AlertDialog.Builder(this)
            .setTitle("접근성 서비스 필요")
            .setMessage("자동 클릭은 접근성 서비스로 동작합니다.\n설정 > 접근성 > 설치된 앱 > 오토터치 를 켜 주세요.")
            .setPositiveButton("설정 열기") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 접근성 오버레이가 막힌 기기용 보조 (보통은 필요 없음) */
    @Suppress("unused")
    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    // ------------------------------------------------------------------ 설정 값

    private fun fillSettings() {
        edLoopCount.setText(ScriptStore.loopCount.toString())
        edLoopDelay.setText(ScriptStore.loopDelay.toString())
        edJitterPx.setText(ScriptStore.jitterPx.toString())
        edJitterMs.setText(ScriptStore.jitterMs.toString())
        edStartDelay.setText(ScriptStore.startDelay.toString())
    }

    private fun readSettings() {
        ScriptStore.loopCount = edLoopCount.int(0)
        ScriptStore.loopDelay = edLoopDelay.long(500L)
        ScriptStore.jitterPx = edJitterPx.int(0)
        ScriptStore.jitterMs = edJitterMs.long(0L)
        ScriptStore.startDelay = edStartDelay.long(1000L)
    }

    private fun persist() {
        readSettings()
        ScriptStore.save(this)
        AutoTouchService.instance?.overlayController()?.refreshMarkers()
    }

    // ------------------------------------------------------------------ 편집 다이얼로그

    private fun showEditDialog(position: Int) {
        val isNew = position < 0
        val src = if (isNew) ActionItem() else ScriptStore.actions[position].copyOf()

        val v = LayoutInflater.from(this).inflate(R.layout.dialog_edit_action, null)
        val rbTap = v.findViewById<RadioButton>(R.id.rbTap)
        val rbSwipe = v.findViewById<RadioButton>(R.id.rbSwipe)
        val rowEnd = v.findViewById<LinearLayout>(R.id.rowEnd)
        val edX1 = v.findViewById<EditText>(R.id.edX1)
        val edY1 = v.findViewById<EditText>(R.id.edY1)
        val edX2 = v.findViewById<EditText>(R.id.edX2)
        val edY2 = v.findViewById<EditText>(R.id.edY2)
        val edDuration = v.findViewById<EditText>(R.id.edDuration)
        val edDelay = v.findViewById<EditText>(R.id.edDelay)
        val edRepeat = v.findViewById<EditText>(R.id.edRepeat)
        val edLabel = v.findViewById<EditText>(R.id.edLabel)
        val lblDuration = v.findViewById<TextView>(R.id.lblDuration)

        fun applyMode(swipe: Boolean) {
            rowEnd.visibility = if (swipe) LinearLayout.VISIBLE else LinearLayout.GONE
            lblDuration.text = if (swipe)
                "드래그에 걸리는 시간 (ms) — 느리게 하려면 500 이상"
            else
                "누르고 있는 시간 (ms) — 길게 누르기는 600 이상"
        }

        rbTap.isChecked = src.type == ActionType.TAP
        rbSwipe.isChecked = src.type == ActionType.SWIPE
        applyMode(src.type == ActionType.SWIPE)
        rbTap.setOnCheckedChangeListener { _, checked -> if (checked) applyMode(false) }
        rbSwipe.setOnCheckedChangeListener { _, checked -> if (checked) applyMode(true) }

        edX1.setText(src.x1.toString())
        edY1.setText(src.y1.toString())
        edX2.setText(src.x2.toString())
        edY2.setText(src.y2.toString())
        edDuration.setText(src.duration.toString())
        edDelay.setText(src.delayAfter.toString())
        edRepeat.setText(src.repeat.toString())
        edLabel.setText(src.label)

        AlertDialog.Builder(this)
            .setTitle(if (isNew) "동작 추가" else "${position + 1}번 동작 편집")
            .setView(v)
            .setPositiveButton("저장") { _, _ ->
                val item = ActionItem(
                    type = if (rbSwipe.isChecked) ActionType.SWIPE else ActionType.TAP,
                    x1 = edX1.int(0),
                    y1 = edY1.int(0),
                    x2 = edX2.int(0),
                    y2 = edY2.int(0),
                    duration = edDuration.long(60L).coerceAtLeast(1L),
                    delayAfter = edDelay.long(500L).coerceAtLeast(0L),
                    repeat = edRepeat.int(1).coerceAtLeast(1),
                    enabled = src.enabled,
                    label = edLabel.text.toString().trim()
                )
                if (isNew) ScriptStore.actions.add(item) else ScriptStore.actions[position] = item
                persist()
                refresh()
            }
            .setNegativeButton("취소", null)
            .show()
    }
}

private fun EditText.int(def: Int): Int = text.toString().trim().toIntOrNull() ?: def
private fun EditText.long(def: Long): Long = text.toString().trim().toLongOrNull() ?: def
