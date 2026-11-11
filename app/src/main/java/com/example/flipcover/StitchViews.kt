package com.example.flipcover

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * 「ステッチ」の見た目（ホーム画面の FlipHome と同じ、黒い布地に白い糸の縫い目）のための色と道具。
 */
object Stitch {
    const val THREAD = 0xFFEFEBE2.toInt()
    const val THREAD_DIM = 0x8CEFEBE2.toInt()
    const val THREAD_FAINT = 0x30EFEBE2.toInt()
    const val SHADOW = 0xF2000000.toInt()
    const val CLOTH = 0xFF0C0C0D.toInt()
    const val FELT = 0xFF171718.toInt()

    private val tiles = mutableMapOf<Int, Bitmap>()

    /** 綾織りの目と繊維のざらつきを描いたタイルを敷き詰める筆。 */
    fun fabric(base: Int, density: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = BitmapShader(tile(base, density), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    private fun tile(base: Int, density: Float): Bitmap = tiles.getOrPut(base) {
        val period = (2.6f * density).toInt().coerceAtLeast(3)
        val size = period * 40
        val random = Random(base)
        val px = IntArray(size * size)
        val r0 = Color.red(base); val g0 = Color.green(base); val b0 = Color.blue(base)
        val rowShade = IntArray(size) { random.nextInt(-3, 4) }
        for (y in 0 until size) for (x in 0 until size) {
            var d = rowShade[y] + random.nextInt(-6, 7)
            if ((x + y) % period < period * 0.4f) d += 7
            if ((x - y + size) % period < period * 0.3f) d -= 6
            px[y * size + x] = Color.rgb((r0 + d).coerceIn(0, 255), (g0 + d).coerceIn(0, 255), (b0 + d).coerceIn(0, 255))
        }
        Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    /** 影つきの点線（ステッチ）の筆。 */
    fun paint(width: Float, dash: Float, gap: Float, color: Int = THREAD) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
        pathEffect = DashPathEffect(floatArrayOf(dash, gap), 0f)
        setShadowLayer(width * 0.35f, 0f, width * 0.55f, SHADOW)
    }

    /** 糸の色の塗り（糸の玉やサテン刺繍）。 */
    fun fill(color: Int = THREAD, density: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        setShadowLayer(0.8f * density, 0f, 1f * density, SHADOW)
    }
}

/**
 * ステッチの見た目の背景と時計。画面全体に布地を敷き、縁をぐるりと縫う。
 * 時計（[clockR] が 0 より大きいとき）は、フェルトの丸いワッペンに縫い付けた文字盤：
 * - 12 時から今の分までを、内側の輪に沿って縫い進めた糸目で表す（光の時計の扇形の代わり）
 * - 目盛りは、3・6・9・12 時が × のクロスステッチ、ほかは糸の玉
 * - 時針はサテン刺繍の太い針、分針はステッチの針、秒は縫い針が 1 秒ずつ進む
 * - 真ん中は、針を留める小さなボタン
 */
class StitchDialView(
    context: Context,
    private val clockCx: Float,
    private val clockCy: Float,
    private val clockR: Float,
) : View(context) {
    private val d = resources.displayMetrics.density
    private val cloth = Stitch.fabric(Stitch.CLOTH, d)
    private val felt = Stitch.fabric(Stitch.FELT, d)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Stitch.FELT
        setShadowLayer(8f * d, 0f, 3f * d, 0xC0000000.toInt())
    }
    private val edgeSeam = Stitch.paint(1.4f * d, 4.3f * d, 3f * d)
    private val fill = Stitch.fill(density = d)
    private val faint = Stitch.paint(1f * d, 2.5f * d, 2.5f * d, Stitch.THREAD_FAINT)
    private val needle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFD8D4CC.toInt()
        strokeCap = Paint.Cap.ROUND
        setShadowLayer(1f * d, 0f, 1.2f * d, Stitch.SHADOW)
    }
    private val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF050505.toInt() }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPaint(cloth)
        // 画面の縁をぐるりと縫う（カバー画面の角の丸みに合わせる）
        val inset = 5f * d
        val corner = rootWindowInsets?.getRoundedCorner(android.view.RoundedCorner.POSITION_BOTTOM_LEFT)?.radius?.toFloat()
            ?: (min(width, height) * 0.1f)
        val rr = (corner - inset).coerceAtLeast(12f * d)
        canvas.drawRoundRect(inset, inset, width - inset, height - inset, rr, rr, edgeSeam)
        if (clockR <= 0f) return
        drawDial(canvas)
        postInvalidateDelayed(1000 - System.currentTimeMillis() % 1000)
    }

    private fun drawDial(canvas: Canvas) {
        val r = clockR
        val cx = clockCx
        val cy = clockCy
        canvas.drawCircle(cx, cy, r, shadow)
        canvas.drawCircle(cx, cy, r, felt)
        val w = (r * 0.012f).coerceIn(1.1f * d, 2.4f * d)
        canvas.drawCircle(cx, cy, r - r * 0.06f, Stitch.paint(w, w * 3f, w * 2.1f))

        val now = Calendar.getInstance()
        val sec = now.get(Calendar.SECOND)
        val min = now.get(Calendar.MINUTE) + sec / 60f
        val hour = now.get(Calendar.HOUR) + min / 60f

        // 12 時から今の分まで、内側の輪に沿って縫い進めた糸目（まだ縫っていない所は薄い下書き）
        val ring = r * 0.78f
        val oval = RectF(cx - ring, cy - ring, cx + ring, cy + ring)
        canvas.drawArc(oval, -90f, 360f, false, faint)
        canvas.drawArc(oval, -90f, min / 60f * 360f, false, Stitch.paint(w * 1.1f, w * 3.2f, w * 2f))

        // 目盛り
        for (i in 0 until 12) {
            val a = Math.toRadians(i * 30.0 - 90)
            val x = cx + (r * 0.88f) * cos(a).toFloat()
            val y = cy + (r * 0.88f) * sin(a).toFloat()
            if (i % 3 == 0) {
                val k = r * 0.04f
                val p = Paint(fill).apply { style = Paint.Style.STROKE; strokeWidth = w * 1.3f; strokeCap = Paint.Cap.ROUND }
                canvas.drawLine(x - k, y - k, x + k, y + k, p)
                canvas.drawLine(x + k, y - k, x - k, y + k, p)
            } else {
                canvas.drawCircle(x, y, r * 0.018f, fill)
            }
        }

        // 時針：サテン刺繍の太い針
        hand(canvas, cx, cy, hour / 12f * 360f, r * 0.45f, Paint(fill).apply {
            style = Paint.Style.STROKE; strokeWidth = r * 0.055f; strokeCap = Paint.Cap.ROUND
        })
        // 分針：ステッチの針
        hand(canvas, cx, cy, min / 60f * 360f, r * 0.7f, Stitch.paint(w * 1.4f, w * 3f, w * 1.8f))
        // 秒：縫い針（先がとがり、根元に糸を通す穴）
        val a = Math.toRadians(sec * 6.0 - 90)
        val tipX = cx + r * 0.82f * cos(a).toFloat()
        val tipY = cy + r * 0.82f * sin(a).toFloat()
        val tailX = cx - r * 0.18f * cos(a).toFloat()
        val tailY = cy - r * 0.18f * sin(a).toFloat()
        needle.strokeWidth = (r * 0.014f).coerceAtLeast(1.2f * d)
        canvas.drawLine(tailX, tailY, tipX, tipY, needle)
        val eyeX = cx - r * 0.13f * cos(a).toFloat()
        val eyeY = cy - r * 0.13f * sin(a).toFloat()
        canvas.drawCircle(eyeX, eyeY, needle.strokeWidth * 0.9f, hole)
        // 真ん中のボタン
        canvas.drawCircle(cx, cy, r * 0.065f, Paint(shadow))
        canvas.drawCircle(cx, cy, r * 0.065f, felt)
        canvas.drawCircle(cx - r * 0.02f, cy, r * 0.012f, hole)
        canvas.drawCircle(cx + r * 0.02f, cy, r * 0.012f, hole)
    }

    private fun hand(canvas: Canvas, cx: Float, cy: Float, deg: Float, len: Float, paint: Paint) {
        val a = Math.toRadians(deg - 90.0)
        canvas.drawLine(cx, cy, cx + len * cos(a).toFloat(), cy + len * sin(a).toFloat(), paint)
    }
}

