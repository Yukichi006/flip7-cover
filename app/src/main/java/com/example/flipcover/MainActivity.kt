package com.example.flipcover

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * FlipCover のアイコンから開く画面。
 *
 * - 端末が閉じている間に開かれたとき（Galaxy のルーチンが「閉じたら FlipCover を開く」を実行したとき）と、
 *   カバー画面で開かれたときは、すぐにカバー画面の表示（[CoverActivity]）を出して、自分は閉じる
 * - 開いた端末のメイン画面で開かれたときは、使い方（ルーチンの作り方）と、うまく出ないときの記録を見せる
 *
 * 7.0.0 から、時計やアプリの配置は、カバー画面を長押しして、その場で変える（この画面では変えない）。
 */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var log: TextView? = null

    /** メイン画面が消えた・カバー画面が現れた、などの変化があったら、閉じたかを確かめる。 */
    private val displayListener = object : DisplayManager.DisplayListener {
        // カバー画面は、閉じた少しあとに現れることがあるので、現れたときにも確かめる
        override fun onDisplayAdded(displayId: Int) { goCover("カバー画面が現れたとき", always = false) }
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) { goCover("画面の点き方が変わったとき", always = false) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Diagnostics.install(this)
        if (goCover("開かれたとき")) return
        // ルーチンは閉じた瞬間に FlipCover を開くので、そのときはまだ画面が切り替わる途中のことがある。
        // このあと画面の点き方が変わったときと、少し待ったあとにも確かめる
        getSystemService(DisplayManager::class.java)!!.registerDisplayListener(displayListener, handler)
        for (ms in longArrayOf(300, 1000, 2500)) handler.postDelayed({ goCover("${ms}ミリ秒後") }, ms)
        setContentView(buildGuide())
    }

    override fun onResume() {
        super.onResume()
        if (goCover("画面に戻ったとき", always = false)) return
        log?.text = logText()
    }

    /** 案内の画面を開いたまま閉じたあとで、ルーチンが FlipCover を開いたとき。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        goCover("もう一度開かれたとき")
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { getSystemService(DisplayManager::class.java)!!.unregisterDisplayListener(displayListener) }
        super.onDestroy()
    }

    /**
     * カバー画面で開かれているか、端末が閉じていれば、案内の画面をやめて、カバー画面の表示を出す。出したら true。
     * うまく出ないときに調べられるよう、確かめたときの様子を記録に残す（[timing] は、いつ確かめたか。
     * [always] でなければ、出すときだけ残す）。
     */
    private fun goCover(timing: String, always: Boolean = true): Boolean {
        if (isFinishing || isDestroyed) return true
        val id = runCatching { display?.displayId }.getOrNull()
        val go = (id != null && id != Display.DEFAULT_DISPLAY) || CoverActivity.folded(this)
        if (go || always) {
            CoverLog.add(this, "$timing：案内の画面は id=$id、${CoverLog.states(this)} → ${if (go) "カバー画面に出す" else "案内の画面のまま"}")
        }
        if (!go) return false
        CoverActivity.open(this)
        finishAndRemoveTask()
        return true
    }

    private fun logText() = CoverLog.text(this)

    /** 使い方と記録の画面。 */
    private fun buildGuide(): ScrollView {
        val d = resources.displayMetrics.density
        fun px(v: Float) = (v * d).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(22f), px(18f), px(22f), px(28f))
        }
        fun text(value: String, size: Float, color: Int = Thread.COLOR, bold: Boolean = false, top: Float = 0f) = TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(0f, 1.25f)
            column.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(top) })
        }
        fun button(label: String, action: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Cloth.BASE)
            setTypeface(typeface, Typeface.BOLD)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = px(26f).toFloat()
                setColor(Thread.COLOR)
            }
            setOnClickListener { action() }
            column.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(52f)).apply { topMargin = px(14f) })
        }
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()
        text("FlipCover", 26f, bold = true)
        text("カバー画面用の、黒い布地にステッチのホーム。バージョン $version", 13f, Thread.DIM, top = 2f)

        text("閉じたら自動で出す（最初の 1 回だけ）", 17f, bold = true, top = 26f)
        text("Galaxy の「モードとルーチン」に、閉じたら FlipCover を開くルーチンを作ります。\n" +
            "1. 下のボタンで設定を開き、「モードとルーチン」を開く\n" +
            "2. 「ルーチン」→「＋」\n" +
            "3. 条件（もし）：「折りたたみの状態」→「完全に閉じた状態」\n" +
            "4. 実行内容（実行）：「アプリ」→「アプリを起動」→ FlipCover\n" +
            "5. 保存", 14f, top = 8f)
        button("設定を開く") {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            Toast.makeText(this, "設定の「モードとルーチン」を開いてください", Toast.LENGTH_LONG).show()
        }

        text("配置を変える", 17f, bold = true, top = 30f)
        text("カバー画面を長押しすると、その場で変えられます。細かい作業や、ほかのアプリのウィジェットを置くとき（初回は Android の確認が出ます）は、" +
            "下のボタンから、この大きい画面でも同じように編集できます（文字や部品の大きさの見え方は、カバー画面と少し違います）。", 14f, top = 8f)
        button("メイン画面で配置を編集する") { CoverActivity.openOnMain(this) }

        text("カバー画面の使い方", 17f, bold = true, top = 30f)
        text("・左右へスワイプ：ページを移る\n" +
            "・上へスワイプ：アプリ一覧\n" +
            "・どこかを長押し：編集モード。空いたマスをタップして、アプリや部品（時計・電池・ライト・巾着・ほかのアプリのウィジェットなど）を置く。" +
            "ドラッグで移動、右下のつまみで大きさ、× で外す\n" +
            "・編集モードの「…」：ページを足す、列の数、糸の太さなどの設定\n" +
            "・今日の予定は、空いたマス →「部品」→「アプリのウィジェット」で、カレンダーの予定の一覧を置く\n" +
            "・端末を開くと自動で閉じ、次に閉じたときにまた出る", 14f, top = 8f)

        text("閉じても出ないときの記録（新しい順）", 17f, bold = true, top = 30f)
        log = text(logText(), 11.5f, Thread.DIM, top = 8f).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        return ScrollView(this).apply {
            setBackgroundColor(Cloth.BASE)
            addView(column)
            // 上下のバーに重ならないようにする
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
        }
    }
}
