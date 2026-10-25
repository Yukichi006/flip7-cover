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
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
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

/**
 * 機材風の背景の共通部分。[frame] で描き、指定の間隔で描き直す。
 * 電池持ちのため、どれも動きはゆっくり、描き直しは少なめにしている。
 */
abstract class GearView(
    context: Context,
    private val intervalMs: Long = 100,
    private val speed: Float = 1f,
) : View(context) {
    protected val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val start = System.nanoTime()

    final override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Gear.BG)
        frame(canvas, (System.nanoTime() - start) / 1e9f * speed)
        postInvalidateDelayed(nextDelay())
    }

    protected open fun nextDelay(): Long = intervalMs

    protected abstract fun frame(canvas: Canvas, t: Float)
}

/** 60 個のドットが 1 秒ごとに点いていく。今の秒はオレンジ。 */
class DotSecondsView(context: Context) : GearView(context, 1000) {
    // 秒の変わり目に合わせて 1 秒に 1 回だけ描き直す
    override fun nextDelay() = 1000 - System.currentTimeMillis() % 1000 + 5

    override fun frame(canvas: Canvas, t: Float) {
        val cols = 6
        val rows = 10
        val gap = min(width / (cols + 1f), height / (rows + 1f))
        val ox = (width - gap * (cols - 1)) / 2
        val oy = (height - gap * (rows - 1)) / 2
        val now = Calendar.getInstance()
        val sec = now.get(Calendar.SECOND)
        for (i in 0 until 60) {
            val x = ox + (i % cols) * gap
            val y = oy + (i / cols) * gap
            paint.color = when {
                i < sec -> Gear.ON
                i == sec -> Gear.ORANGE
                else -> Gear.OFF
            }
            canvas.drawCircle(x, y, gap * 0.32f, paint)
        }
    }
}

/** LED のレベルメーター。ピークの位置に緑のマーカーが残り、ゆっくり落ちる。 */
class LevelMeterView(context: Context, speed: Float = 1f) : GearView(context, speed = speed) {
    private val cols = 7
    private val segments = 16
    private val peak = FloatArray(cols)
    private var last = 0f
    private val rect = RectF()