/** 秒まで出すデジタル時計の、ステッチ版。数字の輪郭を縫い、秒は少し控えめな糸で縫う。 */
class StitchSecondsClockView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        fontFeatureSettings = "tnum"
    }
    private val path = Path()
    private val bounds = Rect()

    override fun onDraw(canvas: Canvas) {
        val now = Calendar.getInstance()
        val main = "%02d:%02d".format(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
        val sec = ":%02d".format(now.get(Calendar.SECOND))
        text.textSize = 100f
        text.getTextBounds("00:00:00", 0, 8, bounds)
        text.textSize = 100f * min(width * 0.94f / bounds.width(), height * 0.8f / bounds.height())
        val w = (text.textSize * 0.035f).coerceIn(1f * d, 2.2f * d)
        val total = text.measureText(main + sec)
        var x = (width - total) / 2f
        val baseline = height / 2f - (text.ascent() + text.descent()) / 2
        for ((part, color) in listOf(main to Stitch.THREAD, sec to Stitch.THREAD_DIM)) {
            path.reset()
            text.getTextPath(part, 0, part.length, x, baseline, path)
            canvas.drawPath(path, Stitch.paint(w, w * 3f, w * 1.9f, color))
            x += text.measureText(part)
        }
        postInvalidateDelayed(1000 - System.currentTimeMillis() % 1000)
    }
}

