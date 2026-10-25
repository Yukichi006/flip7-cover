package com.example.flipcover

import android.content.ComponentName
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Display
import android.widget.Button
import android.widget.FrameLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var preview: FrameLayout
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
        preview = findViewById(R.id.preview)
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

        // ラジオボタンはタップ時に処理する（GIF/動画は毎回ファイル選択）
        findViewById<TextView>(R.id.modeAurora).setOnClickListener { prefs.mode = Mode.AURORA; refresh() }
        findViewById<TextView>(R.id.modeParticles).setOnClickListener { prefs.mode = Mode.PARTICLES; refresh() }
        findViewById<TextView>(R.id.modeGif).setOnClickListener { pickGif.launch(arrayOf("image/gif", "image/webp")) }
        findViewById<TextView>(R.id.modeVideo).setOnClickListener { pickVideo.launch(arrayOf("video/*")) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
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
            }
        )
        findViewById<TextView>(R.id.mediaLabel).text = when (prefs.mode) {
            Mode.GIF -> prefs.gifUri?.lastPathSegment ?: ""
            Mode.VIDEO -> prefs.videoUri?.lastPathSegment ?: ""
            else -> ""
        }

        preview.removeAllViews()
        preview.addView(CoverContent.create(this, prefs), FrameLayout.LayoutParams(-1, -1))

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
        }.ifEmpty { "なし" } + if (cover != null && dm.displays.none { it.displayId == cover.displayId })
            "\nid=${cover.displayId} \"${cover.name}\"  ← カバー画面" else ""
    }
}
