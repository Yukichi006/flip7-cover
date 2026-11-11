package com.example.flipcover

import android.app.Activity
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Toast
import java.lang.ref.WeakReference

/**
 * カバー画面の全画面表示（時計・アプリのボタン・ウィジェット・電池・ライト）。
 *
 * 危ない許可（ユーザー補助・他のアプリの上に重ねて表示・常駐）は使わない、ふつうの画面。
 * FlipCover 自身は裏で動き続けず、次のどれかで開かれる：
 * - Galaxy の「モードとルーチン」の「閉じたら FlipCover を開く」（自動）
 * - カバー画面で FlipCover のアイコンを開く
 *
 * - ロック中でも出る（[setShowWhenLocked]）ので、一度開けば、カバー画面が消えて点き直してもこの画面が出る
 * - アプリのボタンを押すと、そのアプリをカバー画面で開く。アプリを閉じる（戻る）と、この画面に戻る
 * - ダブルタップで閉じる
 * - 端末が開かれたら、自分で閉じる（次に閉じたときに、また新しく出る）
 */
class CoverActivity : Activity(), SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        // ロック中のカバー画面でも時計を見られるようにする（アプリを開くときは、ふつうにロックの解除を求められる）
        setShowWhenLocked(true)
        window.attributes = window.attributes.apply {
            // カメラ穴の部分まで描画する
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        window.setDecorFitsSystemWindows(false)
        build()
        prefs.registerListener(this)
        // 閉じるたびに新しく出すので、前のものが残っていれば片付ける
        current?.get()?.takeIf { it !== this }?.finishAndRemoveTask()
        current = WeakReference(this)
        // 端末が開かれたら（メイン画面が点いたら）自分で閉じる
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
    }

    override fun onDestroy() {
        runCatching { getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener) }
        if (current?.get() === this) current = null
        prefs.unregisterListener(this)
        super.onDestroy()
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) { closeIfOpened() }
    }

    /**
     * 端末が開かれていたら（メイン画面が点いていたら）、この画面を閉じる。閉じたら true。
     * カバー画面用の表示なので、開いたときにメイン画面へ移されて残らないようにする。次に閉じたときに、また新しく出る。
     */
    private fun closeIfOpened(): Boolean {
        if (isFinishing || isDestroyed) return true
        val main = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        if (main?.state != Display.STATE_ON) return false
        CoverLog.add(this, "開かれたので、カバー画面の表示を閉じる：${CoverLog.states(this)}")
        finishAndRemoveTask()
        return true
    }

    override fun onStart() {
        super.onStart()
        CoverLog.add(this, "カバー画面の表示が出た：id=${runCatching { display?.displayId }.getOrNull()}、${CoverLog.states(this)}")
        if (closeIfOpened()) return
        // 表示している間だけ、ウィジェットの更新（残高の変化など）を受け取る
        if (prefs.widgetIds.isNotEmpty()) runCatching { WidgetHost.get(this).startListening() }
    }

    override fun onStop() {
        runCatching { WidgetHost.get(this).stopListening() }
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 上下のバーを隠して、カバー画面いっぱいに出す
        if (hasFocus) window.decorView.windowInsetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** 設定画面で変えたら、すぐに作り直す。 */
    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) = build()

    private fun build() {
        val view = CoverContent.create(this, prefs, CoverContent.coverSize(display), ::launch)
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onDoubleTap(e: MotionEvent): Boolean {
                finishAndRemoveTask()
                return true
            }
        })
        view.setOnTouchListener { _, e -> detector.onTouchEvent(e) }
        setContentView(view)
    }

    /** アプリを、この画面と同じ画面（カバー画面）で開く。閉じれば、この画面に戻る。 */
    private fun launch(component: ComponentName) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val options = ActivityOptions.makeBasic().apply { display?.let { setLaunchDisplayId(it.displayId) } }
        runCatching { startActivity(intent, options.toBundle()) }
            .onFailure { Toast.makeText(this, "起動できませんでした: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    companion object {
        /**
         * 端末が閉じているか。メイン画面が点いておらず、カバー画面が消えていなければ（点いているか、うっすら表示なら）、
         * 閉じているとみなす。開いたまま画面を消したときは、カバー画面も消えているので、閉じているとはみなさない。
         */
        fun folded(context: Context): Boolean {
            val dm = context.getSystemService(DisplayManager::class.java) ?: return false
            val cover = CoverContent.findCoverDisplay(dm) ?: return false
            return cover.state != Display.STATE_OFF && dm.getDisplay(Display.DEFAULT_DISPLAY)?.state != Display.STATE_ON
        }

        /** カバー画面でこの画面を開くための呼び出し（カバー画面が見つからなければ、いまの画面で開く）。 */
        /** いま出ているカバー画面の表示（新しく出すときに、前のものを片付けるため）。 */
        private var current: WeakReference<CoverActivity>? = null

        // 「別のまとまり（タスク）として必ず新しく作る」という指定を付ける。これがないと、設定画面と同じまとまりに
        // 入れられてしまい、カバー画面を指定しても、設定画面のあるメイン画面のほうに出てしまう
        fun intent(context: Context): Intent =
            Intent(context, CoverActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)

        fun options(context: Context): Bundle? {
            val dm = context.getSystemService(DisplayManager::class.java) ?: return null
            val cover = CoverContent.findCoverDisplay(dm) ?: return null
            return ActivityOptions.makeBasic().setLaunchDisplayId(cover.displayId).toBundle()
        }

        /** カバー画面でこの画面を開く。カバー画面を指定して開けなければ、指定なしで開く。 */
        fun open(context: Context) {
            runCatching { context.startActivity(intent(context), options(context)) }
                .onFailure {
                    CoverLog.add(context, "カバー画面を指定して開けなかった：${it.message}")
                    runCatching { context.startActivity(intent(context)) }
                }
        }
    }
}
