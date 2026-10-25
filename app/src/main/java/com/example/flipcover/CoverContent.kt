package com.example.flipcover

import android.content.ComponentName
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** カバー画面とプレビューで共通に使う表示内容を組み立てる。 */
object CoverContent {

    /** [onLaunch] はアプリアイコンがタップされたときに呼ばれる（プレビューでは null）。 */
    fun create(context: Context, prefs: Prefs, onLaunch: ((ComponentName) -> Unit)? = null): FrameLayout {
        val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        val content: View = when (prefs.mode) {
            Mode.AURORA -> AuroraView(context)
            Mode.PARTICLES -> ParticlesView(context)
            Mode.GIF -> prefs.gifUri?.let { gifView(context, it) } ?: AuroraView(context)
            Mode.VIDEO -> prefs.videoUri?.let { LoopingVideoView(context, it) } ?: AuroraView(context)
        }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))

        if (prefs.showClock) {
            if (prefs.analogClock) {
                val size = context.dp(150)
                root.addView(AnalogClockView(context), FrameLayout.LayoutParams(size, size, Gravity.CENTER))
            } else {
                val clock = TextClock(context).apply {
                    format12Hour = "H:mm"
                    format24Hour = "H:mm"
                    setTextColor(Color.WHITE)
                    textSize = 48f
                    setShadowLayer(12f, 0f, 0f, Color.BLACK)
                }
                root.addView(clock, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            }
        }

        val apps = appRow(context, prefs.apps, onLaunch)
        if (apps != null) {
            root.addView(apps, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = context.dp(24)
            })
        }
        return root
    }

    private fun appRow(context: Context, apps: List<ComponentName>, onLaunch: ((ComponentName) -> Unit)?): View? {
        val pm = context.packageManager
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for (cn in apps) {
            // アンインストール済みのアプリは飛ばす
            val icon = runCatching { pm.getActivityIcon(cn) }.getOrNull() ?: continue
            val iv = ImageView(context).apply {
                setImageDrawable(icon)
                contentDescription = runCatching { pm.getActivityInfo(cn, 0).loadLabel(pm) }.getOrNull()
                val pad = context.dp(6)
                setPadding(pad, pad, pad, pad)
                setOnClickListener { onLaunch?.invoke(cn) }
            }
            row.addView(iv, LinearLayout.LayoutParams(context.dp(56), context.dp(56)))
        }
        return if (row.childCount > 0) row else null
    }

    private fun gifView(context: Context, uri: Uri): View {
        val iv = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        runCatching {
            val drawable = ImageDecoder.decodeDrawable(
                ImageDecoder.createSource(context.contentResolver, uri)
            )
            iv.setImageDrawable(drawable)
            (drawable as? AnimatedImageDrawable)?.apply {
                repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                start()
            }
        }
        return iv
    }
}

private fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

/** 文字盤と針を描くアナログ時計。 */
class AnalogClockView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
        setShadowLayer(8f, 0f, 0f, Color.BLACK)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) * 0.92f

        // 目盛り（5分ごとに太く）
        for (i in 0 until 60) {
            val a = Math.toRadians(i * 6.0)
            val major = i % 5 == 0
            paint.strokeWidth = if (major) r * 0.045f else r * 0.018f
            val inner = if (major) r * 0.82f else r * 0.9f
            canvas.drawLine(
                cx + inner * sin(a).toFloat(), cy - inner * cos(a).toFloat(),
                cx + r * sin(a).toFloat(), cy - r * cos(a).toFloat(), paint,
            )
        }

        val now = Calendar.getInstance()
        val sec = now.get(Calendar.SECOND) + now.get(Calendar.MILLISECOND) / 1000f
        val minute = now.get(Calendar.MINUTE) + sec / 60f
        val hour = now.get(Calendar.HOUR) + minute / 60f
        hand(canvas, cx, cy, hour * 30f, r * 0.5f, r * 0.07f, Color.WHITE)
        hand(canvas, cx, cy, minute * 6f, r * 0.75f, r * 0.05f, Color.WHITE)
        hand(canvas, cx, cy, sec * 6f, r * 0.85f, r * 0.02f, 0xFFF472B6.toInt())
        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, r * 0.05f, paint)

        // 秒針を滑らかに動かしつつ、毎フレームは描かない
        postInvalidateDelayed(100)
    }

    private fun hand(canvas: Canvas, cx: Float, cy: Float, deg: Float, len: Float, w: Float, c: Int) {
        val a = Math.toRadians(deg.toDouble())
        paint.color = c
        paint.strokeWidth = w
        canvas.drawLine(cx, cy, cx + len * sin(a).toFloat(), cy - len * cos(a).toFloat(), paint)
    }
}