/**
 * ステッチの見た目の部品を描く道具：巾着（アプリのボタン）、ポケット（ウィジェットのボタン）、ワッペンの土台。
 */
object StitchShapes {
    /** フェルトのワッペン（角丸）に、縁から少し内側のステッチ。 */
    fun patch(canvas: Canvas, view: View, rect: RectF, radius: Float) {
        val d = view.resources.displayMetrics.density
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Stitch.FELT; setShadowLayer(6f * d, 0f, 2.5f * d, 0xC0000000.toInt()) }
        canvas.drawRoundRect(rect, radius, radius, shadow)
        canvas.drawRoundRect(rect, radius, radius, Stitch.fabric(Stitch.FELT, d))
        val inner = RectF(rect).apply { inset(rect.width() * 0.07f, rect.height() * 0.07f) }
        val w = (rect.width() * 0.012f).coerceIn(0.9f * d, 1.4f * d)
        canvas.drawRoundRect(inner, radius * 0.7f, radius * 0.7f, Stitch.paint(w, w * 3f, w * 2.1f))
    }

    /** 巾着袋。[openness] が 1 に近いほど紐がゆるみ、口が開く。 */
    fun pouch(canvas: Canvas, view: View, openness: Float) {
        val d = view.resources.displayMetrics.density
        val s = min(view.width, view.height) * 0.92f
        val cx = view.width / 2f
        val top = (view.height - s) / 2f
        val felt = Stitch.fabric(Stitch.FELT, d)
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Stitch.FELT; setShadowLayer(6f * d, 0f, 3f * d, 0xC0000000.toInt()) }
        val bodyW = s * 0.86f
        val neckY = top + s * 0.3f
        val neckW = s * (0.2f + 0.26f * openness)
        val bottom = top + s
        val body = Path().apply {
            moveTo(cx - neckW / 2, neckY)
            cubicTo(cx - neckW, neckY + s * 0.08f, cx - bodyW / 2, neckY + s * 0.2f, cx - bodyW / 2, neckY + s * 0.42f)
            cubicTo(cx - bodyW / 2, bottom - s * 0.02f, cx - bodyW * 0.3f, bottom, cx, bottom)
            cubicTo(cx + bodyW * 0.3f, bottom, cx + bodyW / 2, bottom - s * 0.02f, cx + bodyW / 2, neckY + s * 0.42f)
            cubicTo(cx + bodyW / 2, neckY + s * 0.2f, cx + neckW, neckY + s * 0.08f, cx + neckW / 2, neckY)
            close()
        }
        canvas.drawPath(body, shadow)
        canvas.drawPath(body, felt)
        val gather = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x55000000; strokeWidth = s * 0.012f; strokeCap = Paint.Cap.ROUND }
        for (i in listOf(-2, -1, 1, 2)) {
            val x = cx + i * neckW * 0.22f
            canvas.drawPath(Path().apply { moveTo(x, neckY + s * 0.02f); quadTo(x + i * s * 0.04f, neckY + s * 0.12f, x + i * s * 0.07f, neckY + s * (0.24f - 0.14f * openness)) }, gather)
        }
        canvas.save()
        canvas.scale(0.82f, 0.84f, cx, neckY + (bottom - neckY) * 0.56f)
        val w = (s * 0.012f).coerceIn(1f * d, 1.6f * d)
        canvas.drawPath(body, Stitch.paint(w, w * 3f, w * 2.1f))
        canvas.restore()
        val fw = neckW * (1.7f + 0.3f * openness)
        val frillTop = neckY - s * (0.13f + 0.04f * openness)
        val frill = Path().apply {
            moveTo(cx - neckW / 2, neckY)
            lineTo(cx - fw / 2, frillTop + s * 0.02f)
            quadTo(cx - fw / 3, frillTop - s * 0.02f, cx - fw / 6, frillTop + s * 0.015f)
            quadTo(cx, frillTop - s * 0.025f, cx + fw / 6, frillTop + s * 0.015f)
            quadTo(cx + fw / 3, frillTop - s * 0.02f, cx + fw / 2, frillTop + s * 0.02f)
            lineTo(cx + neckW / 2, neckY)
            close()
        }
        canvas.drawPath(frill, shadow)
        canvas.drawPath(frill, felt)
        if (openness > 0.05f) {
            val mouthH = (neckY - frillTop) * 0.55f * openness
            canvas.drawOval(RectF(cx - fw * 0.36f, frillTop + s * 0.02f, cx + fw * 0.36f, frillTop + s * 0.02f + mouthH), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF050505.toInt() })
        }
        val cord = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Stitch.THREAD; strokeWidth = s * 0.02f; strokeCap = Paint.Cap.ROUND
            setShadowLayer(0.8f * d, 0f, 1f * d, Stitch.SHADOW)
        }
        val bead = Stitch.fill(density = d)
        canvas.drawLine(cx - neckW / 2 - s * 0.015f, neckY, cx + neckW / 2 + s * 0.015f, neckY, cord)
        val kx = cx + neckW / 2 + s * 0.015f
        val loop = s * (0.07f + 0.05f * openness)
        canvas.drawOval(RectF(kx - s * 0.01f, neckY - loop, kx + loop * 1.4f, neckY + s * 0.01f), cord)
        canvas.drawPath(Path().apply { moveTo(kx, neckY); quadTo(kx + s * 0.05f, neckY + s * 0.08f, kx + s * 0.02f, neckY + s * 0.16f) }, cord)
        canvas.drawPath(Path().apply { moveTo(kx, neckY); quadTo(kx - s * 0.02f, neckY + s * 0.09f, kx - s * 0.04f, neckY + s * 0.13f) }, cord)
        canvas.drawCircle(kx + s * 0.02f, neckY + s * 0.16f, s * 0.026f, bead)
        canvas.drawCircle(kx - s * 0.04f, neckY + s * 0.13f, s * 0.026f, bead)
    }

    /** ジーンズのポケット。開くと中からウィジェットのカードがのぞく。 */
    fun pocket(canvas: Canvas, view: View, openness: Float) {
        val d = view.resources.displayMetrics.density
        val s = min(view.width, view.height) * 0.9f
        val cx = view.width / 2f
        val cy = view.height / 2f
        val r = RectF(cx - s * 0.42f, cy - s * 0.3f, cx + s * 0.42f, cy + s * 0.4f)
        // のぞくカード（開くほど上に出る）
        val card = RectF(r.left + s * 0.14f, r.top - s * 0.22f * openness, r.right - s * 0.14f, r.top + s * 0.3f)
        canvas.drawRoundRect(card, s * 0.05f, s * 0.05f, Stitch.fill(density = d))
        val shape = Path().apply {
            moveTo(r.left, r.top)
            lineTo(r.right, r.top)
            lineTo(r.right, r.bottom - s * 0.16f)
            quadTo(r.right, r.bottom, cx, r.bottom + s * 0.04f)
            quadTo(r.left, r.bottom, r.left, r.bottom - s * 0.16f)
            close()
        }
        canvas.drawPath(shape, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Stitch.FELT; setShadowLayer(6f * d, 0f, 3f * d, 0xC0000000.toInt()) })
        canvas.drawPath(shape, Stitch.fabric(Stitch.FELT, d))
        val w = (s * 0.012f).coerceIn(1f * d, 1.6f * d)
        val seam = Stitch.paint(w, w * 3f, w * 2.1f)
        canvas.drawLine(r.left + s * 0.05f, r.top + s * 0.06f, r.right - s * 0.05f, r.top + s * 0.06f, seam)
        canvas.drawLine(r.left + s * 0.05f, r.top + s * 0.11f, r.right - s * 0.05f, r.top + s * 0.11f, seam)
        canvas.save()
        canvas.scale(0.84f, 0.8f, cx, r.centerY() + s * 0.06f)
        canvas.drawPath(Path().apply {
            moveTo(r.left, r.top + s * 0.2f); lineTo(r.left, r.bottom - s * 0.16f)
            quadTo(r.left, r.bottom, cx, r.bottom + s * 0.04f); quadTo(r.right, r.bottom, r.right, r.bottom - s * 0.16f)
            lineTo(r.right, r.top + s * 0.2f)
        }, seam)
        canvas.restore()
    }
}

