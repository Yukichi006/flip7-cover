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

/**
 * 秒まで出すデジタル時計。光の時計と並べて使う。
 * 光の時計の針と同じ色にそろえ、時はオレンジ、分は白、秒は黄色で描く。
 */
class SecondsClockView(context: Context) : View(context) {
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textAlign = Paint.Align.LEFT
        // 数字の幅で文字が揺れないよう、等幅の数字で描く
        fontFeatureSettings = "tnum"
    }
    private val hh = SimpleDateFormat("HH", Locale.US)
    private val mm = SimpleDateFormat(":mm", Locale.US)
    private val ss = SimpleDateFormat(":ss", Locale.US)

    override fun onDraw(canvas: Canvas) {
        val now = Calendar.getInstance().time
        text.textSize = height * 0.8f
        val parts = listOf(hh.format(now) to Gear.ORANGE, mm.format(now) to Gear.ON, ss.format(now) to Gear.YELLOW)
        var x = (width - parts.sumOf { text.measureText(it.first).toDouble() }.toFloat()) / 2
        val baseline = height / 2f - (text.ascent() + text.descent()) / 2
        for ((part, color) in parts) {
            text.color = color
            canvas.drawText(part, x, baseline, text)
            x += text.measureText(part)
        }
        postInvalidateDelayed(1000 - System.currentTimeMillis() % 1000)
    }
}
