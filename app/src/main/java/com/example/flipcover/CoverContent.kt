package com.example.flipcover

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.Size
import android.util.SizeF
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.min

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
        root.addView(lightDial(context, prefs, size), FrameLayout.LayoutParams(-1, -1))

        if (prefs.showClock) {
            // 時計は背景が描くので、ここにはドラッグで動かすための透明な部品だけ置く
            val box = (prefs.clockSize * w).toInt()
            val handle = ClockHandle(context, editing).apply { tag = TAG_CLOCK }
            root.addView(handle, place(size, prefs.clockX, prefs.clockY, box, box))
        }

        // 光の時計でおおよその時刻、こちらで秒まで確かめられるよう、別の部品として置く
        if (prefs.showDigital) {
            val dw = (prefs.digitalSize * w).toInt()
            // ステッチの見た目では、縫った数字にし、後ろのぼかしは敷かない（布地が見えるように）
            val digital = if (prefs.stitch) StitchSecondsClockView(context) else SecondsClockView(context)
            addWithBackdrop(context, root, size, digital, dw, (dw * 0.3f).toInt(),
                TAG_DIGITAL, prefs.digitalX, prefs.digitalY, prefs.clockBackdrop && !prefs.stitch)
        }

        // 電池残量は常に出しておく情報なので、ボタンで開く部品より先（下）に置く
        if (prefs.showBattery) {
            val bw = (prefs.batterySize * w).toInt()
            val bh = (bw * BatteryView.ROW_RATIO * BatteryView.MAX_ROWS).toInt()
            root.addView(BatteryView(context, stitch = prefs.stitch).apply { tag = TAG_BATTERY }, place(size, prefs.batteryX, prefs.batteryY, bw, bh))
        }

        // ウィジェットとライトは、アプリを開いたときに暗い幕の下に隠れるよう、アプリより先に置く
        val showOpen = editing && expandedPreview
        // ウィジェットに触っている間は開いたままにする（あとで作るので、いったん null）
        var widgetLauncher: AppLauncher? = null
        val widgets = buildWidgets(context, size, prefs, editing, showOpen && prefs.useWidgetButton,
            onTouch = { widgetLauncher?.keepOpen() }, onTap = onWidgetTap)
        var widgetButton: LauncherButton? = null
        if (widgets != null && !prefs.useWidgetButton) {
            root.addView(widgets.area, place(size, prefs.widgetX, prefs.widgetY, widgets.width, widgets.height))
        } else if (widgets != null) {
            val bs = (prefs.widgetButtonSize * w).toInt()
            widgetButton = LauncherButton(context, prefs.widgetButtonStyle, stitch = prefs.stitch, widget = true).apply { tag = TAG_WIDGET_BUTTON }
            root.addView(widgetButton, place(size, prefs.widgetButtonX, prefs.widgetButtonY, bs, bs))
        }
        if (prefs.showTorch) {
            val tw = (prefs.torchSize * w).toInt()
            val torch = TorchSwitch(context, interactive = !editing, stitch = prefs.stitch).apply { tag = TAG_TORCH }
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
            // 更新ボタンを押して結果を待つこともあるので、アプリより長く開いておく
            else if (scrim != null) widgetLauncher = AppLauncher(widgetButton, scrim, widgets.area, widgets.frames, WIDGET_AUTO_CLOSE_MS)
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
        onTouch: () -> Unit, onTap: ((String) -> Unit)?,
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
            // 標準ではウィジェットの周りに余白が付き、角を丸めても余白の角が丸まるだけになる。
            // ホーム画面と同じように余白をなくし、ウィジェットそのものの角を丸める
            view.setPadding(0, 0, 0, 0)
            // ウィジェットは本来の大きさ（ホーム画面で想定している大きさ）より小さいと、はみ出た所が切れてしまう。
            // そこで、マスより小さくならない大きさで組み立ててから、マスに収まるよう縮小する
            val sysDensity = Resources.getSystem().displayMetrics.density
            val naturalW = maxOf(info.minWidth / sysDensity, info.targetCellWidth * HOME_CELL_DP) * density
            val naturalH = maxOf(info.minHeight / sysDensity, info.targetCellHeight * HOME_CELL_DP) * density
            val lw = maxOf(cellW.toFloat(), naturalW)
            val lh = maxOf(cellH.toFloat(), naturalH)
            val scale = minOf(cellW / lw, cellH / lh)
            if (!editing) {
                // ウィジェットのアプリに表示する大きさを知らせ、合うレイアウトを選んでもらう
                runCatching { view.updateAppWidgetSize(Bundle(), listOf(SizeF(lw / density, lh / density))) }
            }
            view.scaleX = scale
            view.scaleY = scale
            // ホーム画面と同じように、ウィジェットの角を丸く切り抜く。
            // Galaxy ではシステムの値が 0 のことがあるので、そのときはホーム画面に近い丸さにする
            val radius = runCatching {
                widgetContext.resources.getDimension(android.R.dimen.system_app_widget_background_radius)
            }.getOrNull()?.takeIf { it > 0f } ?: (WIDGET_CORNER_DP * density)
            view.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) = outline.setRoundRect(0, 0, v.width, v.height, radius)
            }
            view.clipToOutline = true
            WidgetFrame(context, editing, onTouch) { onTap?.invoke(info.provider.packageName) }.also { frame ->
                frame.addView(view, FrameLayout.LayoutParams(lw.toInt(), lh.toInt(), Gravity.CENTER))
                area.addView(frame, FrameLayout.LayoutParams(cellW, cellH).apply {
                    gravity = Gravity.TOP or Gravity.START
                    leftMargin = (i % cols) * (cellW + gap)
                    topMargin = (i / cols) * (cellH + gap)
                })
            }
        }
        return Widgets(area, frames, aw, ah)
    }

    /** 背景と一体の光の時計。時計の位置はほかの部品と同じく画面内に収めた値を使う。 */
    private fun lightDial(context: Context, prefs: Prefs, size: Size): View {
        val box = (prefs.clockSize * size.width).toInt()
        val lp = place(size, prefs.clockX, prefs.clockY, box, box)
        val r = if (prefs.showClock) box / 2f else 0f
        if (prefs.stitch) return StitchDialView(context, lp.leftMargin + box / 2f, lp.topMargin + box / 2f, r)
        return LightDialView(context, lp.leftMargin + box / 2f, lp.topMargin + box / 2f, r, prefs.glow)
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

        fun iconView(cn: ComponentName, icon: android.graphics.drawable.Drawable, iconSize: Int): View {
            val label = runCatching { pm.getActivityInfo(cn, 0).loadLabel(pm) }.getOrNull()
            // ステッチの見た目では、ワッペンに刺繍のジャンルアイコンを縫ったものにする
            val v = if (prefs.stitch) {
                val category = runCatching { pm.getApplicationInfo(cn.packageName, 0).category }.getOrDefault(-1)
                StitchAppIcon(context, Genre.guess(context, cn.packageName, label?.toString().orEmpty(), category), label?.toString().orEmpty())
            } else ImageView(context).apply {
                setImageDrawable(icon)
                val pad = iconSize / 9
                setPadding(pad, pad, pad, pad)
            }
            return v.apply {
                contentDescription = label
                // プレビューではタップを奪わないよう、起動先があるときだけクリック可能にする
                if (onLaunch != null) setOnClickListener { onLaunch(cn) }
            }
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
        val button = LauncherButton(context, prefs.launcherStyle, stitch = prefs.stitch).apply { tag = TAG_LAUNCHER }
        root.addView(button, place(size, prefs.launcherX, prefs.launcherY, buttonSize, buttonSize))
        if (showOpen) button.showOpen()
        if (!editing && scrim != null) AppLauncher(button, scrim, grid, icons)
    }

    /** 展開したときのグリッドの列数。 */
    private const val GRID_COLUMNS = 4

    /** ボタンで開いたウィジェットが、触られないまま自動で閉じるまでの時間。 */
    private const val WIDGET_AUTO_CLOSE_MS = 30_000L

    /** ウィジェットの角の丸さ（dp）。システムに値がないときに使う。 */
    private const val WIDGET_CORNER_DP = 20f

    /** ホーム画面の 1 マスのおよその大きさ（dp）。ウィジェットの本来の大きさを見積もるのに使う。 */
    private const val HOME_CELL_DP = 90f

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
