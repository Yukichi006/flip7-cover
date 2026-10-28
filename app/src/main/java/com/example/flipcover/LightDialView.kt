package com.example.flipcover

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
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
    // ディザをかけて、8bit の色の段差（縞）を細かい粒に散らす
    private val sectorPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private var radial: Shader? = null
    private val sweepMatrix = Matrix().apply { setRotate(-90f, clockCx, clockCy) }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // 中心が一番明るく、離れるほど指数的に弱くなる光。
        // 色の段数を細かく取り、明るさも最初から色に含めて 1 回で塗る（重ね塗りすると段差が目立つ）
        val reach = hypot(w.toFloat(), h.toFloat()) * 1.2f
        val total = (0.3f + 0.4f * glow).coerceIn(0f, 1f)
        val stops = FloatArray(64) { it / 63f }
        val colors = IntArray(stops.size) { i ->
            val a = exp(-(stops[i] * reach) / (clockR.coerceAtLeast(1f) * DECAY))
            Color.argb((255 * total * a).toInt(), 236, 232, 224)
        }
        radial = RadialGradient(clockCx, clockCy, reach, colors, stops, Shader.TileMode.CLAMP)
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
     * 12 時から [deg] までの扇形の光を画面全体に描く。
     * 放射状の光を、縁がなめらかに消える角度のマスクで切り抜いて 1 回で塗る。
     */
    private fun drawSector(canvas: Canvas, deg: Float) {
        val light = radial ?: return
        if (deg < 0.5f) return
        // 扇形の外側へ FEATHER 度かけて消える。残りが狭いときは両側の縁がぶつからないよう縮める
        val f = FEATHER.coerceAtMost((360f - deg) / 2f)
        val mask = SweepGradient(
            clockCx, clockCy,
            intArrayOf(Color.WHITE, Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT, Color.WHITE),
            floatArrayOf(0f, deg / 360f, (deg + f) / 360f, (360f - f) / 360f, 1f),
        )
        // 掃引の始まりを 3 時から 12 時へ回す
        mask.setLocalMatrix(sweepMatrix)
        sectorPaint.shader = ComposeShader(light, mask, PorterDuff.Mode.DST_IN)
        canvas.drawPaint(sectorPaint)
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

    private companion object {
        /** 光が弱まる距離（時計の半径に対する割合）。小さいほど中心の近くで暗くなる。 */
        const val DECAY = 0.4f

        /** 扇形の縁をぼかす角度（度）。 */
        const val FEATHER = 3f
    }

    private fun x(deg: Float, dist: Float) = clockCx + (sin(Math.toRadians(deg.toDouble())) * dist).toFloat()
    private fun y(deg: Float, dist: Float) = clockCy - (cos(Math.toRadians(deg.toDouble())) * dist).toFloat()
}