/** ゆっくり動く色の光でオーロラ風の背景を描く。 */
class AuroraView(context: Context) : View(context) {
    private val colors = intArrayOf(0xFF7C3AED.toInt(), 0xFF06B6D4.toInt(), 0xFFEC4899.toInt(), 0xFF22C55E.toInt())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val start = System.nanoTime()

    override fun onDraw(canvas: Canvas) {
        val t = (System.nanoTime() - start) / 1e9f
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(0xFF0B0B1A.toInt())
        colors.forEachIndexed { i, c ->
            val phase = i * 1.7f
            val cx = w * (0.5f + 0.35f * sin(t * 0.35f + phase))
            val cy = h * (0.5f + 0.35f * cos(t * 0.27f + phase * 1.3f))
            val r = maxOf(w, h) * (0.45f + 0.1f * sin(t * 0.5f + phase))
            paint.shader = RadialGradient(cx, cy, r, c and 0x99FFFFFF.toInt(), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r, paint)
        }
        postInvalidateOnAnimation()
    }
}

/** 光の粒がふわふわ上昇していくアニメーション。 */
class ParticlesView(context: Context) : View(context) {
    private class P(var x: Float, var y: Float, val r: Float, val speed: Float, val drift: Float, val hue: Float)

    private val particles = mutableListOf<P>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var last = System.nanoTime()
    private var time = 0f

    private fun spawn(atBottom: Boolean) = P(
        x = Random.nextFloat() * width,
        y = if (atBottom) height + 20f else Random.nextFloat() * height,
        r = 3f + Random.nextFloat() * 10f,
        speed = 30f + Random.nextFloat() * 90f,
        drift = Random.nextFloat() * 6.28f,
        hue = 180f + Random.nextFloat() * 140f,
    )

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        particles.clear()
        repeat(70) { particles += spawn(atBottom = false) }
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = ((now - last) / 1e9f).coerceAtMost(0.05f)
        last = now
        time += dt
        canvas.drawColor(0xFF050510.toInt())
        for (i in particles.indices) {
            val p = particles[i]
            p.y -= p.speed * dt
            p.x += sin(time + p.drift) * 15f * dt
            if (p.y < -20f) particles[i] = spawn(atBottom = true)
            val alpha = (0.5f + 0.5f * sin(time * 2f + p.drift)).coerceIn(0.2f, 1f)
            paint.color = Color.HSVToColor((alpha * 255).toInt(), floatArrayOf(p.hue, 0.6f, 1f))
            paint.setShadowLayer(p.r * 2, 0f, 0f, paint.color)
            canvas.drawCircle(p.x, p.y, p.r, paint)
        }
        postInvalidateOnAnimation()
    }
}

/** 動画を無音ループ再生し、画面いっぱいにクロップ表示する。 */
class LoopingVideoView(context: Context, private val uri: Uri) : TextureView(context),
    TextureView.SurfaceTextureListener {
    private var player: MediaPlayer? = null

    init { surfaceTextureListener = this }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        player = MediaPlayer().apply {
            runCatching {
                setDataSource(context, uri)
                setSurface(Surface(st))
                isLooping = true
                setVolume(0f, 0f)
                setOnVideoSizeChangedListener { _, vw, vh -> applyCenterCrop(vw, vh) }
                setOnPreparedListener { it.start() }
                prepareAsync()
            }
        }
    }

    private fun applyCenterCrop(vw: Int, vh: Int) {
        if (vw == 0 || vh == 0 || width == 0 || height == 0) return
        val scale = maxOf(width.toFloat() / vw, height.toFloat() / vh)
        val sx = vw * scale / width
        val sy = vh * scale / height
        setTransform(Matrix().apply { setScale(sx, sy, width / 2f, height / 2f) })
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
        player?.let { applyCenterCrop(it.videoWidth, it.videoHeight) }
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        player?.release()
        player = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
}
