package com.example.flipcover

import android.content.ComponentName
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Display
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var editor: PreviewEditor
    private lateinit var modeGroup: RadioGroup

    private val pickGif = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { keep(uri); prefs.gifUri = uri; prefs.mode = Mode.GIF }
        refresh()
    }
    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { keep(uri); prefs.videoUri = uri; prefs.mode = Mode.VIDEO }
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        editor = PreviewEditor(findViewById(R.id.preview), prefs) { refresh() }
        findViewById<Button>(R.id.resetLayout).setOnClickListener { prefs.resetLayout(); refresh() }
        modeGroup = findViewById(R.id.modeGroup)

        findViewById<Button>(R.id.openAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<MaterialSwitch>(R.id.enabledSwitch).apply {
            isChecked = prefs.enabled
            setOnCheckedChangeListener { _, v -> prefs.enabled = v }
        }
        findViewById<MaterialSwitch>(R.id.clockSwitch).apply {
            isChecked = prefs.showClock
            setOnCheckedChangeListener { _, v -> prefs.showClock = v; refresh() }
        }
        bindSwitch(R.id.boldMinuteSwitch, prefs.boldMinute) { prefs.boldMinute = it }
        bindSwitch(R.id.minuteTipSwitch, prefs.minuteTip) { prefs.minuteTip = it }
        bindSwitch(R.id.digitalSwitch, prefs.showDigital) { prefs.showDigital = it }
        findViewById<MaterialSwitch>(R.id.backdropSwitch).apply {
            isChecked = prefs.clockBackdrop
            setOnCheckedChangeListener { _, v -> prefs.clockBackdrop = v; refresh() }
        }
        findViewById<Button>(R.id.clockStyle).setOnClickListener { pickClockStyle() }
        findViewById<Button>(R.id.pickApps).setOnClickListener { pickApps() }

        val speedLabel = findViewById<TextView>(R.id.speedLabel)
        findViewById<Slider>(R.id.speedSlider).apply {
            value = prefs.animSpeed.coerceIn(valueFrom, valueTo)
            speedLabel.text = speedText(value)
            setLabelFormatter { speedText(it) }
            addOnChangeListener { _, v, _ -> speedLabel.text = speedText(v) }
            // 動かしている間に何度も作り直さないよう、指を離したときに保存する
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {}
                override fun onStopTrackingTouch(slider: Slider) {
                    prefs.animSpeed = slider.value
                    refresh()
                }
            })
        }

        // ラジオボタンはタップ時に処理する（GIF/動画は毎回ファイル選択）
        findViewById<TextView>(R.id.modeEarth).setOnClickListener { prefs.mode = Mode.EARTH; refresh() }
        findViewById<TextView>(R.id.modeMoon).setOnClickListener { prefs.mode = Mode.MOON; refresh() }
        findViewById<TextView>(R.id.modeDotSeconds).setOnClickListener { prefs.mode = Mode.DOT_SECONDS; refresh() }
        findViewById<TextView>(R.id.modeLevelMeter).setOnClickListener { prefs.mode = Mode.LEVEL_METER; refresh() }
        findViewById<TextView>(R.id.modeDotWave).setOnClickListener { prefs.mode = Mode.DOT_WAVE; refresh() }
        findViewById<TextView>(R.id.modeSequencer).setOnClickListener { prefs.mode = Mode.SEQUENCER; refresh() }
        findViewById<TextView>(R.id.modeAurora).setOnClickListener { prefs.mode = Mode.AURORA; refresh() }
        findViewById<TextView>(R.id.modeParticles).setOnClickListener { prefs.mode = Mode.PARTICLES; refresh() }
        findViewById<TextView>(R.id.modeGif).setOnClickListener { pickGif.launch(arrayOf("image/gif", "image/webp")) }
        findViewById<TextView>(R.id.modeVideo).setOnClickListener { pickVideo.launch(arrayOf("video/*")) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun bindSwitch(id: Int, initial: Boolean, save: (Boolean) -> Unit) {
        findViewById<MaterialSwitch>(id).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, v -> save(v); refresh() }
        }
    }

    private fun speedText(v: Float) = "アニメーションの速さ: ${"%.2f".format(v).trimEnd('0').trimEnd('.')}倍"

    private fun pickClockStyle() {
        val styles = ClockStyle.entries
        MaterialAlertDialogBuilder(this)
            .setTitle("時計のデザイン")
            .setSingleChoiceItems(styles.map { it.label }.toTypedArray(), styles.indexOf(prefs.clockStyle)) { dialog, i ->
                prefs.clockStyle = styles[i]
                refresh()
                dialog.dismiss()
            }
            .show()
    }

    private fun pickApps() {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val candidates = pm.queryIntentActivities(launcher, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) to it.loadLabel(pm).toString() }
            .sortedBy { it.second.lowercase() }
        val current = prefs.apps
        val checked = BooleanArray(candidates.size) { candidates[it].first in current }

        MaterialAlertDialogBuilder(this)
            .setTitle("カバー画面に置くアプリ（最大${MAX_APPS}個）")
            .setMultiChoiceItems(candidates.map { it.second }.toTypedArray(), checked) { _, i, v -> checked[i] = v }
            .setPositiveButton("OK") { _, _ ->
                val selected = candidates.filterIndexed { i, _ -> checked[i] }.map { it.first }
                // 既に選んでいたアプリの並び順を保ち、新しく選んだものは後ろに足す
                val ordered = current.filter { it in selected } + selected.filter { it !in current }
                if (ordered.size > MAX_APPS) {
                    Toast.makeText(this, "最初の${MAX_APPS}個だけ表示します", Toast.LENGTH_SHORT).show()
                }
                prefs.apps = ordered.take(MAX_APPS)
                refresh()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    /** 再起動後もサービスがファイルを読めるよう、URI の権限を永続化する。 */
    private fun keep(uri: Uri) = runCatching {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun refresh() {
        modeGroup.check(
            when (prefs.mode) {
                Mode.AURORA -> R.id.modeAurora
                Mode.PARTICLES -> R.id.modeParticles
                Mode.GIF -> R.id.modeGif
                Mode.VIDEO -> R.id.modeVideo
                Mode.EARTH -> R.id.modeEarth
                Mode.MOON -> R.id.modeMoon
                Mode.DOT_SECONDS -> R.id.modeDotSeconds
                Mode.LEVEL_METER -> R.id.modeLevelMeter
                Mode.DOT_WAVE -> R.id.modeDotWave
                Mode.SEQUENCER -> R.id.modeSequencer
            }
        )
        findViewById<Button>(R.id.clockStyle).text = "時計のデザイン: ${prefs.clockStyle.label}"
        findViewById<TextView>(R.id.mediaLabel).text = when (prefs.mode) {
            Mode.GIF -> prefs.gifUri?.lastPathSegment ?: ""
            Mode.VIDEO -> prefs.videoUri?.lastPathSegment ?: ""
            else -> ""
        }

        findViewById<TextView>(R.id.appsLabel).text = prefs.apps.mapNotNull { cn ->
            runCatching { packageManager.getActivityInfo(cn, 0).loadLabel(packageManager) }.getOrNull()
        }.joinToString("、").ifEmpty { "なし" }

        val cover = CoverOverlayService.findCoverDisplay(getSystemService(DisplayManager::class.java))
        val size = CoverContent.coverSize(cover)
        val cutouts = runCatching { cover?.cutout?.boundingRects }.getOrNull().orEmpty()
        editor.show(CoverContent.create(this, prefs, size), size, cutouts)

        findViewById<TextView>(R.id.serviceStatus).text =
            if (isServiceEnabled()) "✅ サービス有効" else "⚠️ ユーザー補助で「FlipCover」をオンにしてください"
        findViewById<TextView>(R.id.displayInfo).text = displayInfo()
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, CoverOverlayService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }

    private fun displayInfo(): String {
        val dm = getSystemService(DisplayManager::class.java)
        val cover = CoverOverlayService.findCoverDisplay(dm)
        return dm.displays.joinToString("\n") { d ->
            val mode = d.mode
            val state = when (d.state) {
                Display.STATE_ON -> "ON"; Display.STATE_OFF -> "OFF"
                Display.STATE_DOZE, Display.STATE_DOZE_SUSPEND -> "DOZE"; else -> d.state.toString()
            }
            "id=${d.displayId} ${mode.physicalWidth}x${mode.physicalHeight} $state \"${d.name}\"" +
                if (d.displayId == cover?.displayId) "  ← カバー画面" else ""
        }.ifEmpty { "なし" } + (if (cover != null && dm.displays.none { it.displayId == cover.displayId })
            "\nid=${cover.displayId} \"${cover.name}\"  ← カバー画面" else "") +
            "\nカバー画面の実寸: ${CoverContent.coverSize(cover).let { "${it.width}x${it.height}" }}" +
            "\n切り欠き: ${runCatching { cover?.cutout?.boundingRects }.getOrNull()?.joinToString().orEmpty().ifEmpty { "取得できず" }}"
    }

    companion object {
        private const val MAX_APPS = 5
    }
}
