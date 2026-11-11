package com.example.flipcover

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.MotionEvent
import android.view.View
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.min

/** 部品どうしで使い回す、ちょっとした裏方の仕事用のスレッド。 */
val ioThread = Executors.newSingleThreadExecutor()
val mainHandler = Handler(Looper.getMainLooper())

fun Context.hasPermission(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

// ----------------------------------------------------------------------
// 時計
// ----------------------------------------------------------------------

/**
 * 大きな時計。数字の輪郭をステッチで縫い、下に日付、いちばん下に二本の縫い目の区切り線を描く。
 * タップでアラーム（時計アプリ）を開く。
 */
@SuppressLint("ViewConstructor")
class ClockView(context: Context) : View(context) {
    private val digits = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        fontFeatureSettings = "tnum"
        textAlign = Paint.Align.CENTER
    }
    private val date = context.threadText(16f).apply {
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.12f
    }
    private val dateSize = date.textSize
    private val seam = context.stitch(1.0f, Thread.DIM)
    private val path = Path()
    private val bounds = Rect()
    private var stitch = context.stitch(1.5f)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = invalidate()
    }

    init {
        setOnClickListener {
            runCatching {
                context.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val filter = IntentFilter(Intent.ACTION_TIME_TICK).apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        context.registerReceiver(receiver, filter)
    }

    override fun onDetachedFromWindow() {
        runCatching { context.unregisterReceiver(receiver) }
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // 数字が大きいほど糸も太く、縫い目も長くする
        val width = (h * 0.011f).coerceIn(context.dp(1.3f), context.dp(2.4f))
        stitch = stitchPaint(width, width * 3f, width * 1.9f)
    }

    override fun onDraw(canvas: Canvas) {
        val now = Calendar.getInstance()
        val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
        val time = DateFormat.format(pattern, now).toString()

        // 端末の言語に関係なく、曜日は日本語（月・火…）で出す
        val dateText = java.text.SimpleDateFormat("M月d日（E）", Locale.JAPAN).format(now.time)
        val side = context.dp(14f)
        // 低い部品では、日付と下の縫い目を省いて、時計の数字を大きく出す
        val withDate = height >= context.dp(120f)
        date.textSize = dateSize
        if (date.measureText(dateText) > width - side * 2) date.textSize *= (width - side * 2) / date.measureText(dateText)
        val seamY = height - context.dp(10f)
        val dateY = seamY - context.dp(22f)
        val maxH = if (withDate) dateY - date.textSize - context.dp(18f) else height - context.dp(12f)
        // 幅と高さの両方に収まる大きさにする
        digits.textSize = 100f
        digits.getTextBounds("00:00", 0, 5, bounds)
        val scale = min(width * 0.86f / bounds.width(), maxH * 0.92f / bounds.height())
        digits.textSize = 100f * scale
        digits.getTextBounds(time, 0, time.length, bounds)
        val baseline = (maxH + bounds.height()) / 2 + context.dp(6f)
        path.reset()
        digits.getTextPath(time, 0, time.length, width / 2f, baseline, path)
        canvas.drawPath(path, stitch)
        if (!withDate) return

        canvas.drawText(dateText, width / 2f, dateY, date)
        canvas.drawDoubleSeam(side, width - side, seamY, context.dp(5f), seam)
    }
}

// ----------------------------------------------------------------------
// 今日の予定
// ----------------------------------------------------------------------

