package com.example.flipcover

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.cos
import kotlin.math.sin

/**
 * 横長のつまみをスワイプして、背面のライトを点けたり消したりするスイッチ。
 * ポケットの中などで触れて点かないよう、タップでは切り替えず、端までスワイプしたときだけ切り替える。
 * 右へスワイプで点灯、左へスワイプで消灯。ほかのアプリでライトを切り替えたときも見た目を合わせる。
 *
 * @param interactive false ならタッチを受けない（プレビューでドラッグ・ピンチできるようにする）
 */
@SuppressLint("ClickableViewAccessibility")
class TorchSwitch(context: Context, private val interactive: Boolean) : View(context) {
    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val cameraId: String? = runCatching {
        cameraManager.cameraIdList.firstOrNull { id ->
            val c = cameraManager.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    }.getOrNull()

    private var on = false
    private var available = cameraId != null

    /** つまみの位置。0 で左端（消灯）、1 で右端（点灯）。 */
    private var knob = 0f
        set(v) {
            field = v
            invalidate()
        }
    private var animator: ValueAnimator? = null
    private var dragging = false
    private var downX = 0f
    private var downKnob = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(id: String, enabled: Boolean) {
            if (id != cameraId) return
            available = true
            on = enabled
            if (!dragging) slideTo(if (enabled) 1f else 0f)
        }

        override fun onTorchModeUnavailable(id: String) {
            // カメラを使っている間などは切り替えられないので、暗くして示す
            if (id != cameraId) return
            available = false
            on = false
            if (!dragging) slideTo(0f)
        }
    }

    init {
        contentDescription = "ライト"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 登録するとすぐに今の状態が届く
        if (cameraId != null) runCatching { cameraManager.registerTorchCallback(torchCallback, Handler(Looper.getMainLooper())) }
    }

    override fun onDetachedFromWindow() {
        runCatching { cameraManager.unregisterTorchCallback(torchCallback) }
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!interactive || !available) return false
        val travel = travel()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animator?.cancel()
                dragging = true
                downX = e.x
                downKnob = knob
            }
            MotionEvent.ACTION_MOVE -> if (travel > 0f) {
                knob = (downKnob + (e.x - downX) / travel).coerceIn(0f, 1f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                // 反対側の近くまで動かしたときだけ切り替え、そうでなければ元に戻す
                val target = when {
                    e.actionMasked == MotionEvent.ACTION_CANCEL -> on
                    !on && knob > 1f - SWITCH_AT -> true
                    on && knob < SWITCH_AT -> false
                    else -> on
                }
                if (target != on) setTorch(target)
                slideTo(if (on) 1f else 0f)
            }
        }
        return true
    }

    private fun setTorch(value: Boolean) {
        val id = cameraId ?: return
        runCatching { cameraManager.setTorchMode(id, value) }.onSuccess {
            on = value
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        }.onFailure {
            performHapticFeedback(HapticFeedbackConstants.REJECT)
        }
    }

    private fun slideTo(target: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(knob, target).apply {
            duration = 180
            addUpdateListener { knob = it.animatedValue as Float }
            start()
        }
    }

    /** つまみが動ける距離（px）。 */
    private fun travel() = width - height.toFloat()

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val r = h / 2f
        val inset = h * 0.08f
        val kr = r - inset
        val kx = r + knob * travel()
        val cy = r
        val glow = if (available) knob else 0f

        // 下地。点いていくほど、つまみが通った所が黄色く光る
        rect.set(0f, 0f, width.toFloat(), h)
        fill.shader = null
        fill.color = TRACK_COLOR
        canvas.drawRoundRect(rect, r, r, fill)
        if (glow > 0f) {
            rect.set(0f, 0f, kx + r, h)
            fill.color = ColorUtils.setAlphaComponent(Gear.YELLOW, (90 * glow).toInt())
            canvas.drawRoundRect(rect, r, r, fill)
        }
        rect.set(0f, 0f, width.toFloat(), h)
        stroke.color = Gear.ON
        stroke.alpha = 50
        stroke.strokeWidth = h * 0.03f
        canvas.drawRoundRect(rect, r, r, stroke)

        // スワイプする向きを示す薄い矢印
        if (available) chevrons(canvas, kx, kr, cy, h, if (on) -1f else 1f)

        // つまみの周りの光
        if (glow > 0f) {
            fill.shader = RadialGradient(kx, cy, r * 1.6f,
                ColorUtils.setAlphaComponent(Gear.YELLOW, (150 * glow).toInt()), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawCircle(kx, cy, r * 1.6f, fill)
            fill.shader = null
        }
        fill.color = if (available) ColorUtils.blendARGB(KNOB_COLOR, Gear.YELLOW, glow) else Gear.OFF
        canvas.drawCircle(kx, cy, kr, fill)
        bulb(canvas, kx, cy, kr, ColorUtils.blendARGB(Gear.ON, Gear.BG, if (available) glow else 0.6f))
    }

    /** つまみの進む側に、3 つ並んだ矢印を薄く描く。[dir] は 1 で右向き、-1 で左向き。 */
    private fun chevrons(canvas: Canvas, kx: Float, kr: Float, cy: Float, h: Float, dir: Float) {
        val size = h * 0.12f
        val gap = h * 0.22f
        val start = kx + dir * (kr + h * 0.3f)
        stroke.strokeWidth = h * 0.05f
        for (i in 0 until 3) {
            val x = start + dir * i * gap
            if (x < h * 0.3f || x > width - h * 0.3f) continue
            stroke.color = Gear.ON
            stroke.alpha = 110 - i * 30
            canvas.drawLine(x - dir * size * 0.5f, cy - size, x + dir * size * 0.5f, cy, stroke)
            canvas.drawLine(x + dir * size * 0.5f, cy, x - dir * size * 0.5f, cy + size, stroke)
        }
    }

    /** つまみの中の、光の粒と放射する短い線（ライトの印）。 */
    private fun bulb(canvas: Canvas, cx: Float, cy: Float, kr: Float, color: Int) {
        fill.color = color
        canvas.drawCircle(cx, cy, kr * 0.2f, fill)
        stroke.color = color
        stroke.strokeWidth = kr * 0.1f
        for (k in 0 until 8) {
            val a = Math.toRadians(k * 45.0)
            val s = sin(a).toFloat()
            val c = cos(a).toFloat()
            canvas.drawLine(cx + s * kr * 0.36f, cy - c * kr * 0.36f, cx + s * kr * 0.52f, cy - c * kr * 0.52f, stroke)
        }
    }

    private companion object {
        /** 端からこの割合以内まで動かしたら切り替える。 */
        const val SWITCH_AT = 0.25f

        /** 下地とつまみの色。ランチャーのボタンと同じチャコール系。 */
        const val TRACK_COLOR = 0xE6262623.toInt()
        const val KNOB_COLOR = 0xFF3A3A36.toInt()
    }
}
