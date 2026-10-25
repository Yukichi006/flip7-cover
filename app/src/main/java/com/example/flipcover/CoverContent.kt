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
import android.graphics.Typeface
import android.graphics.SurfaceTexture
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.util.Size
import android.util.TypedValue
import android.view.Display
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
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * カバー画面とプレビューで共通に使う表示内容を組み立てる。
 * 配置はカバー画面の実寸（px）で計算し、プレビューはそれを縮小して表示する。
 */
object CoverContent {
    const val TAG_CLOCK = "clock"
    const val TAG_APPS = "apps"

    /** カバー画面の実寸。取得できなければ Flip7 の値を使う。 */
    fun coverSize(display: Display?): Size {
        val mode = display?.mode
        return if (mode != null && mode.physicalWidth > 0) Size(mode.physicalWidth, mode.physicalHeight)
        else Size(948, 1048)
    }

    /** [onLaunch] はアプリアイコンがタップされたときに呼ばれる（プレビューでは null）。 */
    fun create(
        context: Context,
        prefs: Prefs,
        size: Size,
        onLaunch: ((ComponentName) -> Unit)? = null,
    ): FrameLayout {
        val w = size.width
        val h = size.height
        val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        val speed = prefs.animSpeed
        val content: View = when (prefs.mode) {
            Mode.AURORA -> AuroraView(context, speed)
            Mode.PARTICLES -> ParticlesView(context, speed)
            Mode.GIF -> prefs.gifUri?.let { gifView(context, it) } ?: AuroraView(context, speed)
            Mode.VIDEO -> prefs.videoUri?.let { LoopingVideoView(context, it) } ?: AuroraView(context, speed)
            Mode.EARTH -> SpaceView(context, SpaceView.Planet.EARTH, speed)
            Mode.MOON -> SpaceView(context, SpaceView.Planet.MOON, speed)
            // ドット秒は時計そのものなので速さは変えない
            Mode.DOT_SECONDS -> DotSecondsView(context)
            Mode.LEVEL_METER -> LevelMeterView(context, speed)
            Mode.DOT_WAVE -> DotWaveView(context, speed)
            Mode.SEQUENCER -> SequencerView(context, speed)
        }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))

        if (prefs.showClock) {
            val box = (prefs.clockSize * w).toInt()
            val style = prefs.clockStyle
            val (clock, boxH) = if (style == ClockStyle.BOLD) {
                BoldClockView(context) to (box * 0.6f).toInt()
            } else if (style != ClockStyle.DIGITAL) {
                AnalogClockView(context, style) to box
            } else {
                TextClock(context).apply {
                    format12Hour = "H:mm"
                    format24Hour = "H:mm"
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, box * 0.3f)
                    setShadowLayer(12f, 0f, 0f, Color.BLACK)
                } to box / 2
            }
            // ぼかした背景の分だけ枠を広げ、時計はその中央に置く
            val pad = if (prefs.clockBackdrop) (box * 0.18f).toInt() else 0
            val holder = FrameLayout(context).apply {
                tag = TAG_CLOCK
                if (prefs.clockBackdrop) addView(SoftBackdrop(context), FrameLayout.LayoutParams(-1, -1))
                addView(clock, FrameLayout.LayoutParams(box, boxH, Gravity.CENTER))
            }
            root.addView(holder, place(size, prefs.clockX, prefs.clockY, box + pad * 2, boxH + pad * 2))
        }

        val icon = (prefs.appsSize * w).toInt()
        appRow(context, prefs.apps, icon, onLaunch)?.let { row ->
            row.tag = TAG_APPS
            root.addView(row, place(size, prefs.appsX, prefs.appsY, row.childCount * icon, icon))
        }
        return root
    }

    /** 中心が (cx, cy)（画面に対する割合）になるよう配置する。画面からははみ出さない。 */
    private fun place(size: Size, cx: Float, cy: Float, vw: Int, vh: Int) =
        FrameLayout.LayoutParams(vw, vh).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = (cx * size.width - vw / 2f).toInt().coerceIn(0, maxOf(0, size.width - vw))
            topMargin = (cy * size.height - vh / 2f).toInt().coerceIn(0, maxOf(0, size.height - vh))
        }

    private fun appRow(
        context: Context,
        apps: List<ComponentName>,
        iconSize: Int,
        onLaunch: ((ComponentName) -> Unit)?,
    ): LinearLayout? {
        val pm = context.packageManager
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for (cn in apps) {
            // アンインストール済みのアプリは飛ばす
            val icon = runCatching { pm.getActivityIcon(cn) }.getOrNull() ?: continue
            val iv = ImageView(context).apply {
                setImageDrawable(icon)
                contentDescription = runCatching { pm.getActivityInfo(cn, 0).loadLabel(pm) }.getOrNull()
                val pad = iconSize / 9
                setPadding(pad, pad, pad, pad)
                // プレビューではタップを奪わないよう、起動先があるときだけクリック可能にする
                if (onLaunch != null) setOnClickListener { onLaunch(cn) }
            }
            row.addView(iv, LinearLayout.LayoutParams(iconSize, iconSize))
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

/** 時計の後ろに敷く、輪郭をぼかした暗い楕円。背景が明るくても時刻を読みやすくする。 */
class SoftBackdrop(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // 中心はしっかり暗く、外側へなだらかに消える
        paint.shader = RadialGradient(
            w / 2f, h / 2f, w / 2f,
            intArrayOf(0x99000000.toInt(), 0x8C000000.toInt(), 0x66000000, 0x26000000, 0x00000000),
            floatArrayOf(0f, 0.4f, 0.65f, 0.85f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        // 円のグラデーションを縦に縮めて、枠の形に合わせた楕円にする
        canvas.save()
        canvas.scale(1f, height / width.toFloat(), width / 2f, height / 2f)
        canvas.drawCircle(width / 2f, height / 2f, width / 2f, paint)
        canvas.restore()
    }
}

/** 文字盤と針を描くアナログ時計。[style] でデザインを切り替える。 */
class AnalogClockView(context: Context, private val style: ClockStyle) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) * 0.92f

        val now = Calendar.getInstance()
        val sec = now.get(Calendar.SECOND) + now.get(Calendar.MILLISECOND) / 1000f
        val minute = now.get(Calendar.MINUTE) + sec / 60f
        val hour = now.get(Calendar.HOUR) + minute / 60f

        when (style) {
            ClockStyle.MINIMAL -> {
                shadow(r * 0.03f, Color.BLACK)
                paint.style = Paint.Style.FILL
                for (i in 0 until 4) dot(canvas, cx, cy, i * 90f, r * 0.92f, r * 0.04f, Color.WHITE)
                hands(canvas, cx, cy, r, hour, minute, sec, r * 0.035f, r * 0.025f, Color.WHITE, 0xFFFF9F43.toInt())
            }
            ClockStyle.NUMBERS -> {
                shadow(r * 0.03f, Color.BLACK)
                ticks(canvas, cx, cy, r, major = 0.9f, minor = 0.95f, majorW = 0.03f, minorW = 0.012f, color = Color.WHITE)
                numerals(canvas, cx, cy, r * 0.72f, r * 0.2f, Color.WHITE, Typeface.DEFAULT_BOLD, ARABIC)
                hands(canvas, cx, cy, r, hour, minute, sec, r * 0.06f, r * 0.04f, Color.WHITE, 0xFFFF5252.toInt())
            }
            ClockStyle.ROMAN -> {
                val gold = 0xFFE9C46A.toInt()
                shadow(r * 0.03f, Color.BLACK)
                ring(canvas, cx, cy, r, r * 0.025f, gold)
                ring(canvas, cx, cy, r * 0.9f, r * 0.008f, gold)
                ticks(canvas, cx, cy, r * 0.9f, major = 0.92f, minor = 0.96f, majorW = 0.02f, minorW = 0.008f, color = gold)
                numerals(canvas, cx, cy, r * 0.68f, r * 0.17f, gold, Typeface.SERIF, ROMAN_NUMERALS)
                hands(canvas, cx, cy, r, hour, minute, sec, r * 0.045f, r * 0.03f, gold, Color.WHITE)
            }
            ClockStyle.NEON -> {
                val cyan = 0xFF22D3EE.toInt()
                shadow(r * 0.12f, cyan)
                ring(canvas, cx, cy, r, r * 0.03f, cyan)
                for (i in 0 until 12) {
                    line(canvas, cx, cy, i * 30f, r * 0.78f, r * 0.88f, r * 0.03f, cyan)
                }
                hands(canvas, cx, cy, r, hour, minute, sec, r * 0.05f, r * 0.035f, cyan, 0xFFF472B6.toInt(), glow = r * 0.12f)
            }
            ClockStyle.DOTS -> {
                shadow(0f, Color.TRANSPARENT)
                paint.style = Paint.Style.FILL
                paint.color = 0x66000000
                canvas.drawCircle(cx, cy, r, paint)
                shadow(r * 0.02f, Color.BLACK)
                for (i in 0 until 60) {
                    val major = i % 5 == 0
                    dot(canvas, cx, cy, i * 6f, r * 0.88f, if (major) r * 0.045f else r * 0.015f,
                        if (major) Color.WHITE else 0xFFB0B0B0.toInt())
                }
                hands(canvas, cx, cy, r, hour, minute, sec, r * 0.08f, r * 0.055f, Color.WHITE, 0xFFFACC15.toInt())
                dot(canvas, cx, cy, sec * 6f, r * 0.7f, r * 0.04f, 0xFFFACC15.toInt())
            }
            ClockStyle.DOT_MATRIX -> dotMatrix(canvas, cx, cy, r, hour, minute, sec)
            ClockStyle.DOT_RING -> dotRing(canvas, cx, cy, r, hour, minute, sec)
            ClockStyle.DOT_HANDS -> dotHands(canvas, cx, cy, r, hour, minute, sec)
            else -> {
                shadow(r * 0.03f, Color.BLACK)
                ticks(canvas, cx, cy, r, major = 0.82f, minor = 0.9f, majorW = 0.045f, minorW = 0.018f, color = Color.WHITE)
                hands(canvas, cx, cy, r, hour, minute, sec, r * 0.07f, r * 0.05f, Color.WHITE, 0xFFF472B6.toInt())
            }
        }

        // 秒針が動いて見える程度に、描き直しは控えめにする
        postInvalidateDelayed(250)
    }

    /** 丸く並べたドットのうち、針の上にあるものほど大きく明るくして針を浮かび上がらせる。 */
    private fun dotMatrix(canvas: Canvas, cx: Float, cy: Float, r: Float, hour: Float, minute: Float, sec: Float) {
        shadow(0f, Color.TRANSPARENT)
        paint.style = Paint.Style.FILL
        val n = 19
        val g = r * 2 / n
        val ha = Math.toRadians(hour * 30.0)
        val ma = Math.toRadians(minute * 6.0)
        val hx = sin(ha).toFloat() * r * 0.5f
        val hy = -cos(ha).toFloat() * r * 0.5f
        val mx = sin(ma).toFloat() * r * 0.82f
        val my = -cos(ma).toFloat() * r * 0.82f
        for (j in 0 until n) for (i in 0 until n) {
            val x = (i - (n - 1) / 2f) * g
            val y = (j - (n - 1) / 2f) * g
            if (x * x + y * y > (r * 0.92f) * (r * 0.92f)) continue
            val hourHit = bump(segmentDistance(x, y, hx, hy), g * 0.95f)
            val minHit = bump(segmentDistance(x, y, mx, my), g * 0.6f) * 0.9f
            val v = max(hourHit, minHit)
            // 近い方の針の色に染める
            paint.color = if (minHit > hourHit) MINUTE_COLOR else HOUR_COLOR
            paint.alpha = (30 + 160 * v).toInt()
            canvas.drawCircle(cx + x, cy + y, g * (0.1f + 0.3f * v), paint)
        }
        paint.alpha = 255
        solidHands(canvas, cx, cy, r, hour, minute, g * 0.55f, g * 0.32f)
        dot(canvas, cx, cy, sec * 6f, r * 0.99f, g * 0.3f, Gear.ORANGE)
    }

    /** 外から秒・分・時の 3 重のドットの輪。経過した分だけ点き、今の位置はオレンジ。 */
    private fun dotRing(canvas: Canvas, cx: Float, cy: Float, r: Float, hour: Float, minute: Float, sec: Float) {
        shadow(0f, Color.TRANSPARENT)
        dotRow(canvas, cx, cy, r * 0.95f, r * 0.02f, 60, sec.toInt())
        dotRow(canvas, cx, cy, r * 0.74f, r * 0.024f, 60, minute.toInt())
        dotRow(canvas, cx, cy, r * 0.5f, r * 0.05f, 12, hour.toInt() % 12)
        solidHands(canvas, cx, cy, r, hour, minute, r * 0.06f, r * 0.035f, hourLen = 0.36f, minLen = 0.64f)
    }

    private fun dotRow(canvas: Canvas, cx: Float, cy: Float, radius: Float, size: Float, count: Int, now: Int) {
        for (i in 0 until count) {
            val (color, s) = when {
                i < now -> Gear.ON to size
                i == now -> Gear.ORANGE to size * 1.4f
                else -> Gear.OFF to size * 0.7f
            }
            dot(canvas, cx, cy, i * 360f / count, radius, s, color)
        }
    }

    /** 針を先へ行くほど小さくなるドットの列で描き、秒はオレンジのドットが尾を引いて回る。 */
    private fun dotHands(canvas: Canvas, cx: Float, cy: Float, r: Float, hour: Float, minute: Float, sec: Float) {
        shadow(0f, Color.TRANSPARENT)
        for (i in 0 until 12) {
            val quarter = i % 3 == 0
            dot(canvas, cx, cy, i * 30f, r * 0.94f, r * (if (quarter) 0.045f else 0.025f),
                if (quarter) Gear.ON else Gear.GRAY)
        }
        // ドットの下に細い線を通して、針として一続きに見えるようにする
        line(canvas, cx, cy, hour * 30f, 0f, r * 0.5f, r * 0.03f, HOUR_COLOR and 0x00FFFFFF or 0x99000000.toInt())
        line(canvas, cx, cy, minute * 6f, 0f, r * 0.78f, r * 0.02f, MINUTE_COLOR and 0x00FFFFFF or 0x99000000.toInt())
        for (k in 0 until 6) {
            val f = (k + 1) / 6f
            dot(canvas, cx, cy, hour * 30f, r * 0.5f * f, r * (0.075f - 0.03f * f), HOUR_COLOR)
        }
        for (k in 0 until 9) {
            val f = (k + 1) / 9f
            dot(canvas, cx, cy, minute * 6f, r * 0.78f * f, r * (0.055f - 0.025f * f), MINUTE_COLOR)
        }
        for (k in 7 downTo 0) {
            val fade = 1f - k / 8f
            paint.alpha = 255
            dot(canvas, cx, cy, sec * 6f - k * 4f, r * 0.84f, r * 0.04f * (0.4f + 0.6f * fade),
                Gear.ORANGE and 0x00FFFFFF or ((fade * fade * 255).toInt() shl 24))
        }
        dot(canvas, cx, cy, 0f, 0f, r * 0.05f, Gear.ON)
    }

    /** 時針（オフホワイト）と分針（黄色）を実線で描く。 */
    private fun solidHands(
        canvas: Canvas, cx: Float, cy: Float, r: Float, hour: Float, minute: Float,
        hourW: Float, minW: Float, hourLen: Float = 0.5f, minLen: Float = 0.82f,
    ) {
        line(canvas, cx, cy, hour * 30f, 0f, r * hourLen, hourW, HOUR_COLOR)
        line(canvas, cx, cy, minute * 6f, 0f, r * minLen, minW, MINUTE_COLOR)
        paint.style = Paint.Style.FILL
        paint.color = HOUR_COLOR
        canvas.drawCircle(cx, cy, hourW * 0.9f, paint)
    }

    /** 原点から (tx, ty) までの線分と点 (x, y) の距離。 */
    private fun segmentDistance(x: Float, y: Float, tx: Float, ty: Float): Float {
        val len2 = tx * tx + ty * ty
        val t = ((x * tx + y * ty) / len2).coerceIn(0f, 1f)
        return hypot(x - tx * t, y - ty * t)
    }

    /** 距離 0 で 1、[width] 離れるとほぼ 0 になるなめらかな山。 */
    private fun bump(d: Float, width: Float): Float {
        val q = d / width
        return exp(-q * q)
    }

    private fun shadow(radius: Float, color: Int) {
        if (radius > 0f) paint.setShadowLayer(radius, 0f, 0f, color) else paint.clearShadowLayer()
        if (radius > 0f) text.setShadowLayer(radius, 0f, 0f, color) else text.clearShadowLayer()
    }

    private fun hands(
        canvas: Canvas, cx: Float, cy: Float, r: Float,
        hour: Float, minute: Float, sec: Float,
        hourW: Float, minW: Float, color: Int, secColor: Int, glow: Float = 0f,
    ) {
        line(canvas, cx, cy, hour * 30f, 0f, r * 0.5f, hourW, color)
        line(canvas, cx, cy, minute * 6f, 0f, r * 0.75f, minW, color)
        if (glow > 0f) paint.setShadowLayer(glow, 0f, 0f, secColor)
        line(canvas, cx, cy, sec * 6f, -r * 0.12f, r * 0.85f, r * 0.018f, secColor)
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawCircle(cx, cy, hourW * 0.8f, paint)
    }

    private fun ticks(
        canvas: Canvas, cx: Float, cy: Float, r: Float,
        major: Float, minor: Float, majorW: Float, minorW: Float, color: Int,
    ) {
        for (i in 0 until 60) {
            val isMajor = i % 5 == 0
            line(canvas, cx, cy, i * 6f, r * (if (isMajor) major else minor), r,
                r * (if (isMajor) majorW else minorW), color)
        }
    }

    private fun numerals(canvas: Canvas, cx: Float, cy: Float, radius: Float, size: Float, color: Int, face: Typeface, labels: Array<String>) {
        text.color = color
        text.textSize = size
        text.typeface = face
        val baseline = -(text.ascent() + text.descent()) / 2
        for (i in 1..12) {
            val a = Math.toRadians(i * 30.0)
            canvas.drawText(labels[i - 1], cx + radius * sin(a).toFloat(), cy - radius * cos(a).toFloat() + baseline, text)
        }
    }

    private fun ring(canvas: Canvas, cx: Float, cy: Float, radius: Float, w: Float, color: Int) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = w
        paint.color = color
        canvas.drawCircle(cx, cy, radius, paint)
    }

    private fun dot(canvas: Canvas, cx: Float, cy: Float, deg: Float, dist: Float, radius: Float, color: Int) {
        val a = Math.toRadians(deg.toDouble())
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawCircle(cx + dist * sin(a).toFloat(), cy - dist * cos(a).toFloat(), radius, paint)
    }

    /** 中心から角度 [deg] の方向へ、[from] から [to] の距離まで線を引く。 */
    private fun line(canvas: Canvas, cx: Float, cy: Float, deg: Float, from: Float, to: Float, w: Float, color: Int) {
        val a = Math.toRadians(deg.toDouble())
        val sx = sin(a).toFloat()
        val sy = -cos(a).toFloat()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = w
        paint.color = color
        canvas.drawLine(cx + from * sx, cy + from * sy, cx + to * sx, cy + to * sy, paint)
    }

    private companion object {
        val ARABIC = Array(12) { (it + 1).toString() }
        /** ドット系の時計の針の色。時針と分針を見分けやすくする。 */
        val HOUR_COLOR = Gear.ON
        val MINUTE_COLOR = Gear.YELLOW

        val ROMAN_NUMERALS = arrayOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII")
    }
}

