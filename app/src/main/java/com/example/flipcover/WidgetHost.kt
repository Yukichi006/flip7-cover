package com.example.flipcover

import android.annotation.SuppressLint
import android.appwidget.AppWidgetHost
import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * ほかのアプリのホーム画面用ウィジェット（Suica の残高など）を、カバー画面に表示するための窓口。
 * アプリ画面とサービスは同じプロセスで動くので、1 つを共有する。
 */
object WidgetHost {
    /** このアプリの中でウィジェットの置き場所を区別する番号（何でもよいが変えないこと）。 */
    private const val HOST_ID = 7001

    private var host: AppWidgetHost? = null

    fun get(context: Context): AppWidgetHost =
        host ?: AppWidgetHost(context.applicationContext, HOST_ID).also { host = it }
}

/**
 * ウィジェットを包む枠。プレビューで編集しているときは、ウィジェットのボタンに指を取られず
 * ドラッグ・ピンチで動かせるよう、タッチを受け取らずに外側へ回す。
 */
@SuppressLint("ViewConstructor")
class WidgetFrame(context: Context, private val editing: Boolean) : FrameLayout(context) {
    override fun onInterceptTouchEvent(ev: MotionEvent) = editing || super.onInterceptTouchEvent(ev)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent) = if (editing) false else super.onTouchEvent(event)
}
