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

    /** オーバーレイを消して見せているアプリ。そこから戻ってきたら、オーバーレイを出し直す。 */
    private var openedApp: String? = null

    /**
     * 閉じているときにカバー画面の奥に出ていた画面（Samsung の待受画面など）のアプリ。
     * 開いたアプリからここへ戻ったら、アプリを閉じたと判断する。
     */
    private val coverHomes = mutableSetOf<String>()

    private var folded = false

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
        folded = cover != null && cover.state == Display.STATE_ON &&
            main?.state != Display.STATE_ON

        if (!folded) {
            dismissedUntilReopen = false
            openedApp = null
            // 起動待ちのまま開いたときは、次に閉じたときの表示を消してしまわないよう取り消す
            launchingPackage = null
            widgetTapped = false
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
        widgetTapped = true
        handler.removeCallbacks(forgetWidgetApp)
        handler.postDelayed(forgetWidgetApp, WIDGET_APP_WAIT_MS)
    }

    /**
     * ウィジェットのタップから待っている途中か。モバイルSuica のように、残高を読むのに
     * 「おサイフケータイ」など別のアプリの画面を開くウィジェットもあるので、その間はどのアプリの画面でも消す。
     */
    private var widgetTapped = false

    private val forgetWidgetApp = Runnable {
        launchingPackage = null
        widgetTapped = false
    }

    private fun hide() {
        if (overlay != null) runCatching { WidgetHost.get(this).stopListening() }
        overlay?.let { v -> runCatching { overlayWm?.removeView(v) } }
        overlay = null
        overlayWm = null
    }

    private val fadeOut = Runnable {
        openedApp = launchingPackage
        launchingPackage = null
        val v = overlay ?: return@Runnable
        v.animate().alpha(0f).setDuration(150).withEndAction { if (overlay === v) hide() }.start()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val shown = event.packageName?.toString() ?: return
        if (!folded || shown == packageName) return

        val opened = openedApp
        if (opened != null) {
            // 開いたアプリから待受画面（ホーム）へ戻ったら、オーバーレイを出し直す。
            // キーボードや、途中で出る別のアプリの画面では戻さない
            if (shown != opened && (shown in coverHomes || shown in homePackages())) {
                openedApp = null
                dismissedUntilReopen = false
                update()
            }
            return
        }
        val pkg = launchingPackage
        if (pkg == null) {
            // アプリを開いていない間にカバー画面に出る画面を、待受画面として覚えておく
            coverHomes += shown
            return
        }
        if (shown == pkg || (widgetTapped && shown !in SYSTEM_PACKAGES)) {
            widgetTapped = false
            // アプリの画面の上に表示が戻ってこないよう、閉じ直すまでは出さない
            dismissedUntilReopen = true
            handler.removeCallbacks(forgetWidgetApp)
            // 最初の画面が描かれるのを少しだけ待ってから消す
            handler.removeCallbacks(fadeOut)
            handler.postDelayed(fadeOut, 120)
        }
    }
    override fun onInterrupt() {}

    /** ホームアプリ（ランチャー）のパッケージ名。 */
    private fun homePackages(): Set<String> =
        packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet()

    companion object {
        /** アプリの画面が出たことが分からなくても、この時間でオーバーレイを消す。 */
        private const val LAUNCH_TIMEOUT_MS = 1500L

        /** 画面の切り替わりを知らせてくるが、アプリの画面ではないもの（通知パネルなど）。 */
        private val SYSTEM_PACKAGES = setOf("com.android.systemui", "android")

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
