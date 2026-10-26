package com.example.flipcover

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

/**
 * ドットを使わず、淡い光だけで時刻を表す背景一体の時計（集合体が苦手な人にも見やすいように）。
 * 12 時から今の分までの扇形が中心から放射状に光り、離れるほど弱くなって画面の端まで届く。
 * 時は短いオレンジの光の線、分は扇形の先端の白い光の線、秒は残像を引く黄色い光。
 *
 * @param clockR 時計の半径（px）。0 なら何も描かない
 * @param glow 扇形・残像の光の強さ（0〜1）
 */
class LightDialView(
    context: Context,
    private val clockCx: Float,
    private val clockCy: Float,
    private val clockR: Float,
    private val glow: Float = 0.5f,
) : View(context) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sectorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sectorPath = Path()
    private val oval = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // 中心が一番明るく、離れるほど指数的に弱くなる光
        val reach = hypot(w.toFloat(), h.toFloat()) * 1.2f
        val stops = FloatArray(12) { it / 11f }
        val colors = IntArray(stops.size) { i ->
            val a = exp(-(stops[i] * reach) / (clockR.coerceAtLeast(1f) * 0.75f))
            Color.argb((255 * a).toInt(), 236, 232, 224)
        }
        sectorPaint.shader = RadialGradient(clockCx, clockCy, reach, colors, stops, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Gear.BG)
        val r = clockR
        if (r > 0f) {
            val now = Calendar.getInstance()
            val sec = now.get(Calendar.SECOND) + now.get(Calendar.MILLISECOND) / 1000f
            val minute = now.get(Calendar.MINUTE) + sec / 60f
            val hour = now.get(Calendar.HOUR) + minute / 60f

            drawSector(canvas, minute * 6f)
            drawDial(canvas, r)
            drawMinute(canvas, r, minute * 6f)
            drawHour(canvas, r, hour * 30f)
            drawSeconds(canvas, r, sec * 6f)
        }
        // 秒の光がなめらかに動く程度に描き直す
        postInvalidateDelayed(125)
    }

    /**
     * 12 時から [deg] までの扇形を、画面の外まで届く大きさで描く。
     * 少しずつ広げた扇形を薄く重ねて、縁をぼかす。
     */
    private fun drawSector(canvas: Canvas, deg: Float) {
        if (deg < 0.5f) return
        val big = hypot(width.toFloat(), height.toFloat()) * 2f
        oval.set(clockCx - big, clockCy - big, clockCx + big, clockCy + big)
        val layers = 6
        val feather = 0.6f
        val total = (0.3f + 0.4f * glow).coerceIn(0f, 1f)
        sectorPaint.alpha = (255 * total / layers).toInt()
        for (k in 0 until layers) {
            sectorPath.reset()
            sectorPath.moveTo(clockCx, clockCy)
            // 360° 以上を渡すと 0° 扱いになるので、少し手前で止める
            sectorPath.arcTo(oval, -90f - k * feather, (deg + 2 * k * feather).coerceAtMost(359.9f))
            sectorPath.close()
            canvas.drawPath(sectorPath, sectorPaint)
        }
    }

    /** 12・3・6・9 時の位置をかすかな光で示す（丸い枠は描かない）。 */
    private fun drawDial(canvas: Canvas, r: Float) {
        for (k in 0 until 4) {
            line(canvas, k * 90f, r * 0.86f, r * 0.97f, r * 0.06f, Gear.ON, 40)
            line(canvas, k * 90f, r * 0.86f, r * 0.97f, r * 0.03f, Gear.ON, 115)
        }
    }

    private fun drawMinute(canvas: Canvas, r: Float, deg: Float) {
        // 暗い縁で扇形の光から切り離し、その上に白い光の線を重ねる
        line(canvas, deg, 0f, r * 0.93f, r * 0.06f, Gear.BG, 140)
        line(canvas, deg, 0f, r * 0.93f, r * 0.05f, Gear.ON, (255 * (0.1f + 0.25f * glow)).toInt())
        line(canvas, deg, 0f, r * 0.93f, r * 0.013f, Gear.ON, 248)
    }

    private fun drawHour(canvas: Canvas, r: Float, deg: Float) {
        line(canvas, deg, 0f, r * 0.55f, r * 0.12f, Gear.ORANGE, (255 * (0.15f + 0.2f * glow)).toInt())
        line(canvas, deg, 0f, r * 0.55f, r * 0.04f, Gear.ORANGE, 242)
        fill.shader = null
        fill.color = Gear.ORANGE
        fill.alpha = 230
        canvas.drawCircle(clockCx, clockCy, r * 0.05f, fill)
    }

    /** 黄色い秒の光。後ろに薄れていく残像を引き、周りもぼんやり光る。 */
    private fun drawSeconds(canvas: Canvas, r: Float, deg: Float) {
        fill.shader = null
        fill.color = Gear.YELLOW
        val n = 24
        for (k in n downTo 1) {
            val f = 1f - k / (n + 1f)
            fill.alpha = (255 * f * f * (0.5f + 0.4f * glow)).toInt().coerceIn(0, 255)
            canvas.drawCircle(x(deg - k * 1.2f, r), y(deg - k * 1.2f, r), r * 0.03f * (0.4f + 0.6f * f), fill)
        }
        val hx = x(deg, r)
        val hy = y(deg, r)
        val halo = r * 0.1f
        fill.shader = RadialGradient(hx, hy, halo,
            Color.argb((255 * (0.1f + 0.35f * glow)).toInt(), 245, 217, 10), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        fill.alpha = 255
        canvas.drawCircle(hx, hy, halo, fill)
        fill.shader = null
        fill.color = Gear.YELLOW
        canvas.drawCircle(hx, hy, r * 0.028f, fill)
    }

    private fun line(canvas: Canvas, deg: Float, from: Float, to: Float, w: Float, color: Int, alpha: Int) {
        stroke.color = color
        stroke.alpha = alpha.coerceIn(0, 255)
        stroke.strokeWidth = w
        canvas.drawLine(x(deg, from), y(deg, from), x(deg, to), y(deg, to), stroke)
    }

    private fun x(deg: Float, dist: Float) = clockCx + (sin(Math.toRadians(deg.toDouble())) * dist).toFloat()
    private fun y(deg: Float, dist: Float) = clockCy - (cos(Math.toRadians(deg.toDouble())) * dist).toFloat()
}
