package com.example.flipcover

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** 音楽機材風のホーム画面に合わせた配色。 */
object Gear {
    const val BG = 0xFF1C1C1A.toInt()
    const val ON = 0xFFE6E2D9.toInt()
    const val OFF = 0xFF2E2E2B.toInt()
    const val PAD = 0xFF262623.toInt()
    const val ORANGE = 0xFFFF6A1A.toInt()
    const val YELLOW = 0xFFF5D90A.toInt()
    const val GREEN = 0xFF5CB85C.toInt()
    const val GRAY = 0xFF8A8780.toInt()
}

/** 光の時計の、プレビュー上の掴む場所。編集中だけ薄い円を出す。 */
class ClockHandle(context: Context, private val visible: Boolean) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x66FFFFFF
    }

    override fun onDraw(canvas: Canvas) {
        if (!visible) return
        paint.strokeWidth = width * 0.008f
        canvas.drawCircle(width / 2f, height / 2f, width / 2f - paint.strokeWidth, paint)
    }
}

/** 秒まで出すデジタル時計。光の時計と並べて使う。秒はオレンジで目に入りやすくする。 */
class SecondsClockView(context: Context) : View(context) {
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }
    private val hm = SimpleDateFormat("HH:mm", Locale.US)
    private val ss = SimpleDateFormat(":ss", Locale.US)

    override fun onDraw(canvas: Canvas) {
        val now = Calendar.getInstance().time
        text.textSize = height * 0.8f
        val main = hm.format(now)
        val sec = ss.format(now)
        // 数字の幅で文字が揺れないよう、等幅の数字で描く
        text.fontFeatureSettings = "tnum"
        val total = text.measureText(main) + text.measureText(sec)
        val x = (width - total) / 2
        val baseline = height / 2f - (text.ascent() + text.descent()) / 2
        text.color = Gear.ON
        canvas.drawText(main, x, baseline, text)
        text.color = Gear.ORANGE
        canvas.drawText(sec, x + text.measureText(main), baseline, text)
        postInvalidateDelayed(1000 - System.currentTimeMillis() % 1000)
    }
}
