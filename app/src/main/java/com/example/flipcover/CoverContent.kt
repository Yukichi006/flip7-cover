package com.example.flipcover

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.Size
import android.util.SizeF
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextClock
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * カバー画面とプレビューで共通に使う表示内容を組み立てる。
 * 配置はカバー画面の実寸（px）で計算し、プレビューはそれを縮小して表示する。
 */
object CoverContent {
    const val TAG_CLOCK = "clock"
    /** アプリアイコンのタグの頭。後ろにアプリの名前（ComponentName）を付ける。 */
    const val TAG_APP_PREFIX = "app:"
    const val TAG_DIGITAL = "digital"
    const val TAG_LAUNCHER = "launcher"
    const val TAG_APP_GRID = "appGrid"
    const val TAG_TORCH = "torch"
    const val TAG_WIDGET = "widget"
    const val TAG_WIDGET_BUTTON = "widgetButton"
    const val TAG_BATTERY = "battery"

    /** カバー画面の実寸。取得できなければ Flip7 の値を使う。 */
    fun coverSize(display: Display?): Size {
        val mode = display?.mode
        return if (mode != null && mode.physicalWidth > 0) Size(mode.physicalWidth, mode.physicalHeight)
        else Size(948, 1048)
    }

    /**
     * [onLaunch] はアプリアイコンがタップされたときに呼ばれる（プレビューでは null）。
     * [onWidgetTap] はウィジェットがタップされたときに、そのウィジェットのアプリの名前（パッケージ名）で呼ばれる。
     * [editing] はプレビューで編集しているときに true にする。
     * [expandedPreview] はプレビューで、ボタンを開いたときの様子を見せるときに true にする。
     */
    fun create(
        context: Context,
        prefs: Prefs,
        size: Size,
        onLaunch: ((ComponentName) -> Unit)? = null,
        onWidgetTap: ((String) -> Unit)? = null,
        editing: Boolean = false,
        expandedPreview: Boolean = false,
    ): FrameLayout {
        val w = size.width
        val h = size.height
        val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        val speed = prefs.animSpeed
        // ドット系の時計は、背景のドットで描く（背景の選択より優先する）
        val integrated = prefs.mode == Mode.DOT_CLOCK || prefs.clockStyle.integrated
        val content: View = if (integrated) dotClock(context, prefs, size) else when (prefs.mode) {
            Mode.EARTH -> SpaceView(context, SpaceView.Planet.EARTH, speed)
            Mode.MOON -> SpaceView(context, SpaceView.Planet.MOON, speed)
            // ドット秒は時計そのものなので速さは変えない
            Mode.DOT_SECONDS -> DotSecondsView(context)
            Mode.LEVEL_METER -> LevelMeterView(context, speed)
            Mode.DOT_WAVE -> DotWaveView(context, speed)
            Mode.SEQUENCER -> SequencerView(context, speed)
            Mode.DOT_CLOCK -> dotClock(context, prefs, size)
        }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))

        if (prefs.showClock && integrated) {
            // 時計は背景が描くので、ここにはドラッグで動かすための透明な部品だけ置く
            val box = (prefs.clockSize * w).toInt()
            val handle = ClockHandle(context, editing).apply { tag = TAG_CLOCK }
            root.addView(handle, place(size, prefs.clockX, prefs.clockY, box, box))
        } else if (prefs.showClock) {
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
            addWithBackdrop(context, root, size, clock, box, boxH, TAG_CLOCK, prefs.clockX, prefs.clockY, prefs.clockBackdrop)
        }

        // アナログでおおよその時刻、こちらで秒まで確かめられるよう、別の部品として置く
        if (prefs.showDigital) {
            val dw = (prefs.digitalSize * w).toInt()
            addWithBackdrop(context, root, size, SecondsClockView(context), dw, (dw * 0.3f).toInt(),
                TAG_DIGITAL, prefs.digitalX, prefs.digitalY, prefs.clockBackdrop)
        }

        // 電池残量は常に出しておく情報なので、ボタンで開く部品より先（下）に置く
        if (prefs.showBattery) {
            val bw = (prefs.batterySize * w).toInt()
            val bh = (bw * BatteryView.ROW_RATIO * BatteryView.MAX_ROWS).toInt()
            root.addView(BatteryView(context).apply { tag = TAG_BATTERY }, place(size, prefs.batteryX, prefs.batteryY, bw, bh))
        }

        // ウィジェットとライトは、アプリを開いたときに暗い幕の下に隠れるよう、アプリより先に置く
        val showOpen = editing && expandedPreview
        val widgets = buildWidgets(context, size, prefs, editing, showOpen && prefs.useWidgetButton, onWidgetTap)
        var widgetButton: LauncherButton? = null
        if (widgets != null && !prefs.useWidgetButton) {
            root.addView(widgets.area, place(size, prefs.widgetX, prefs.widgetY, widgets.width, widgets.height))
        } else if (widgets != null) {
            val bs = (prefs.widgetButtonSize * w).toInt()
            widgetButton = LauncherButton(context, prefs.widgetButtonStyle).apply { tag = TAG_WIDGET_BUTTON }
            root.addView(widgetButton, place(size, prefs.widgetButtonX, prefs.widgetButtonY, bs, bs))
        }
        if (prefs.showTorch) {
            val tw = (prefs.torchSize * w).toInt()
            val torch = TorchSwitch(context, interactive = !editing).apply { tag = TAG_TORCH }
            root.addView(torch, place(size, prefs.torchX, prefs.torchY, tw, (tw * TORCH_ASPECT).toInt()))
        }

        addApps(context, root, size, prefs, onLaunch, editing, expandedPreview)

        // ボタンで開くウィジェットは、開いたときにアプリより手前に出るよう最後に置く
        if (widgets != null && widgetButton != null && (!editing || showOpen)) {
            // プレビューでアプリも開いて見せているときは、アプリのグリッドまで暗くしないよう幕を省く
            val appsOpen = prefs.useLauncher && prefs.apps.isNotEmpty()
            val scrim = if (!editing || !appsOpen) View(context).apply {
                setBackgroundColor(SCRIM_COLOR)
                root.addView(this, FrameLayout.LayoutParams(-1, -1))
            } else null
            root.addView(widgets.area, place(size, prefs.widgetX, prefs.widgetY, widgets.width, widgets.height))
            if (showOpen) widgetButton.showOpen()
            else if (scrim != null) AppLauncher(widgetButton, scrim, widgets.area, widgets.frames)
        }
        return root
    }

    /** マス目に並べたウィジェットと、それを入れる範囲（[width]×[height] px）。 */
    private class Widgets(val area: FrameLayout, val frames: List<View>, val width: Int, val height: Int)

    /**
     * ほかのアプリのウィジェット（Suica の残高など）を、範囲の中に 2 列のマス目で並べる。置いていなければ null。
     * ウィジェットの文字の大きさがカバー画面と同じになるよう、カバー画面の密度で組み立てる。
     * [outline] のときは、プレビューで範囲が分かるよう点線で囲む。
     */
    private fun buildWidgets(
        context: Context, size: Size, prefs: Prefs, editing: Boolean, outline: Boolean,
        onTap: ((String) -> Unit)?,
    ): Widgets? {
        val am = AppWidgetManager.getInstance(context)
        // ウィジェットのアプリがアンインストールされたものは飛ばす
        val widgets = prefs.widgetIds.mapNotNull { id -> am.getAppWidgetInfo(id)?.let { id to it } }
        if (widgets.isEmpty()) return null
        val cover = CoverOverlayService.findCoverDisplay(context.getSystemService(DisplayManager::class.java))
        val onCover = runCatching { context.display?.displayId == cover?.displayId }.getOrDefault(false)
        val widgetContext = if (cover != null && !onCover) context.createDisplayContext(cover) else context
        val density = widgetContext.resources.displayMetrics.density

        val aw = (prefs.widgetW * size.width).toInt()
        val ah = (prefs.widgetH * size.height).toInt()
        // 2×2 のウィジェットを 4 つ並べられるよう、2 列のマス目にする（1 つだけなら範囲いっぱい）
        val cols = if (widgets.size == 1) 1 else WIDGET_COLUMNS
        val rows = (widgets.size + cols - 1) / cols
        val gap = (size.width * 0.02f).toInt()
        val cellW = (aw - gap * (cols - 1)) / cols
        val cellH = (ah - gap * (rows - 1)) / rows
        val area = FrameLayout(context).apply {
            tag = TAG_WIDGET
            if (outline) background = GradientDrawable().apply {
                setStroke(4, 0x66FFFFFF, 14f, 10f)
                cornerRadius = 16f
            }
        }
        val frames = widgets.mapIndexedNotNull { i, (id, info) ->
            val view = runCatching { WidgetHost.get(context).createView(widgetContext, id, info) }.getOrNull()
                ?: return@mapIndexedNotNull null
            if (!editing) {
                // ウィジェットのアプリに表示する大きさを知らせ、合うレイアウトを選んでもらう
                runCatching { view.updateAppWidgetSize(Bundle(), listOf(SizeF(cellW / density, cellH / density))) }
            }
            WidgetFrame(context, editing) { onTap?.invoke(info.provider.packageName) }.also { frame ->
                frame.addView(view, FrameLayout.LayoutParams(-1, -1))
                area.addView(frame, FrameLayout.LayoutParams(cellW, cellH).apply {
                    gravity = Gravity.TOP or Gravity.START
                    leftMargin = (i % cols) * (cellW + gap)
                    topMargin = (i / cols) * (cellH + gap)
                })
            }
        }
        return Widgets(area, frames, aw, ah)
    }

    /** 背景と一体のドット時計。時計の位置はほかの部品と同じく画面内に収めた値を使う。 */
    private fun dotClock(context: Context, prefs: Prefs, size: Size): View {
        val box = (prefs.clockSize * size.width).toInt()
        val lp = place(size, prefs.clockX, prefs.clockY, box, box)
        val r = if (prefs.showClock) box / 2f else 0f
        val cx = lp.leftMargin + box / 2f
        val cy = lp.topMargin + box / 2f
        if (prefs.clockStyle.light) return LightDialView(context, cx, cy, r, prefs.glow)
        // 背景だけドット時計にして時計が別のデザインのときは、扇形にする
        val pattern = prefs.clockStyle.dial ?: DotDialView.Pattern.SECTOR
        return DotDialView(context, cx, cy, r, pattern, prefs.animSpeed,
            prefs.taperHands, prefs.glow, prefs.boldMinute, prefs.minuteTip)
    }

    /** ぼかした背景の分だけ枠を広げて [view] をその中央に置き、ドラッグ用の [tag] を付けて配置する。 */
    private fun addWithBackdrop(
        context: Context, root: FrameLayout, size: Size, view: View,
        vw: Int, vh: Int, tag: String, cx: Float, cy: Float, backdrop: Boolean,
    ) {
        val pad = if (backdrop) (min(vw, vh) * 0.18f).toInt().coerceAtLeast((vw * 0.12f).toInt()) else 0
        val holder = FrameLayout(context).apply {
            this.tag = tag
            if (backdrop) addView(SoftBackdrop(context), FrameLayout.LayoutParams(-1, -1))
            addView(view, FrameLayout.LayoutParams(vw, vh, Gravity.CENTER))
        }
        root.addView(holder, place(size, cx, cy, vw + pad * 2, vh + pad * 2))
    }

    /** 中心が (cx, cy)（画面に対する割合）になるよう配置する。画面からははみ出さない。 */
    private fun place(size: Size, cx: Float, cy: Float, vw: Int, vh: Int) =
        FrameLayout.LayoutParams(vw, vh).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = (cx * size.width - vw / 2f).toInt().coerceIn(0, maxOf(0, size.width - vw))
            topMargin = (cy * size.height - vh / 2f).toInt().coerceIn(0, maxOf(0, size.height - vh))
        }

    /**
     * カバー画面のアプリアイコンを置く。
     *
     * ボタンにまとめる設定なら、ふだんはボタンだけを見せ、タップで「グリッドの範囲」に
     * 4 列のアイコンを展開する。プレビューでは [expandedPreview] のときだけ展開した様子を見せ、
     * グリッドの範囲ごとドラッグ・ピンチで調整できるようにする。
     * まとめない設定なら、アイコンを 1 個ずつ自由な位置に常に表示する。
     */
    private fun addApps(
        context: Context, root: FrameLayout, size: Size, prefs: Prefs,
        onLaunch: ((ComponentName) -> Unit)?, editing: Boolean, expandedPreview: Boolean,
    ) {
        val pm = context.packageManager
        // アンインストール済みのアプリは飛ばす
        val apps = prefs.apps.mapNotNull { cn -> runCatching { pm.getActivityIcon(cn) }.getOrNull()?.let { cn to it } }
        if (apps.isEmpty()) return

        fun iconView(cn: ComponentName, icon: android.graphics.drawable.Drawable, iconSize: Int) = ImageView(context).apply {
            setImageDrawable(icon)
            contentDescription = runCatching { pm.getActivityInfo(cn, 0).loadLabel(pm) }.getOrNull()
            val pad = iconSize / 9
            setPadding(pad, pad, pad, pad)
            // プレビューではタップを奪わないよう、起動先があるときだけクリック可能にする
            if (onLaunch != null) setOnClickListener { onLaunch(cn) }
        }

        if (!prefs.useLauncher) {
            val iconSize = (prefs.appsSize * size.width).toInt()
            apps.forEachIndexed { i, (cn, icon) ->
                val pos = prefs.appPosition(cn)
                val x = pos?.x ?: (prefs.appsX + (i - (apps.size - 1) / 2f) * prefs.appsSize)
                val y = pos?.y ?: prefs.appsY
                val iv = iconView(cn, icon, iconSize).apply { tag = TAG_APP_PREFIX + cn.flattenToString() }
                root.addView(iv, place(size, x, y, iconSize, iconSize))
            }
            return
        }

        val showOpen = editing && expandedPreview
        // 開いたときにアイコンの後ろを暗くする幕
        val scrim = if (!editing || showOpen) View(context).apply {
            setBackgroundColor(SCRIM_COLOR)
            root.addView(this, FrameLayout.LayoutParams(-1, -1))
        } else null

        // グリッドの範囲にアイコンを 4 列で並べる
        val gw = (prefs.gridW * size.width).toInt()
        val gh = (prefs.gridH * size.height).toInt()
        val rows = (apps.size + GRID_COLUMNS - 1) / GRID_COLUMNS
        val cellW = gw / GRID_COLUMNS.toFloat()
        val cellH = gh / maxOf(rows, GRID_COLUMNS).toFloat()
        val iconSize = (min(cellW, cellH) * 0.8f).toInt()
        val grid = FrameLayout(context).apply {
            tag = TAG_APP_GRID
            if (showOpen) background = GradientDrawable().apply {
                setStroke(4, 0x66FFFFFF, 14f, 10f)
                cornerRadius = 16f
            }
        }
        val icons = apps.mapIndexed { i, (cn, icon) ->
            val col = i % GRID_COLUMNS
            val row = i / GRID_COLUMNS
            iconView(cn, icon, iconSize).also { iv ->
                grid.addView(iv, FrameLayout.LayoutParams(iconSize, iconSize).apply {
                    gravity = Gravity.TOP or Gravity.START
                    leftMargin = (cellW * (col + 0.5f) - iconSize / 2f).toInt()
                    topMargin = (cellH * (row + 0.5f) - iconSize / 2f).toInt()
                })
            }
        }
        if (!editing || showOpen) root.addView(grid, place(size, prefs.gridCx, prefs.gridCy, gw, gh))

        val buttonSize = (prefs.launcherSize * size.width).toInt()
        val button = LauncherButton(context, prefs.launcherStyle).apply { tag = TAG_LAUNCHER }
        root.addView(button, place(size, prefs.launcherX, prefs.launcherY, buttonSize, buttonSize))
        if (showOpen) button.showOpen()
        if (!editing && scrim != null) AppLauncher(button, scrim, grid, icons)
    }

    /** 展開したときのグリッドの列数。 */
    private const val GRID_COLUMNS = 4

    /** ウィジェットを並べる列の数。 */
    private const val WIDGET_COLUMNS = 2

    /** ボタンで開いたときに後ろを暗くする幕の色。 */
    private const val SCRIM_COLOR = 0xB01C1C1A.toInt()

    /** ライトのスイッチの高さ（幅に対する割合）。 */
    private const val TORCH_ASPECT = 0.36f
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
            else -> {
                shadow(r * 0.03f, Color.BLACK)
                ticks(canvas, cx, cy, r, major = 0.82f, minor = 0.9f, majorW = 0.045f, minorW = 0.018f, color = Color.WHITE)
                hands(canvas, cx, cy, r, hour, minute, sec, r * 0.07f, r * 0.05f, Color.WHITE, 0xFFF472B6.toInt())
            }
        }

        // 秒針が動いて見える程度に、描き直しは控えめにする
        postInvalidateDelayed(250)
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
        val ROMAN_NUMERALS = arrayOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII")
    }
}