/** ゆっくり動く色の光でオーロラ風の背景を描く。 */
class AuroraView(context: Context, private val speed: Float = 1f) : View(context) {
    private val colors = intArrayOf(0xFF7C3AED.toInt(), 0xFF06B6D4.toInt(), 0xFFEC4899.toInt(), 0xFF22C55E.toInt())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val start = System.nanoTime()

    override fun onDraw(canvas: Canvas) {
        val t = (System.nanoTime() - start) / 1e9f * speed
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(0xFF0B0B1A.toInt())
        colors.forEachIndexed { i, c ->
            val phase = i * 1.7f
            val cx = w * (0.5f + 0.35f * sin(t * 0.12f + phase))
            val cy = h * (0.5f + 0.35f * cos(t * 0.09f + phase * 1.3f))
            val r = maxOf(w, h) * (0.45f + 0.1f * sin(t * 0.17f + phase))
            paint.shader = RadialGradient(cx, cy, r, c and 0x99FFFFFF.toInt(), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r, paint)
        }
        postInvalidateDelayed(66)
    }
}

/** 光の粒がふわふわ上昇していくアニメーション。 */
class ParticlesView(context: Context, private val speed: Float = 1f) : View(context) {
    private class P(var x: Float, var y: Float, val r: Float, val speed: Float, val drift: Float, val hue: Float)

