package com.example.flipcover

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * 横長のつまみをスワイプして、背面のライトを点けたり消したりするスイッチ。
 * ポケットの中などで触れて点かないよう、タップでは切り替えず、端までスワイプしたときだけ切り替える。
 * 右へスワイプで点灯、左へスワイプで消灯。ほかのアプリでライトを切り替えたときも見た目を合わせる。
 *
 * 見た目は、フェルトの帯にステッチの縁取り、つまみは縫い付けボタン。
 * 点いていくほど、つまみが通った所を白い糸で埋め（サテン刺繍）、点くとボタンが生成り色になる。
 * 編集モードでは、マス目が上に被さって指を受け取るので、切り替わらない。
 */
@SuppressLint("ClickableViewAccessibility", "ViewConstructor")
class TorchSwitch(context: Context) : View(context), SewAware {
    private val cameraManager: CameraManager = context.getSystemService(CameraManager::class.java)!!
    private val patch = PatchPainter(context, PatchPainter.Shape.Round(999f))
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
        if (!available) return false
        val travel = travel()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animator?.cancel()
                dragging = true
                downX = e.x
                downKnob = knob
                // スワイプの間は、ページの切り替えに指を取られないようにする
                parent?.requestDisallowInterceptTouchEvent(true)
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

    /** 帯の、影の余白を除いた範囲。 */
    private fun band(): RectF {
        val m = patch.margin
        rect.set(m, m, width - m, height - m)
        return rect
    }

    /** つまみが動ける距離（px）。 */
    private fun travel(): Float = (width - patch.margin * 2) - (height - patch.margin * 2)

    private fun blend(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        fun mix(x: Int, y: Int) = (x + (y - x) * u).toInt()
        return Color.argb(mix(Color.alpha(a), Color.alpha(b)), mix(Color.red(a), Color.red(b)), mix(Color.green(a), Color.green(b)), mix(Color.blue(a), Color.blue(b)))
    }

    override fun onDraw(canvas: Canvas) {
        patch.draw(canvas, width, height)
        val d = resources.displayMetrics.density
        val b = RectF(band())
        val h = b.height()
        val r = h / 2f
        val kr = r - h * 0.16f
        val kx = b.left + r + knob * travel()
        val cy = b.centerY()
        val lit = if (available) knob else 0f
        if (lit > 0f) {
            rect.set(b.left + h * 0.3f, cy - h * 0.1f, kx, cy + h * 0.1f)
            fill.color = Color.argb((230 * lit).toInt(), Color.red(Thread.COLOR), Color.green(Thread.COLOR), Color.blue(Thread.COLOR))
            canvas.drawRoundRect(rect, h * 0.1f, h * 0.1f, fill)
        }
        if (available) {
            // スワイプする向きの矢印（縫い目）
            val dir = if (on) -1f else 1f
            val size = h * 0.1f
            val start = kx + dir * (kr + h * 0.3f)
            val seam = stitchPaint((h * 0.03f).coerceAtLeast(1f * d), h * 0.06f, h * 0.04f, Thread.DIM)
            for (i in 0 until 3) {
                val x = start + dir * i * h * 0.22f
                if (x < b.left + h * 0.4f || x > b.right - h * 0.4f) continue
                canvas.drawLine(x - dir * size * 0.5f, cy - size, x + dir * size * 0.5f, cy, seam)
                canvas.drawLine(x + dir * size * 0.5f, cy, x - dir * size * 0.5f, cy + size, seam)
            }
        }
        // つまみ：縫い付けボタン（点くと生成り色）
        val btn = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = blend(Cloth.LINING, Thread.COLOR, lit)
            setShadowLayer(4f * d, 0f, 2f * d, 0xC0000000.toInt())
        }
        canvas.drawCircle(kx, cy, kr, btn)
        canvas.drawCircle(kx, cy, kr * 0.78f, stitchPaint((kr * 0.05f).coerceAtLeast(0.9f * d), kr * 0.14f, kr * 0.1f,
            if (lit > 0.5f) 0x99000000.toInt() else Thread.COLOR))
        fill.color = if (lit > 0.5f) 0xFF2A2A28.toInt() else 0xFF050505.toInt()
        val k = kr * 0.22f
        for ((x, y) in listOf(-k to -k, k to -k, k to k, -k to k)) canvas.drawCircle(kx + x, cy + y, kr * 0.09f, fill)
    }

    private companion object {
        /** 端からこの割合以内まで動かしたら切り替える。 */
        const val SWITCH_AT = 0.25f
    }
}
