package com.example.flipcover

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.View
import androidx.core.graphics.ColorUtils
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
    // 光は縁まで透明に消えるので、つなぎ目に細い筋が出ないよう縁のなめらか処理はかけない
    private val glowPaint = Paint(Paint.DITHER_FLAG)
    private val glowOval = RectF()
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

    /**
     * 12・3・6・9 時の位置を、ふんわりにじむ光の中に細い芯が通った短い線で示す（丸い枠は描かない）。
     * 12 時だけ少し長くして、向きが分かるようにする。
     */
    private fun drawDial(canvas: Canvas, r: Float) {
        for (k in 0 until 4) {
            val from = if (k == 0) r * 0.82f else r * 0.86f
            drawGlow(canvas, k * 90f, from, r * 0.96f, tickGlow)
            line(canvas, k * 90f, from, r * 0.96f, r * 0.012f, Gear.ON, 235)
        }
    }

    private fun drawMinute(canvas: Canvas, r: Float, deg: Float) {
        // 暗い縁で扇形の光から切り離し、その上に光の帯と白い芯を重ねる
        line(canvas, deg, 0f, r * 0.93f, r * 0.06f, Gear.BG, 140)
        drawGlow(canvas, deg, 0f, r * 0.93f, minuteGlow)
        line(canvas, deg, 0f, r * 0.93f, r * 0.014f, Gear.ON, 255)
    }

    private fun drawHour(canvas: Canvas, r: Float, deg: Float) {
        drawGlow(canvas, deg, 0f, r * 0.55f, hourGlow)
        line(canvas, deg, 0f, r * 0.55f, r * 0.036f, Gear.ORANGE, 255)
        // 芯の中心を白っぽく光らせ、熱を持った線のように見せる
        line(canvas, deg, r * 0.07f, r * 0.53f, r * 0.012f, HOT_CORE, 235)

        // 中心の軸。オレンジの光の中に小さな白い点
        drawGlow(canvas, 0f, 0f, 0f, hubGlow)
        fill.shader = null
        fill.color = Gear.ORANGE
        canvas.drawCircle(clockCx, clockCy, r * 0.045f, fill)
        fill.color = HOT_CORE
        canvas.drawCircle(clockCx, clockCy, r * 0.015f, fill)
    }

    /**
     * 針の周りの光。中心からの距離に応じた明るさを、芯のすぐ周りの強い光と、広く淡いにじみの 2 つの山の和で決める。
     * 針の向きに依らない形なので、シェーダーは最初に 1 度だけ作り、描くときはキャンバスを回して使う。
     */
    private class Glow(color: Int, near: Float, nearAlpha: Float, far: Float, farAlpha: Float) {
        val reach = far * 3f
        private val profile = FloatArray(GLOW_STEPS + 1) { i ->
            val d = reach * i / GLOW_STEPS
            (nearAlpha * exp(-0.5f * (d / near) * (d / near)) + farAlpha * exp(-0.5f * (d / far) * (d / far)))
                .coerceIn(0f, 1f)
        }
        private val colors = IntArray(profile.size) { ColorUtils.setAlphaComponent(color, (255 * profile[it]).toInt()) }

        /** 針の幅方向（左右対称）の明るさ。 */
        val across: Shader = LinearGradient(-reach, 0f, reach, 0f,
            colors.reversedArray() + colors.copyOfRange(1, colors.size),
            FloatArray(GLOW_STEPS * 2 + 1) { it / (GLOW_STEPS * 2f) }, Shader.TileMode.CLAMP)

        /** 針の両端の丸い部分の明るさ。 */
        val round: Shader = RadialGradient(0f, 0f, reach, colors,
            FloatArray(colors.size) { it / GLOW_STEPS.toFloat() }, Shader.TileMode.CLAMP)
    }

    // 時計を出さないとき（半径 0）でもグラデーションを作れるよう、半径は 1 以上にしておく
    private val gr = clockR.coerceAtLeast(1f)
    private val tickGlow = Glow(Gear.ON, gr * 0.014f, 0.5f, gr * 0.04f, 0.14f + 0.16f * glow)
    private val minuteGlow = Glow(Gear.ON, gr * 0.016f, 0.55f, gr * 0.055f, 0.16f + 0.22f * glow)
    private val hourGlow = Glow(Gear.ORANGE, gr * 0.028f, 0.75f, gr * 0.085f, 0.25f + 0.3f * glow)
    private val hubGlow = Glow(Gear.ORANGE, gr * 0.05f, 0.6f, gr * 0.1f, 0.2f + 0.2f * glow)

    /**
     * [deg] の向きに、中心から [from]〜[to] の距離の線を包む光を描く。
     * 線の部分は幅方向のグラデーション、両端は半円の放射グラデーションでつなぐ。
     */
    private fun drawGlow(canvas: Canvas, deg: Float, from: Float, to: Float, g: Glow) {
        val e = g.reach
        canvas.save()
        canvas.translate(clockCx, clockCy)
        canvas.rotate(deg)
        if (to > from) {
            glowPaint.shader = g.across
            canvas.drawRect(-e, -to, e, -from, glowPaint)
        }
        // 先端側の半円（上向き）と根元側の半円（下向き）
        glowPaint.shader = g.round
        canvas.save()
        canvas.translate(0f, -to)
        glowOval.set(-e, -e, e, e)
        canvas.drawArc(glowOval, 180f, 180f, true, glowPaint)
        canvas.restore()
        canvas.translate(0f, -from)
        canvas.drawArc(glowOval, 0f, 180f, true, glowPaint)
        canvas.restore()
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

        /** 光の明るさを決める段数。 */
        const val GLOW_STEPS = 16

        /** 時針の芯と中心の点の、白に近い暖色。 */
        const val HOT_CORE = 0xFFFFE2CC.toInt()
    }

    private fun x(deg: Float, dist: Float) = clockCx + (sin(Math.toRadians(deg.toDouble())) * dist).toFloat()
    private fun y(deg: Float, dist: Float) = clockCy - (cos(Math.toRadians(deg.toDouble())) * dist).toFloat()
}