    private val particles = mutableListOf<P>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var last = System.nanoTime()
    private var time = 0f

    private fun spawn(atBottom: Boolean) = P(
        x = Random.nextFloat() * width,
        y = if (atBottom) height + 20f else Random.nextFloat() * height,
        r = 3f + Random.nextFloat() * 10f,
        speed = 15f + Random.nextFloat() * 40f,
        drift = Random.nextFloat() * 6.28f,
        hue = 180f + Random.nextFloat() * 140f,
    )

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        particles.clear()
        repeat(70) { particles += spawn(atBottom = false) }
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = ((now - last) / 1e9f).coerceAtMost(0.2f) * speed
        last = now
        time += dt
        canvas.drawColor(0xFF050510.toInt())
        for (i in particles.indices) {
            val p = particles[i]
            p.y -= p.speed * dt
            p.x += sin(time + p.drift) * 15f * dt
            if (p.y < -20f) particles[i] = spawn(atBottom = true)
            val alpha = (0.5f + 0.5f * sin(time * 0.8f + p.drift)).coerceIn(0.2f, 1f)
            paint.color = Color.HSVToColor((alpha * 255).toInt(), floatArrayOf(p.hue, 0.6f, 1f))
            paint.setShadowLayer(p.r * 2, 0f, 0f, paint.color)
            canvas.drawCircle(p.x, p.y, p.r, paint)
        }
        postInvalidateDelayed(66)
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
