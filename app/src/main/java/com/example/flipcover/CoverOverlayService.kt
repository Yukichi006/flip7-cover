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

    /** カバー画面で起動中のアプリ。その画面が出たらオーバーレイを消す。 */
    private var launchingPackage: String? = null

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
            // 起動待ちのまま開いたときは、次に閉じたときの表示を消してしまわないよう取り消す
            launchingPackage = null
            handler.removeCallbacks(fadeOut)
            hide()
            return
        }
        if (prefs.enabled && !dismissedUntilReopen && overlay == null) show(cover!!)
    }

    private fun show(display: Display) {
        val ctx = createWindowContext(display, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
        val wm = ctx.getSystemService(WindowManager::class.java)
        val view = CoverContent.create(ctx, prefs, CoverContent.coverSize(display), ::launch, ::onWidgetTapped)

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
            // 表示している間だけ、ウィジェットの更新（残高の変化など）を受け取る
            if (prefs.widgetIds.isNotEmpty()) runCatching { WidgetHost.get(this).startListening() }
        }
    }

    /**
     * アプリをカバー画面上で起動する。
     * すぐにオーバーレイを消すと、アプリが出るまでの間に元のカバー画面が見えてしまうので、
     * アプリの画面が出たのを確かめてから（出なくても少し待ってから）フェードアウトして消す。
     */
    private fun launch(component: ComponentName) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(coverDisplayId)
        runCatching { startActivity(intent, options.toBundle()) }
            .onSuccess {
                dismissedUntilReopen = true
                launchingPackage = component.packageName
                handler.removeCallbacks(fadeOut)
                handler.postDelayed(fadeOut, LAUNCH_TIMEOUT_MS)
            }
            .onFailure { Toast.makeText(this, "起動できませんでした: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    /**
     * ウィジェットがタップされたとき。モバイルSuica のように、残高を読むために自分のアプリの画面を開く
     * ウィジェットがあるが、この表示はいつも一番上にあるので、そのままでは画面が下に隠れてしまう。
     * そこで、少しの間そのアプリの画面が出るのを待ち、出たら表示を消して見えるようにする。
     * 画面を開かず中身を更新するだけのウィジェットなら、表示はそのまま残す。
     */
    private fun onWidgetTapped(packageName: String) {
        if (launchingPackage != null) return
        launchingPackage = packageName
        handler.removeCallbacks(forgetWidgetApp)
        handler.postDelayed(forgetWidgetApp, WIDGET_APP_WAIT_MS)
    }

    private val forgetWidgetApp = Runnable { launchingPackage = null }

    private fun hide() {
        if (overlay != null) runCatching { WidgetHost.get(this).stopListening() }
        overlay?.let { v -> runCatching { overlayWm?.removeView(v) } }
        overlay = null
        overlayWm = null
    }

    private val fadeOut = Runnable {
        launchingPackage = null
        val v = overlay ?: return@Runnable
        v.animate().alpha(0f).setDuration(150).withEndAction { if (overlay === v) hide() }.start()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = launchingPackage ?: return
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.packageName?.toString() == pkg) {
            // アプリの画面の上に表示が戻ってこないよう、閉じ直すまでは出さない
            dismissedUntilReopen = true
            handler.removeCallbacks(forgetWidgetApp)
            // 最初の画面が描かれるのを少しだけ待ってから消す
            handler.removeCallbacks(fadeOut)
            handler.postDelayed(fadeOut, 120)
        }
    }
    override fun onInterrupt() {}

    companion object {
        /** アプリの画面が出たことが分からなくても、この時間でオーバーレイを消す。 */
        private const val LAUNCH_TIMEOUT_MS = 1500L

        /** ウィジェットをタップしてから、そのアプリの画面が出るのを待つ時間。 */
        private const val WIDGET_APP_WAIT_MS = 4000L

        /**
         * Galaxy Z Flip のカバー画面は通常 displayId=1 の内蔵ディスプレイとして見える。
         * 見つからない場合は既定以外のディスプレイを探す。
         */
        fun findCoverDisplay(dm: DisplayManager): Display? =
            dm.getDisplay(1) ?: dm.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
    }
}
