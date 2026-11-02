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
