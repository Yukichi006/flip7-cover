package com.example.flipcover

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.text.TextUtils
import android.text.format.DateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.min

/**
 * 次に鳴るアラーム（刺繍の部品）。ベルの刺繍と、縫った数字の時刻、「明日」「11月7日（土）」などの日付。
 * Android の「次のアラーム」を読むだけなので、新しい権限は使わない。アラームを変えたら、すぐに描き直す。タップで時計アプリのアラーム。
 */
class NextAlarmView(context: Context) : PatchView(context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val digits = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        fontFeatureSettings = "tnum"
    }
    private val sub = context.threadText(12f, color = Thread.DIM).apply { letterSpacing = 0.04f }
    private val none = context.threadText(13f, color = Thread.DIM)
    private val path = Path()
    private val bounds = Rect()

    private val changed = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = invalidate()
    }

    init {
        setOnClickListener {
            runCatching { context.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.registerReceiver(changed, IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED).apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
        })
    }

    override fun onDetachedFromWindow() {
        runCatching { context.unregisterReceiver(changed) }
        super.onDetachedFromWindow()
    }

    override fun drawContent(canvas: Canvas, area: RectF) {
        val next = runCatching { alarms?.nextAlarmClock?.triggerTime }.getOrNull()
        // ベル（左上、部品が低いときは左の真ん中）
        val bell = min(area.height() * 0.42f, area.width() * 0.28f).coerceAtMost(context.dp(40f))
        val wide = area.width() > area.height() * 1.6f
        val bx = area.left
        val by = if (wide) area.centerY() - bell / 2 else area.top
        drawBell(canvas, bx, by, bell, next != null)
        val textLeft = if (wide) bx + bell + context.dp(10f) else area.left
        val textTop = if (wide) area.top else by + bell + context.dp(6f)
        val textW = area.right - textLeft
        val textH = area.bottom - textTop
        if (next == null) {
            val t = TextUtils.ellipsize("アラームなし", none, textW, TextUtils.TruncateAt.END)
            canvas.drawText(t, 0, t.length, textLeft, textTop + textH / 2 + none.textSize * 0.35f, none)
            return
        }
        val pattern = if (DateFormat.is24HourFormat(context)) "H:mm" else "h:mm"
        val time = DateFormat.format(pattern, next).toString()
        // 縫った数字の時刻：幅と高さ（下に日付の行を残す）に入る大きさにする
        val subH = sub.textSize * 1.5f
        digits.textSize = 100f
        digits.getTextBounds("00:00", 0, 5, bounds)
        val scale = min(textW * 0.95f / bounds.width(), (textH - subH) * 0.85f / bounds.height()).coerceAtLeast(0.05f)
        digits.textSize = 100f * scale
        digits.getTextBounds(time, 0, time.length, bounds)
        val baseline = textTop + (textH - subH) / 2 + bounds.height() / 2f
        path.reset()
        digits.getTextPath(time, 0, time.length, textLeft - bounds.left, baseline, path)
        val w = (digits.textSize * 0.035f).coerceIn(context.dp(1f), context.dp(2.2f))
        canvas.drawPath(path, stitchPaint(w, w * 3f, w * 1.9f))
        // 日付（今日・明日、それより先は日付）
        val day = dayLabel(next)
        val t = TextUtils.ellipsize(day, sub, textW, TextUtils.TruncateAt.END)
        canvas.drawText(t, 0, t.length, textLeft, baseline + subH, sub)
    }

    private fun dayLabel(at: Long): String {
        val target = Calendar.getInstance().apply { timeInMillis = at }
        val today = Calendar.getInstance()
        fun dayIndex(c: Calendar) = c.get(Calendar.YEAR) * 400 + c.get(Calendar.DAY_OF_YEAR)
        return when (dayIndex(target) - dayIndex(today)) {
            0 -> "今日"
            1 -> "明日"
            else -> java.text.SimpleDateFormat("M月d日（E）", Locale.JAPAN).format(target.time)
        }
    }

    /** ベルの刺繍（アラームがないときは薄い糸で、斜線を入れる）。 */
    private fun drawBell(canvas: Canvas, x: Float, y: Float, s: Float, on: Boolean) {
        val color = if (on) Thread.COLOR else Thread.DIM
        val p = stitchPaint(s * 0.06f, s * 0.12f, s * 0.08f, color)
        val cx = x + s / 2
        val bell = Path().apply {
            moveTo(cx - s * 0.32f, y + s * 0.72f)
            cubicTo(cx - s * 0.28f, y + s * 0.5f, cx - s * 0.3f, y + s * 0.18f, cx, y + s * 0.16f)
            cubicTo(cx + s * 0.3f, y + s * 0.18f, cx + s * 0.28f, y + s * 0.5f, cx + s * 0.32f, y + s * 0.72f)
            lineTo(cx + s * 0.4f, y + s * 0.8f)
            lineTo(cx - s * 0.4f, y + s * 0.8f)
            close()
        }
        canvas.drawPath(bell, p)
        canvas.drawCircle(cx, y + s * 0.9f, s * 0.07f, p)
        canvas.drawLine(cx, y + s * 0.06f, cx, y + s * 0.16f, p)
        if (!on) canvas.drawLine(x + s * 0.12f, y + s * 0.1f, x + s * 0.88f, y + s * 0.9f, p)
    }
}

