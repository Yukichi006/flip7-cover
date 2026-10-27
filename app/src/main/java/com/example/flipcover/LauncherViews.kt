package com.example.flipcover

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.ColorUtils
import kotlin.math.min

/** アプリを開くボタンのデザイン。どれも時計と同じ配色にそろえている。 */
enum class LauncherStyle(val label: String) {
    DOTS("ドット（3×3）"),
    RING("リング"),
    PAD("パッド（機材風）"),
    GLOW("光"),
    PLUS("プラス"),
}

/**
 * タップするとアプリアイコンが展開するボタン。
 * [openness] が 0 で閉じた見た目、1 で開いた見た目（オレンジに光る、× に回るなど）。
 */
class LauncherButton(context: Context, private val style: LauncherStyle) : View(context) {
    var openness = 0f
        private set(v) {
            field = v
            invalidate()
        }
    private var animator: ValueAnimator? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()

    fun animateOpen(open: Boolean) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(openness, if (open) 1f else 0f).apply {
            duration = 200
            addUpdateListener { openness = it.animatedValue as Float }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) * 0.92f
        val o = openness
        val accent = ColorUtils.blendARGB(Gear.ON, Gear.ORANGE, o)
        fill.shader = null
        when (style) {
            LauncherStyle.DOTS -> {
                circle(canvas, cx, cy, r, PAD_COLOR)
                // 開くと 45° 回り、真ん中のドットがオレンジになる
                canvas.save()
                canvas.rotate(45f * o, cx, cy)
                val step = r * 0.4f
                for (j in -1..1) for (i in -1..1) {
                    circle(canvas, cx + i * step, cy + j * step, r * 0.11f,
                        if (i == 0 && j == 0) accent else Gear.ON)
                }
                canvas.restore()
            }
            LauncherStyle.RING -> {
                circle(canvas, cx, cy, r, PAD_COLOR)
                stroke.color = accent
                stroke.strokeWidth = r * 0.09f
                canvas.drawCircle(cx, cy, r * 0.62f, stroke)
                circle(canvas, cx, cy, r * 0.16f * (1f + 0.4f * o), accent)
            }
            LauncherStyle.PAD -> {
                // 押すとオレンジに光る、機材のパッド
                val half = r * 0.9f
                rect.set(cx - half, cy - half, cx + half, cy + half)
                fill.color = ColorUtils.blendARGB(Gear.PAD, Gear.ORANGE, o * 0.9f)
                canvas.drawRoundRect(rect, r * 0.28f, r * 0.28f, fill)
                stroke.color = Gear.ON
                stroke.alpha = 60
                stroke.strokeWidth = r * 0.04f
                canvas.drawRoundRect(rect, r * 0.28f, r * 0.28f, stroke)
                circle(canvas, cx + half * 0.6f, cy - half * 0.6f, r * 0.09f, Gear.YELLOW)
            }
            LauncherStyle.GLOW -> {
                // ふんわり光る球。開くとオレンジの光になる
                fill.shader = RadialGradient(cx, cy, r,
                    intArrayOf(ColorUtils.setAlphaComponent(accent, 200), ColorUtils.setAlphaComponent(accent, 70), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
                canvas.drawCircle(cx, cy, r, fill)
                fill.shader = null
                circle(canvas, cx, cy, r * 0.2f, accent)
            }
            LauncherStyle.PLUS -> {
                circle(canvas, cx, cy, r, PAD_COLOR)
                stroke.color = Gear.ON
                stroke.alpha = 120
                stroke.strokeWidth = r * 0.05f
                canvas.drawCircle(cx, cy, r * 0.94f, stroke)
                // 開くと 45° 回って × になる
                canvas.save()
                canvas.rotate(45f * o, cx, cy)
                stroke.color = accent
                stroke.strokeWidth = r * 0.12f
                canvas.drawLine(cx - r * 0.42f, cy, cx + r * 0.42f, cy, stroke)
                canvas.drawLine(cx, cy - r * 0.42f, cx, cy + r * 0.42f, stroke)
                canvas.restore()
            }
        }
    }

    private fun circle(canvas: Canvas, x: Float, y: Float, radius: Float, color: Int) {
        fill.color = color
        canvas.drawCircle(x, y, radius, fill)
    }

    private companion object {
        /** ボタンの下地。背景より少し明るいチャコール。 */
        const val PAD_COLOR = 0xE6262623.toInt()
    }
}

/**
 * ボタンでアプリアイコンを展開・収納する。
 * 開くとアイコンがボタンから飛び出して各自の位置へ広がり、後ろが少し暗くなる。
 * 暗い部分かボタンをタップするか、しばらく操作しないと閉じる。
 */
class AppLauncher(
    private val button: LauncherButton,
    private val scrim: View,
    private val icons: List<View>,
) {
    private var open = false
    private val autoClose = Runnable { setOpen(false) }

    init {
        // 閉じている間はアイコンを見せず、タップも受けない（位置は計算しておくため GONE にはしない）
        scrim.visibility = View.INVISIBLE
        icons.forEach { it.visibility = View.INVISIBLE }
        button.setOnClickListener { setOpen(!open) }
        scrim.setOnClickListener { setOpen(false) }
    }

    fun setOpen(value: Boolean) {
        open = value
        button.animateOpen(value)
        button.removeCallbacks(autoClose)
        val bx = button.left + button.width / 2f
        val by = button.top + button.height / 2f
        if (value) {
            scrim.alpha = 0f
            scrim.visibility = View.VISIBLE
            scrim.animate().alpha(1f).setDuration(180).start()
            icons.forEachIndexed { i, v ->
                v.visibility = View.VISIBLE
                v.translationX = bx - (v.left + v.width / 2f)
                v.translationY = by - (v.top + v.height / 2f)
                v.scaleX = 0.3f
                v.scaleY = 0.3f
                v.alpha = 0f
                v.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).alpha(1f)
                    .setStartDelay(i * 30L).setDuration(220).setInterpolator(DecelerateInterpolator())
                    .start()
            }
            button.postDelayed(autoClose, AUTO_CLOSE_MS)
        } else {
            scrim.animate().alpha(0f).setDuration(160)
                .withEndAction { if (!open) scrim.visibility = View.INVISIBLE }.start()
            icons.forEach { v ->
                v.animate()
                    .translationX(bx - (v.left + v.width / 2f)).translationY(by - (v.top + v.height / 2f))
                    .scaleX(0.3f).scaleY(0.3f).alpha(0f).setStartDelay(0).setDuration(160)
                    .withEndAction { if (!open) v.visibility = View.INVISIBLE }.start()
            }
        }
    }

    private companion object {
        /** 開いたまま操作がないと、この時間で閉じる。 */
        const val AUTO_CLOSE_MS = 6000L
    }
}
