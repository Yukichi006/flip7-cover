package com.example.flipcover

import android.content.ComponentName
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.provider.Settings
import android.view.Display
import android.view.View
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var editor: PreviewEditor
    private lateinit var modeGroup: RadioGroup

    /** プレビューで、ボタンを開いたときの様子を見せる（保存しない、画面だけの切り替え）。 */
    private var expandedPreview = false

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
        bindSwitch(R.id.taperSwitch, prefs.taperHands) { prefs.taperHands = it }

        val glowLabel = findViewById<TextView>(R.id.glowLabel)
        findViewById<Slider>(R.id.glowSlider).apply {
            value = prefs.glow.coerceIn(valueFrom, valueTo)
            glowLabel.text = glowText(value)
            setLabelFormatter { glowText(it) }
            addOnChangeListener { _, v, _ -> glowLabel.text = glowText(v) }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {}
                override fun onStopTrackingTouch(slider: Slider) {
                    prefs.glow = slider.value
                    refresh()
                }
            })
        }
        findViewById<MaterialSwitch>(R.id.backdropSwitch).apply {
            isChecked = prefs.clockBackdrop
            setOnCheckedChangeListener { _, v -> prefs.clockBackdrop = v; refresh() }
        }
        findViewById<Button>(R.id.clockStyle).setOnClickListener { pickClockStyle() }
        findViewById<Button>(R.id.pickApps).setOnClickListener { pickApps() }
        bindSwitch(R.id.launcherSwitch, prefs.useLauncher) { prefs.useLauncher = it }
        bindSwitch(R.id.expandedPreviewSwitch, expandedPreview) { expandedPreview = it }
        findViewById<Button>(R.id.launcherStyle).setOnClickListener { pickLauncherStyle() }
        bindSwitch(R.id.torchSwitch, prefs.showTorch) { prefs.showTorch = it }

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

        // ラジオボタンはタップ時に処理する（時計との組み合わせもそろえるため）
        findViewById<TextView>(R.id.modeEarth).setOnClickListener { setMode(Mode.EARTH) }
        findViewById<TextView>(R.id.modeMoon).setOnClickListener { setMode(Mode.MOON) }
        findViewById<TextView>(R.id.modeDotSeconds).setOnClickListener { setMode(Mode.DOT_SECONDS) }
        findViewById<TextView>(R.id.modeLevelMeter).setOnClickListener { setMode(Mode.LEVEL_METER) }
        findViewById<TextView>(R.id.modeDotWave).setOnClickListener { setMode(Mode.DOT_WAVE) }
        findViewById<TextView>(R.id.modeSequencer).setOnClickListener { setMode(Mode.SEQUENCER) }
        findViewById<TextView>(R.id.modeDotClock).setOnClickListener { setMode(Mode.DOT_CLOCK) }
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

    /** 背景を切り替える。背景と一体の時計があるので、時計と背景の組み合わせをそろえる。 */
    private fun setMode(mode: Mode) {
        if (mode == Mode.DOT_CLOCK) {
            useDotClockBackground()
            if (!prefs.clockStyle.integrated) prefs.clockStyle = ClockStyle.DIAL_SECTOR
        } else {
            prefs.mode = mode
            if (prefs.clockStyle.integrated) {
                prefs.clockStyle = ClockStyle.SIMPLE
                toast("この時計は背景と一体のため、時計を「シンプル」に切り替えました")
            }
        }
        refresh()
    }

    private fun useDotClockBackground() {
        prefs.mode = Mode.DOT_CLOCK
        // ドットで針を描くので、小さいと針が数個のドットにしかならない。最初は大きめにする
        if (prefs.clockSize < 0.6f) prefs.clockSize = 0.8f
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun glowText(v: Float) = "光の強さ（扇形・残像）: ${(v * 100).toInt()}%"

    private fun speedText(v: Float) = "アニメーションの速さ: ${"%.2f".format(v).trimEnd('0').trimEnd('.')}倍"

    private fun pickClockStyle() {
        val styles = ClockStyle.entries
        MaterialAlertDialogBuilder(this)
            .setTitle("時計のデザイン")
            .setSingleChoiceItems(styles.map { it.label }.toTypedArray(), styles.indexOf(prefs.clockStyle)) { dialog, i ->
                prefs.clockStyle = styles[i]
                // 背景と一体の時計を選んだら、背景もそろえる
                if (styles[i].integrated && prefs.mode != Mode.DOT_CLOCK) {
                    useDotClockBackground()
                    toast("背景を「時計と一体」に切り替えました")
                } else if (!styles[i].integrated && prefs.mode == Mode.DOT_CLOCK) {
                    prefs.mode = Mode.DOT_WAVE
                    toast("背景を「ドットウェーブ」に切り替えました")
                }
                refresh()
                dialog.dismiss()
            }
            .show()
    }

    private fun pickLauncherStyle() {
        val styles = LauncherStyle.entries
        MaterialAlertDialogBuilder(this)
            .setTitle("ボタンのデザイン")
            .setSingleChoiceItems(styles.map { it.label }.toTypedArray(), styles.indexOf(prefs.launcherStyle)) { dialog, i ->
                prefs.launcherStyle = styles[i]
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

    private fun refresh() {
        modeGroup.check(
            when (prefs.mode) {
                Mode.EARTH -> R.id.modeEarth
                Mode.MOON -> R.id.modeMoon
                Mode.DOT_SECONDS -> R.id.modeDotSeconds
                Mode.LEVEL_METER -> R.id.modeLevelMeter
                Mode.DOT_WAVE -> R.id.modeDotWave
                Mode.SEQUENCER -> R.id.modeSequencer
                Mode.DOT_CLOCK -> R.id.modeDotClock
            }
        )
        findViewById<Button>(R.id.clockStyle).text = "時計のデザイン: ${prefs.clockStyle.label}"
        // 背景と一体の時計だけの設定は、選んでいるときだけ出す。針のドットの設定は光の時計では使わない
        val style = prefs.clockStyle
        findViewById<View>(R.id.dotOptions).visibility =
            if (prefs.showClock && style.integrated) View.VISIBLE else View.GONE
        val dotOnly = if (style.dial != null) View.VISIBLE else View.GONE
        for (id in intArrayOf(R.id.taperSwitch, R.id.boldMinuteSwitch, R.id.minuteTipSwitch)) {
            findViewById<View>(id).visibility = dotOnly
        }

        findViewById<Button>(R.id.launcherStyle).apply {
            text = "ボタンのデザイン: ${prefs.launcherStyle.label}"
            visibility = if (prefs.useLauncher) View.VISIBLE else View.GONE
        }
        findViewById<TextView>(R.id.appsLabel).text = prefs.apps.mapNotNull { cn ->
            runCatching { packageManager.getActivityInfo(cn, 0).loadLabel(packageManager) }.getOrNull()
        }.joinToString("、").ifEmpty { "なし" }

        val cover = CoverOverlayService.findCoverDisplay(getSystemService(DisplayManager::class.java))
        val size = CoverContent.coverSize(cover)
        val cutouts = runCatching { cover?.cutout?.boundingRects }.getOrNull().orEmpty()
        findViewById<View>(R.id.expandedPreviewSwitch).visibility =
            if (prefs.useLauncher && prefs.apps.isNotEmpty()) View.VISIBLE else View.GONE
        val showExpanded = expandedPreview && prefs.useLauncher
        editor.show(CoverContent.create(this, prefs, size, editing = true, expandedPreview = showExpanded), size, cutouts)

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
        private const val MAX_APPS = 16
    }
}
