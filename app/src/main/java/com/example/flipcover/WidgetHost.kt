package com.example.flipcover

import android.annotation.SuppressLint
import android.appwidget.AppWidgetHost
import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

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
 * カバー画面では、触られたら [onTouch]、タップされたら（指をほとんど動かさずに離したら）[onTap] で知らせる。
 * タッチそのものはウィジェットに渡し、ウィジェットのボタン以外の所に触れても後ろ（閉じるための暗い幕）へは通さない。
 */
@SuppressLint("ViewConstructor")
class WidgetFrame(
    context: Context,
    private val editing: Boolean,
    private val onTouch: (() -> Unit)? = null,
    private val onTap: (() -> Unit)? = null,
) : FrameLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (editing) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                onTouch?.invoke()
            }
            MotionEvent.ACTION_UP ->
                if (abs(ev.x - downX) < slop && abs(ev.y - downY) < slop) onTap?.invoke()
        }
        return super.onInterceptTouchEvent(ev)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent) = !editing
}
