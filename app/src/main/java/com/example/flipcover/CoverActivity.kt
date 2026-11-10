package com.example.flipcover

import android.app.Activity
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Toast

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
 * - 端末を開いてメイン画面に移されたら、自分で閉じる
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
    }

    override fun onDestroy() {
        prefs.unregisterListener(this)
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        // 端末を開いたときに、この画面がメイン画面へ移されることがある。カバー画面用の表示なので閉じる
        if (runCatching { display?.displayId }.getOrNull() == Display.DEFAULT_DISPLAY && !folded(this)) {
            finish()
            return
        }
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
                finish()
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
        /** 端末が閉じていて、カバー画面だけが点いているか。 */
        fun folded(context: Context): Boolean {
            val dm = context.getSystemService(DisplayManager::class.java) ?: return false
            val cover = CoverContent.findCoverDisplay(dm) ?: return false
            return cover.state == Display.STATE_ON && dm.getDisplay(Display.DEFAULT_DISPLAY)?.state != Display.STATE_ON
        }

        /** カバー画面でこの画面を開くための呼び出し（カバー画面が見つからなければ、いまの画面で開く）。 */
        fun intent(context: Context): Intent =
            Intent(context, CoverActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun options(context: Context): Bundle? {
            val dm = context.getSystemService(DisplayManager::class.java) ?: return null
            val cover = CoverContent.findCoverDisplay(dm) ?: return null
            return ActivityOptions.makeBasic().setLaunchDisplayId(cover.displayId).toBundle()
        }

        /** カバー画面でこの画面を開く。カバー画面を指定して開けなければ、指定なしで開く。 */
        fun open(context: Context) {
            runCatching { context.startActivity(intent(context), options(context)) }
                .onFailure { runCatching { context.startActivity(intent(context)) } }
        }
    }
}
