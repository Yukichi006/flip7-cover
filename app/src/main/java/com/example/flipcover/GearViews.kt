package com.example.flipcover

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** 音楽機材風のホーム画面に合わせた配色。 */
object Gear {
    const val BG = 0xFF1C1C1A.toInt()
    const val ON = 0xFFE6E2D9.toInt()
    const val OFF = 0xFF2E2E2B.toInt()
    const val PAD = 0xFF262623.toInt()
    const val ORANGE = 0xFFFF6A1F.toInt()
    const val YELLOW = 0xFFF5D90A.toInt()
    const val GREEN = 0xFF5CB85C.toInt()
    const val GRAY = 0xFF8A8780.toInt()
}

/** 機材風の背景の共通部分。[frame] で毎フレーム描き、指定の間隔で描き直す。 */
abstract class GearView(context: Context, private val intervalMs: Long = 33) : View(context) {
    protected val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val start = System.nanoTime()

    final override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Gear.BG)
        frame(canvas, (System.nanoTime() - start) / 1e9f)
        postInvalidateDelayed(intervalMs)
    }

    protected abstract fun frame(canvas: Canvas, t: Float)
}

/** 60 個のドットが 1 秒ごとに点いていく。今の秒はオレンジ。 */
class DotSecondsView(context: Context) : GearView(context, 50) {
    override fun frame(canvas: Canvas, t: Float) {
        val cols = 6
        val rows = 10
        val gap = min(width / (cols + 1f), height / (rows + 1f))
        val ox = (width - gap * (cols - 1)) / 2
        val oy = (height - gap * (rows - 1)) / 2
        val now = Calendar.getInstance()
        val sec = now.get(Calendar.SECOND)
        val frac = now.get(Calendar.MILLISECOND) / 1000f
        for (i in 0 until 60) {
            val x = ox + (i % cols) * gap
            val y = oy + (i / cols) * gap
            paint.color = when {
                i < sec -> Gear.ON
                i == sec -> Gear.ORANGE
                else -> Gear.OFF
            }
            // 今の秒のドットだけ、拍に合わせて少し脈打つ
            val r = gap * 0.32f * if (i == sec) 1f + 0.12f * (1 - frac) else 1f
            canvas.drawCircle(x, y, r, paint)
        }
    }
}

/** LED のレベルメーター。ピークの位置に緑のマーカーが残り、ゆっくり落ちる。 */
class LevelMeterView(context: Context) : GearView(context) {
    private val cols = 7
    private val segments = 16
    private val peak = FloatArray(cols)
    private var last = 0f
    private val rect = RectF()

    override fun frame(canvas: Canvas, t: Float) {
        val dt = (t - last).coerceIn(0f, 0.1f)
        last = t
        val colW = width / (cols + 1f)
        val segH = height * 0.7f / segments
        val top = (height - segH * segments) / 2
        val barW = colW * 0.55f
        for (c in 0 until cols) {
            // 周期の違う波を重ねて、音楽に合わせて揺れているように見せる
            val p = c * 1.7f
            val level = (0.45f + 0.25f * sin(t * 1.3f + p) + 0.18f * sin(t * 3.1f + p * 2.3f) +
                0.1f * sin(t * 7.7f + p * 0.7f)).coerceIn(0.05f, 1f)
            peak[c] = max(level, peak[c] - dt * 0.18f)
            val lit = (level * segments).toInt()
            val peakSeg = (peak[c] * segments).toInt().coerceAtMost(segments - 1)
            val cx = colW * (c + 1)
            for (s in 0 until segments) {
                val y = top + (segments - 1 - s) * segH
                rect.set(cx - barW / 2, y + segH * 0.12f, cx + barW / 2, y + segH * 0.88f)
                paint.color = when {
                    s == peakSeg && s >= lit -> Gear.GREEN
                    s < lit && s >= segments * 0.8f -> Gear.ORANGE
                    s < lit -> Gear.ON
                    else -> Gear.OFF
                }
                canvas.drawRoundRect(rect, segH * 0.12f, segH * 0.12f, paint)
            }
        }
    }
}

