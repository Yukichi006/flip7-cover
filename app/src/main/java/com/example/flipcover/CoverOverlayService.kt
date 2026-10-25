package com.example.flipcover

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * 端末が閉じられてカバー画面だけが点灯したとき、
 * カバー画面上にアクセシビリティオーバーレイとしてアニメーションを表示する。
 * ダブルタップで非表示にでき、次に閉じ直すと再び表示される。
 */
class CoverOverlayService : AccessibilityService(), DisplayManager.DisplayListener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var displayManager: DisplayManager
    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())

    private var overlay: View? = null
    private var overlayWm: WindowManager? = null
    private var dismissedUntilReopen = false
    private var coverDisplayId = Display.INVALID_DISPLAY

    override fun onServiceConnected() {
        prefs = Prefs(this)
        prefs.registerListener(this)
        displayManager = getSystemService(DisplayManager::class.java)
        displayManager.registerDisplayListener(this, handler)
        update()
    }

    override fun onDestroy() {
        if (::displayManager.isInitialized) displayManager.unregisterDisplayListener(this)
        if (::prefs.isInitialized) prefs.unregisterListener(this)
        hide()
        super.onDestroy()
    }

    override fun onDisplayAdded(displayId: Int) = update()
    override fun onDisplayRemoved(displayId: Int) = update()
    override fun onDisplayChanged(displayId: Int) = update()

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        // 設定変更を即座に反映するため作り直す
        hide()
        update()
    }

    private fun update() {
        val cover = findCoverDisplay(displayManager)
        val main = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val folded = cover != null && cover.state == Display.STATE_ON &&
            main?.state != Display.STATE_ON

        if (!folded) {
            dismissedUntilReopen = false
            hide()
            return
        }
        if (prefs.enabled && !dismissedUntilReopen && overlay == null) show(cover!!)
    }

    private fun show(display: Display) {
        val ctx = createWindowContext(display, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
        val wm = ctx.getSystemService(WindowManager::class.java)
        val view = CoverContent.create(ctx, prefs, CoverContent.coverSize(display), ::launch)

        val detector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onDoubleTap(e: MotionEvent): Boolean {
                dismissedUntilReopen = true
                hide()
                return true
            }
        })
        view.setOnTouchListener { _, e -> detector.onTouchEvent(e) }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        ).apply {
            // カメラ穴の部分まで描画する
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        runCatching { wm.addView(view, lp) }.onSuccess {
            overlay = view
            overlayWm = wm
            coverDisplayId = display.displayId
        }
    }

    /** アプリをカバー画面上で起動し、邪魔にならないようオーバーレイを閉じる。 */
    private fun launch(component: ComponentName) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(coverDisplayId)
        runCatching { startActivity(intent, options.toBundle()) }
            .onSuccess {
                dismissedUntilReopen = true
                hide()
            }
            .onFailure { Toast.makeText(this, "起動できませんでした: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    private fun hide() {
        overlay?.let { v -> runCatching { overlayWm?.removeView(v) } }
        overlay = null
        overlayWm = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        /**
         * Galaxy Z Flip のカバー画面は通常 displayId=1 の内蔵ディスプレイとして見える。
         * 見つからない場合は既定以外のディスプレイを探す。
         */
        fun findCoverDisplay(dm: DisplayManager): Display? =
            dm.getDisplay(1) ?: dm.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
    }
}
