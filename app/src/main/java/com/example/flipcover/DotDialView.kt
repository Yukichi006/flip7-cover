package com.example.flipcover

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * 背景のドットと一体になった、文字盤型のアナログ時計。
 * 針は格子に合わせず、針の角度に沿ってドットを等間隔に並べるので、角度が正確に読める。
 * 時針はオレンジ、分針は白、秒は黄色で、秒は残像を引いて光る。
 *
 * @param clockCx 時計の中心（px）
 * @param clockR 時計の半径（px）。0 なら針を描かず、ただのドットの背景になる
 * @param taper 針のドットを先端ほど小さくする
 * @param glow 扇形・残像の光の強さ（0〜1）
 */
class DotDialView(
    context: Context,
    private val clockCx: Float,
    private val clockCy: Float,
    private val clockR: Float,
    private val pattern: Pattern,
    speed: Float = 1f,
    private val taper: Boolean = true,
    private val glow: Float = 0.5f,
    private val boldMinute: Boolean = false,
    private val minuteTip: Boolean = false,
) : GearView(context, intervalMs = 125, speed = speed) {

    enum class Pattern {
        /** 針の通り道の背景ドットを消し、その中にドットの列の針を置く。 */
        CHANNEL,

        /** 時計の中を放射状のドット（60 本 × 等間隔の輪）にし、針はその 1 本が光る。 */
        RADIAL,

        /** 時計の中を暗くし、60 個の目盛りの輪とドットの列の針を置く。 */
        TICKS,

        /** 放射状のドットの、12 時から今の分までの扇形を淡く光らせる。 */
        SECTOR,

        /** 目盛りの輪の上で、分針が薄れていく残像を引いて回る。 */
        RADAR,
    }

    private val polar = pattern == Pattern.RADIAL || pattern == Pattern.SECTOR

    // 扇形の光は分が変わったときだけ作り直す
    private var sectorBitmap: Bitmap? = null
    private var sectorMinute = -1
    private val sectorPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dest = Rect()

    override fun frame(canvas: Canvas, t: Float) {
        val gap = width / (COLS + 1f)
        val rows = (height / gap).toInt() - 1
        val oy = (height - gap * (rows - 1)) / 2
        val sx = width * (0.5f + 0.3f * sin(t * 0.04f))
        val sy = height * (0.5f + 0.3f * sin(t * 0.05f + 1f))

        val now = Calendar.getInstance()
        val sec = now.get(Calendar.SECOND) + now.get(Calendar.MILLISECOND) / 1000f
        val minute = now.get(Calendar.MINUTE) + sec / 60f
        val hour = now.get(Calendar.HOUR) + minute / 60f
        val r = clockR

        // 放射状のドットの上では、針は 60 本の線のどれかに乗る（分は 1 分、時は 12 分きざみ）
        val minuteSpoke = minute.toInt() % 60
        val hourSpoke = (hour * 5).toInt() % 60
        val minuteDeg = if (polar) minuteSpoke * 6f else minute * 6f
        val hourDeg = if (polar) hourSpoke * 6f else hour * 30f
        val hourEnd = r * STEP * HOUR_DOTS
        val minuteEnd = r * STEP * MINUTE_DOTS

        // 背景の四角い格子
        for (j in 0 until rows) for (i in 0 until COLS) {
            val x = gap * (i + 1)
            val y = oy + j * gap
            val d = hypot(x - clockCx, y - clockCy)
            if (r > 0f && polar && d < r * 1.08f) continue
            val v = 0.5f + 0.5f * sin(hypot(x - sx, y - sy) / gap * 0.6f - t * 0.5f)
            var alpha = 60 + 60 * v
            if (r > 0f) when (pattern) {
                Pattern.TICKS, Pattern.RADAR -> if (d < r * 1.06f) alpha *= 0.3f
                Pattern.CHANNEL -> {
                    // 針の通り道を空けて、針と背景を区切る
                    val px = x - clockCx
                    val py = y - clockCy
                    if (segmentDistance(px, py, handX(hourDeg, hourEnd), handY(hourDeg, hourEnd)) < gap * 0.75f ||
                        segmentDistance(px, py, handX(minuteDeg, minuteEnd), handY(minuteDeg, minuteEnd)) < gap * 0.6f
                    ) continue
                    if (d < r * 1.06f) alpha *= 0.6f
                }
                else -> {}
            }
            dot(canvas, x, y, gap * (0.1f + 0.1f * v), Gear.ON, alpha)
        }
        if (r <= 0f) return

        if (pattern == Pattern.SECTOR && glow > 0f && minuteSpoke > 0) drawSectorGlow(canvas, gap, minuteSpoke)

        if (polar) {
            for (k in 0 until RING_COUNT) {
                val fr = STEP * (k + 1)
                // 内側の輪は円周が短いので、背景のドットを間引いて詰まりすぎないようにする
                val n = if (fr < 0.2f) 12 else if (fr < 0.4f) 30 else 60
                for (q in 0 until n) {
                    val deg = q * 360f / n
                    val outer = k == RING_COUNT - 1
                    val tick = outer && q % 5 == 0
                    var alpha = if (tick) 170f else 75f
                    var radius = gap * (if (tick) (if (q % 15 == 0) 0.15f else 0.11f) else 0.07f)
                    if (pattern == Pattern.SECTOR && deg < minuteSpoke * 6f) {
                        alpha = max(alpha, 110f + 90f * glow)
                        radius = max(radius, gap * 0.1f)
                    }
                    dot(canvas, clockCx + handX(deg, r * fr), clockCy + handY(deg, r * fr), radius, Gear.ON, alpha)
                }
            }
        } else if (pattern == Pattern.CHANNEL) {
            for (k in 0 until 12) {
                dot(canvas, clockCx + handX(k * 30f, r), clockCy + handY(k * 30f, r),
                    gap * (if (k % 3 == 0) 0.22f else 0.15f), Gear.ON, 235f)
            }
        } else {
            for (k in 0 until 60) {
                val major = k % 5 == 0
                dot(canvas, clockCx + handX(k * 6f, r), clockCy + handY(k * 6f, r),
                    gap * (if (k % 15 == 0) 0.2f else if (major) 0.15f else 0.06f),
                    if (major) Gear.ON else Gear.GRAY, if (major) 240f else 170f)
            }
        }

        if (pattern == Pattern.RADAR && glow > 0f) {
            // 分針の後ろに、薄れていく残像を並べる
            for (k in 1..15) {
                val fade = (1 - k / 16f).let { it * it } * glow
                for (q in 0 until MINUTE_DOTS) {
                    val dist = r * STEP * (q + 1)
                    dot(canvas, clockCx + handX(minuteDeg - k * 2f, dist), clockCy + handY(minuteDeg - k * 2f, dist),
                        gap * 0.1f, Gear.ON, 170f * fade)
                }
            }
        }

        val minuteScale = if (boldMinute) 1.3f else 1f
        hand(canvas, minuteDeg, MINUTE_DOTS, Gear.ON, gap * 0.22f * minuteScale, gap * 0.1f * minuteScale)
        if (minuteTip) dot(canvas, clockCx + handX(minuteDeg, minuteEnd + r * STEP * 0.6f),
            clockCy + handY(minuteDeg, minuteEnd + r * STEP * 0.6f), gap * 0.14f, Gear.ON, 255f)
        hand(canvas, hourDeg, HOUR_DOTS, Gear.ORANGE, gap * 0.26f, gap * 0.15f)

        drawSeconds(canvas, gap, sec * 6f, when {
            polar -> r * 1.06f
            pattern == Pattern.CHANNEL -> r * 0.93f
            else -> r
        })
        dot(canvas, clockCx, clockCy, gap * 0.22f, Gear.ORANGE, 255f)
    }

    /**
     * 中心から等間隔に [count] 個のドットを並べた針。[taper] なら先端ほど小さくする。
     * 先に背景色の縁を敷いて、扇形の光や背景のドットから針を切り離す。
     */
    private fun hand(canvas: Canvas, deg: Float, count: Int, color: Int, rootRadius: Float, tipRadius: Float) {
        for (pass in 0..1) for (k in 0 until count) {
            val f = k / (count - 1f)
            val radius = if (taper) rootRadius + (tipRadius - rootRadius) * f else rootRadius
            val dist = clockR * STEP * (k + 1)
            val x = clockCx + handX(deg, dist)
            val y = clockCy + handY(deg, dist)
            if (pass == 0) dot(canvas, x, y, radius * 1.55f, Gear.BG, 255f) else dot(canvas, x, y, radius, color, 255f)
        }
    }

    /** 黄色い秒のドット。光の強さに応じて、後ろに残像と周りのぼんやりした光を付ける。 */
    private fun drawSeconds(canvas: Canvas, gap: Float, deg: Float, dist: Float) {
        val trail = (4 + 10 * glow).toInt()
        for (k in trail downTo 1) {
            val fade = 1f - k / (trail + 1f)
            dot(canvas, clockCx + handX(deg - k * 1.5f, dist), clockCy + handY(deg - k * 1.5f, dist),
                gap * 0.16f * (0.5f + 0.5f * fade), Gear.YELLOW, 200f * fade * fade * (0.4f + 0.6f * glow))
        }
        val x = clockCx + handX(deg, dist)
        val y = clockCy + handY(deg, dist)
        if (glow > 0f) {
            dot(canvas, x, y, gap * 0.5f, Gear.YELLOW, 35f * glow)
            dot(canvas, x, y, gap * 0.32f, Gear.YELLOW, 70f * glow)
        }
        dot(canvas, x, y, gap * 0.18f, Gear.YELLOW, 255f)
    }

    /**
     * 12 時から今の分までの扇形に、ぼかした白い光を敷く。
     * 小さな画像にぼかして描き、それを拡大して重ねることで、軽く柔らかい光にする。
     */
    private fun drawSectorGlow(canvas: Canvas, gap: Float, minuteSpoke: Int) {
        val scale = 0.25f
        if (sectorBitmap == null || sectorMinute != minuteSpoke) {
            val bmp = sectorBitmap ?: Bitmap.createBitmap(
                (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            ).also { sectorBitmap = it }
            bmp.eraseColor(0)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Gear.ON
                maskFilter = BlurMaskFilter(gap * scale * 0.6f, BlurMaskFilter.Blur.NORMAL)
            }
            val rr = clockR * 0.99f * scale
            val ox = clockCx * scale
            val oy = clockCy * scale
            c.drawArc(RectF(ox - rr, oy - rr, ox + rr, oy + rr), -90f, minuteSpoke * 6f, true, p)
            sectorMinute = minuteSpoke
        }
        dest.set(0, 0, width, height)
        sectorPaint.alpha = (255 * 0.3f * glow).toInt().coerceIn(0, 255)
        canvas.drawBitmap(sectorBitmap!!, null, dest, sectorPaint)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        sectorBitmap = null
        sectorMinute = -1
    }

    private fun dot(canvas: Canvas, x: Float, y: Float, radius: Float, color: Int, alpha: Float) {
        paint.color = color
        paint.alpha = alpha.toInt().coerceIn(0, 255)
        canvas.drawCircle(x, y, radius, paint)
    }

    private fun handX(deg: Float, len: Float) = (sin(Math.toRadians(deg.toDouble())) * len).toFloat()
    private fun handY(deg: Float, len: Float) = (-cos(Math.toRadians(deg.toDouble())) * len).toFloat()

    private fun segmentDistance(x: Float, y: Float, tx: Float, ty: Float): Float {
        val len2 = tx * tx + ty * ty
        if (len2 == 0f) return hypot(x, y)
        val p = ((x * tx + y * ty) / len2).coerceIn(0f, 1f)
        return hypot(x - tx * p, y - ty * p)
    }

    private companion object {
        const val COLS = 19

        /** 針のドットの間隔と、放射状の輪の間隔（時計の半径に対する割合）。 */
        const val STEP = 0.12f
        const val RING_COUNT = 8
        const val HOUR_DOTS = 4
        const val MINUTE_DOTS = 7
    }
}