/** ドットの大きさが波紋のように変わる。波の頂上のドットはオレンジ。 */
class DotWaveView(context: Context) : GearView(context) {
    override fun frame(canvas: Canvas, t: Float) {
        val cols = 11
        val gap = width / (cols + 1f)
        val rows = (height / gap).toInt() - 1
        val oy = (height - gap * (rows - 1)) / 2
        // 波紋の中心が画面の中をゆっくり巡る
        val sx = width * (0.5f + 0.3f * sin(t * 0.13f))
        val sy = height * (0.5f + 0.3f * sin(t * 0.17f + 1f))
        for (j in 0 until rows) for (i in 0 until cols) {
            val x = gap * (i + 1)
            val y = oy + j * gap
            val d = hypot(x - sx, y - sy) / gap
            val v = 0.5f + 0.5f * sin(d * 0.9f - t * 2.2f)
            paint.color = if (v > 0.985f) Gear.ORANGE else Gear.ON
            paint.alpha = (90 + 165 * v).toInt()
            canvas.drawCircle(x, y, gap * (0.1f + 0.26f * v), paint)
        }
        paint.alpha = 255
    }
}

/** ステップシーケンサー。再生位置の列で、オンのパッドがオレンジに光る。 */
class SequencerView(context: Context) : GearView(context) {
    private val cols = 8
    private val rows = 6
    private val rnd = Random(11)
    private val steps = Array(rows) { BooleanArray(cols) { rnd.nextFloat() < 0.35f } }
    private val leds = Array(rows) { BooleanArray(cols) { rnd.nextFloat() < 0.12f } }
    private var lastBar = -1
    private val rect = RectF()

    override fun frame(canvas: Canvas, t: Float) {
        // 120 BPM の 8 分音符で 1 列ずつ進む
        val stepLen = 0.25f
        val step = (t / stepLen).toInt()
        val col = step % cols
        val bar = step / cols
        if (bar != lastBar) {
            // 小節が変わるたびにパターンを少しだけ変える
            lastBar = bar
            repeat(3) {
                val r = rnd.nextInt(rows)
                val c = rnd.nextInt(cols)
                steps[r][c] = !steps[r][c]
            }
        }
        val hit = 1f - (t % stepLen) / stepLen

        val cell = min(width / (cols + 0.6f), height / (rows + 0.6f))
        val pad = cell * 0.8f
        val ox = (width - cell * cols) / 2 + (cell - pad) / 2
        val oy = (height - cell * rows) / 2 + (cell - pad) / 2
        for (r in 0 until rows) for (c in 0 until cols) {
            val x = ox + c * cell
            val y = oy + r * cell
            rect.set(x, y, x + pad, y + pad)
            val on = steps[r][c]
            paint.color = when {
                on && c == col -> Gear.ORANGE
                on -> Gear.ON
                c == col -> Gear.OFF
                else -> Gear.PAD
            }
            if (on && c == col) paint.alpha = (170 + 85 * hit).toInt()
            canvas.drawRoundRect(rect, pad * 0.18f, pad * 0.18f, paint)
            paint.alpha = 255
            if (leds[r][c] && !on) {
                paint.color = Gear.YELLOW
                canvas.drawCircle(x + pad * 0.8f, y + pad * 0.2f, pad * 0.06f, paint)
            }
        }
    }
}

/** 太字の時刻に小さな秒と日付を添えたデジタル時計。 */
class BoldClockView(context: Context) : View(context) {
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Gear.ON
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        letterSpacing = -0.04f
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Gear.GRAY
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val hm = SimpleDateFormat("HH:mm", Locale.US)
    private val ss = SimpleDateFormat(":ss", Locale.US)
    private val date = SimpleDateFormat("MM.dd EEE", Locale.US)

    override fun onDraw(canvas: Canvas) {
        val now = Calendar.getInstance().time
        big.textSize = height * 0.55f
        small.textSize = height * 0.2f
        val main = hm.format(now)
        val sec = ss.format(now)
        val total = big.measureText(main) + small.measureText(sec)
        val x = (width - total) / 2
        val baseline = height * 0.6f
        canvas.drawText(main, x, baseline, big)
        canvas.drawText(sec, x + big.measureText(main), baseline, small)
        canvas.drawText(date.format(now).uppercase(Locale.US), x, baseline + height * 0.3f, small)
        postInvalidateDelayed(1000 - System.currentTimeMillis() % 1000)
    }
}