    override fun frame(canvas: Canvas, t: Float) {
        val dt = (t - last).coerceIn(0f, 0.5f)
        last = t
        val colW = width / (cols + 1f)
        val segH = height * 0.7f / segments
        val top = (height - segH * segments) / 2
        val barW = colW * 0.55f
        for (c in 0 until cols) {
            // 周期の違う波を重ねて、音楽に合わせて揺れているように見せる
            val p = c * 1.7f
            val level = (0.45f + 0.28f * sin(t * 0.25f + p) + 0.15f * sin(t * 0.6f + p * 2.3f) +
                0.05f * sin(t * 1.3f + p * 0.7f)).coerceIn(0.05f, 1f)
            peak[c] = max(level, peak[c] - dt * 0.05f)
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
class DotWaveView(context: Context, speed: Float = 1f) : GearView(context, speed = speed) {
    override fun frame(canvas: Canvas, t: Float) {
        val cols = 11
        val gap = width / (cols + 1f)
        val rows = (height / gap).toInt() - 1
        val oy = (height - gap * (rows - 1)) / 2
        // 波紋の中心が画面の中をゆっくり巡る
        val sx = width * (0.5f + 0.3f * sin(t * 0.04f))
        val sy = height * (0.5f + 0.3f * sin(t * 0.05f + 1f))
        for (j in 0 until rows) for (i in 0 until cols) {
            val x = gap * (i + 1)
            val y = oy + j * gap
            val d = hypot(x - sx, y - sy) / gap
            val v = 0.5f + 0.5f * sin(d * 0.6f - t * 0.5f)
            paint.color = Gear.ON
            paint.alpha = (110 + 110 * v).toInt()
            canvas.drawCircle(x, y, gap * (0.16f + 0.16f * v), paint)
        }
        // 差し色は波の中心に近いドット 1 つだけ
        val ci = ((sx / gap).toInt() - 1).coerceIn(0, cols - 1)
        val cj = ((sy - oy) / gap).toInt().coerceIn(0, maxOf(rows - 1, 0))
        paint.color = Gear.ORANGE
        paint.alpha = 255
        canvas.drawCircle(gap * (ci + 1), oy + cj * gap, gap * 0.3f, paint)
    }
}

/**
 * ドットウェーブの中に、ドットだけでアナログ時計を浮かび上がらせる背景。
 * 時計の形は [variant] で選ぶ。どれも時針は白、分針は黄、秒はオレンジのドットで表す。
 *
 * @param clockCx 時計の中心（px）
 * @param clockR 時計の半径（px）。0 なら針を描かず、ただのドットウェーブになる
 */
class DotClockView(
    context: Context,
    private val clockCx: Float,
    private val clockCy: Float,
    private val clockR: Float,
    private val variant: Variant = Variant.MATRIX,
    speed: Float = 1f,
    private val boldMinute: Boolean = false,
    private val minuteTip: Boolean = false,
) : GearView(context, intervalMs = 125, speed = speed) {

    enum class Variant {
        /** 針の上のドットが膨らむ。目盛りの位置のドットが明るい。 */
        MATRIX,

        /** 外から秒・分の輪。経過した分だけ点き、今の位置はオレンジ。中に針。 */
        RING,

        /** 針が根元ほど太いドットの列になり、秒はオレンジのドットが尾を引いて回る。 */
        HANDS,
    }

    private val cols = 19

    override fun frame(canvas: Canvas, t: Float) {
        val gap = width / (cols + 1f)
        val rows = (height / gap).toInt() - 1
        val oy = (height - gap * (rows - 1)) / 2
        val sx = width * (0.5f + 0.3f * sin(t * 0.04f))
        val sy = height * (0.5f + 0.3f * sin(t * 0.05f + 1f))

        val now = Calendar.getInstance()
        val sec = now.get(Calendar.SECOND) + now.get(Calendar.MILLISECOND) / 1000f
        val minute = now.get(Calendar.MINUTE) + sec / 60f
        val hour = now.get(Calendar.HOUR) + minute / 60f
        val r = clockR

        val hourLen = if (variant == Variant.RING) 0.4f else 0.5f
        val minLen = when {
            variant == Variant.RING -> if (boldMinute) 0.62f else 0.58f
            boldMinute -> 0.9f
            else -> 0.8f
        }
        val hx = handX(hour * 30f, r * hourLen)
        val hy = handY(hour * 30f, r * hourLen)
        val mx = handX(minute * 6f, r * minLen)
        val my = handY(minute * 6f, r * minLen)

        fun nearest(x: Float, y: Float): Int {
            val i = (x / gap - 1 + 0.5f).toInt().coerceIn(0, cols - 1)
            val j = ((y - oy) / gap + 0.5f).toInt().coerceIn(0, max(rows - 1, 0))
            return j * cols + i
        }
        fun nearestOnClock(deg: Float, dist: Float) = nearest(clockCx + handX(deg, dist), clockCy + handY(deg, dist))

        // 特定の位置に当たるドット（一番近いもの）を先に決めておく。後から書いたものが優先
        val special = HashMap<Int, Special>()
        if (r > 0f) {
            when (variant) {
                Variant.MATRIX -> {
                    for (k in 0 until 12) special[nearestOnClock(k * 30f, r)] =
                        if (k % 3 == 0) Special(Gear.ON, 230f, 0.22f) else Special(Gear.ON, 160f, 0.16f)
                    special[nearestOnClock(sec * 6f, r)] = Special(Gear.ORANGE, 255f, 0.3f)
                }
                Variant.RING -> {
                    special[nearestOnClock(minute * 6f, r * RING_RADII[1])] = Special(Gear.ORANGE, 255f, 0.28f)
                    special[nearestOnClock(sec * 6f, r * RING_RADII[0])] = Special(Gear.ORANGE, 255f, 0.28f)
                }
                Variant.HANDS -> {
                    for (k in 0 until 12) special[nearestOnClock(k * 30f, r * 0.96f)] =
                        if (k % 3 == 0) Special(Gear.ON, 235f, 0.22f) else Special(Gear.GRAY, 200f, 0.14f)
                    // 尾は古い方から書き、今の秒のドットが最後に上書きする
                    for (k in 5 downTo 0) {
                        val fade = 1f - k / 6f
                        special[nearestOnClock(sec * 6f - k * 8f, r * 0.84f)] =
                            Special(Gear.ORANGE, 60f + 195f * fade * fade, 0.14f + 0.16f * fade)
                    }
                }
            }
            if (minuteTip) {
                special[nearest(clockCx + mx, clockCy + my)] = Special(Gear.YELLOW, 255f, 0.36f)
                if (variant != Variant.RING) special[nearestOnClock(minute * 6f, r * 1.12f)] = Special(Gear.YELLOW, 220f, 0.2f)
            }
        }

        val fractions = floatArrayOf(sec / 60f, minute / 60f)
        for (j in 0 until rows) for (i in 0 until cols) {
            val x = gap * (i + 1)
            val y = oy + j * gap
            val idx = j * cols + i
            val v = 0.5f + 0.5f * sin(hypot(x - sx, y - sy) / gap * 0.6f - t * 0.5f)
            // 背景としてのドットは控えめに
            var color = Gear.ON
            var alpha = 60 + 60 * v
            var radius = gap * (0.1f + 0.1f * v)

            if (r > 0f) {
                val px = x - clockCx
                val py = y - clockCy

                if (variant == Variant.RING) {
                    // 輪の上のドットは、経過した範囲だけ明るくする。まだのところは背景のまま
                    val d = hypot(px, py)
                    val angle = ((atan2(px, -py) / (2 * PI)).toFloat() + 1f) % 1f
                    for (k in RING_RADII.indices) {
                        if (kotlin.math.abs(d - r * RING_RADII[k]) < gap * 0.45f && angle <= fractions[k]) {
                            alpha = 190f
                            radius = gap * 0.17f
                        }
                    }
                }

                val hp = projection(px, py, hx, hy)
                val mp = projection(px, py, mx, my)
                val hHit = bump(segmentDistance(px, py, hx, hy, hp), gap * 0.9f)
                val mHit = bump(segmentDistance(px, py, mx, my, mp), gap * (if (boldMinute) 0.8f else 0.55f))
                // HANDS は根元ほど太く、先へ行くほど細くする
                val taper = if (variant == Variant.HANDS) {
                    if (mHit > hHit) 1.15f - 0.55f * mp else 1.15f - 0.55f * hp
                } else 1f
                val k = max(hHit, mHit)
                if (k > 0.05f) {
                    color = if (mHit > hHit) Gear.YELLOW else Gear.ON
                    alpha = max(alpha, 80 + 175 * k)
                    radius = max(radius, gap * (0.12f + 0.28f * k) * taper)
                }
                special[idx]?.let {
                    color = it.color
                    alpha = max(alpha, it.alpha)
                    radius = max(radius, gap * it.size)
                    if (it.color == Gear.ORANGE) { alpha = it.alpha; radius = gap * it.size }
                }
            }
            paint.color = color
            paint.alpha = alpha.toInt().coerceIn(0, 255)
            canvas.drawCircle(x, y, radius, paint)
        }
        paint.alpha = 255
    }

    private class Special(val color: Int, val alpha: Float, val size: Float)

    private fun handX(deg: Float, len: Float) = (sin(Math.toRadians(deg.toDouble())) * len).toFloat()
    private fun handY(deg: Float, len: Float) = (-cos(Math.toRadians(deg.toDouble())) * len).toFloat()

    /** 原点から (tx, ty) への線分上で、点 (x, y) に一番近い位置（0〜1）。 */
    private fun projection(x: Float, y: Float, tx: Float, ty: Float): Float {
        val len2 = tx * tx + ty * ty
        return if (len2 == 0f) 0f else ((x * tx + y * ty) / len2).coerceIn(0f, 1f)
    }

    private fun segmentDistance(x: Float, y: Float, tx: Float, ty: Float, p: Float) =
        hypot(x - tx * p, y - ty * p)

    private fun bump(d: Float, width: Float): Float {
        val q = d / width
        return exp(-q * q)
    }

    private companion object {
        /** RING の秒・分の輪の半径（時計の半径に対する割合）。 */
        val RING_RADII = floatArrayOf(0.95f, 0.72f)
    }
}

/** 背景と一体の時計の、プレビュー上の掴む場所。編集中だけ薄い円を出す。 */
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

/** ステップシーケンサー。再生位置の列で、オンのパッドがオレンジに光る。 */
class SequencerView(context: Context, speed: Float = 1f) : GearView(context, speed = speed) {
    private val cols = 8
    private val rows = 6
    private val rnd = Random(11)
    private val steps = Array(rows) { BooleanArray(cols) { rnd.nextFloat() < 0.35f } }
    private val leds = Array(rows) { BooleanArray(cols) { rnd.nextFloat() < 0.12f } }
    private var lastBar = -1
    private val rect = RectF()

    override fun frame(canvas: Canvas, t: Float) {
        // 1 秒に 1 列ずつ、ゆっくり進む
        val stepLen = 1f
        val step = (t / stepLen).toInt()
        val col = step % cols
        val bar = step / cols
        if (bar != lastBar) {
            // 小節が変わるたびにパターンを少しだけ変える
            lastBar = bar
            repeat(1) {
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
            if (on && c == col) paint.alpha = (200 + 55 * hit).toInt()
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

/** 秒まで出すデジタル時計。アナログ時計と並べて使う。秒はオレンジで目に入りやすくする。 */
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