/** ステッチの見た目のアプリアイコン：フェルトのワッペンに、刺繍のアイコン（アプリのアイコンをなぞったものか、ジャンルのもの）と、その下に小さくアプリ名。 */
class StitchAppIcon(
    context: Context,
    private val genre: Genre,
    private val label: String,
    /** あれば、ジャンルのアイコンの代わりに、このアプリのアイコンをなぞった刺繍を縫う（できるまではジャンルのアイコン）。 */
    private val traced: IconApp? = null,
) : View(context) {
    private val rect = RectF()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Stitch.THREAD
        textAlign = Paint.Align.CENTER
        setShadowLayer(0.6f * resources.displayMetrics.density, 0f, 1f * resources.displayMetrics.density, Stitch.SHADOW)
    }

    override fun onDraw(canvas: Canvas) {
        val m = width * 0.05f
        rect.set(m, m, width - m, height - m)
        StitchShapes.patch(canvas, this, rect, width * 0.2f)
        // ワッペンの縁のステッチにかからないよう、内側にアイコンと名前を収める
        text.textSize = width * 0.13f
        val inner = width * 0.17f
        val labelH = text.textSize * 1.25f
        val s = (min(width - inner * 2, height - inner * 2 - labelH)).toInt().coerceAtLeast(8)
        val top = (height - s - labelH) / 2f
        val icon = traced?.let { app ->
            StitchIcons.cached(app, s) ?: run {
                StitchIcons.load(context, app, s) { invalidate() }
                null
            }
        } ?: GenreIcons.bitmap(genre, s)
        canvas.drawBitmap(icon, (width - s) / 2f, top, null)
        // 長い名前は、少しだけ文字を小さくし、それでも入らなければ末尾を「…」にする
        val avail = width - inner * 1.4f
        val base = text.textSize
        val baseline = top + s + base * 1.05f
        if (text.measureText(label) > avail) text.textSize = maxOf(base * 0.78f, base * avail / text.measureText(label))
        var t = label
        if (text.measureText(t) > avail) {
            val n = text.breakText(t, true, avail - text.measureText("…"), null)
            t = t.take(n.coerceAtLeast(1)) + "…"
        }
        canvas.drawText(t, width / 2f, baseline, text)
    }
}
