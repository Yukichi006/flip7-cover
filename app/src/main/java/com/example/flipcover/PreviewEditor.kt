package com.example.flipcover

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * プレビュー枠にカバー画面の内容を実寸で組み立てて縮小表示し、
 * 時計とアプリ列をドラッグで移動、2本指で拡大・縮小できるようにする。
 * 移動中は中央・3分割・4分割の線や、もう一方の部品の中心に吸い付く。
 * 指を離したときに設定へ保存し、[onChanged] で再描画を依頼する。
 */
@SuppressLint("ClickableViewAccessibility")
class PreviewEditor(
    private val frame: FrameLayout,
    private val prefs: Prefs,
    private val onChanged: () -> Unit,
) {
    private var content: FrameLayout? = null
    private var size = Size(948, 1048)
    private var scale = 1f

    private var target: View? = null
    private var lastX = 0f
    private var lastY = 0f
    private var pinch = 1f
    private var changed = false

    // ドラッグ中の中心位置（吸い付く前の値）と、表示中のガイド
    private var rawCx = 0f
    private var rawCy = 0f
    private var guide: GridGuide? = null

    private val scaleDetector = ScaleGestureDetector(frame.context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                // 何もない所でピンチしたときは時計を対象にする
                val v = target
                    ?: content?.findViewWithTag<View>(CoverContent.TAG_CLOCK)?.also {
                        rawCx = centerX(it)
                        rawCy = centerY(it)
                    }
                    ?: return false
                target = v
                pinch = (pinch * d.scaleFactor).coerceIn(0.3f, 3f)
                v.scaleX = pinch
                v.scaleY = pinch
                changed = true
                return true
            }
        })

    init {
        frame.setOnTouchListener { _, e -> onTouch(e) }
    }

    fun show(view: FrameLayout, coverSize: Size, cutouts: List<Rect>) {
        frame.removeAllViews()
        size = coverSize
        content = view
        if (cutouts.isNotEmpty()) view.addView(CutoutGuide(frame.context, cutouts), FrameLayout.LayoutParams(-1, -1))
        guide = GridGuide(frame.context, GRID).also { view.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        frame.addView(view, FrameLayout.LayoutParams(size.width, size.height))
        // 初回はまだ枠の大きさが決まっていないので、レイアウト後にも合わせ直す
        if (frame.width > 0) applyScale()
        frame.post { applyScale() }
    }

    private fun applyScale() {
        scale = minOf(frame.width / size.width.toFloat(), frame.height / size.height.toFloat())
        content?.apply {
            pivotX = 0f
            pivotY = 0f
            scaleX = scale
            scaleY = scale
        }
    }

    private fun onTouch(e: MotionEvent): Boolean {
        val root = content ?: return false
        if (scale <= 0f) return false
        scaleDetector.onTouchEvent(e)
        val x = e.x / scale
        val y = e.y / scale
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 編集中は外側の ScrollView にスクロールさせない
                frame.parent.requestDisallowInterceptTouchEvent(true)
                target = hit(root, x, y)?.also { v ->
                    rawCx = centerX(v)
                    rawCy = centerY(v)
                    guide?.dragging = true
                }
                pinch = 1f
                changed = false
                lastX = x
                lastY = y
            }
            MotionEvent.ACTION_MOVE -> if (!scaleDetector.isInProgress && e.pointerCount == 1) {
                target?.let { v ->
                    rawCx += x - lastX
                    rawCy += y - lastY
                    move(root, v)
                    changed = true
                }
                lastX = x
                lastY = y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // 2本指から1本指に戻ったとき、残った指の位置から動かし直す
                val i = if (e.actionIndex == 0) 1 else 0
                lastX = e.getX(i) / scale
                lastY = e.getY(i) / scale
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                guide?.apply { dragging = false; snapX = null; snapY = null }
                if (changed) commit()
                target = null
            }
        }
        return true
    }

    private fun centerX(v: View) = v.left + v.translationX + v.width / 2f
    private fun centerY(v: View) = v.top + v.translationY + v.height / 2f

    /** 指の動きに合わせて動かし、近くのガイド線があればそこへ吸い付ける。 */
    private fun move(root: FrameLayout, v: View) {
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        // ほかの部品の中心にも揃えられるようにする
        val others = (0 until root.childCount).map { root.getChildAt(it) }
            .filter { it !== v && it.tag in MOVABLE }
        val xs = GRID.map { it * w } + others.map { centerX(it) }
        val ys = GRID.map { it * h } + others.map { centerY(it) }

        val sx = nearest(rawCx, xs, w * SNAP)
        val sy = nearest(rawCy, ys, w * SNAP)
        v.translationX = (sx ?: rawCx) - (v.left + v.width / 2f)
        v.translationY = (sy ?: rawCy) - (v.top + v.height / 2f)

        val g = guide ?: return
        if ((sx != null && sx != g.snapX) || (sy != null && sy != g.snapY)) {
            frame.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        g.snapX = sx
        g.snapY = sy
    }

    private fun nearest(value: Float, targets: List<Float>, threshold: Float): Float? =
        targets.minByOrNull { abs(it - value) }?.takeIf { abs(it - value) <= threshold }

    /** 指の下にある時計かアプリ列を探す。小さくても掴めるよう判定を少し広げる。 */
    private fun hit(root: FrameLayout, x: Float, y: Float): View? {
        val slop = size.width * 0.04f
        for (i in root.childCount - 1 downTo 0) {
            val v = root.getChildAt(i)
            if (v.tag !in MOVABLE) continue
            val l = v.left + v.translationX - slop
            val t = v.top + v.translationY - slop
            if (x >= l && x <= l + v.width + slop * 2 && y >= t && y <= t + v.height + slop * 2) return v
        }
        return null
    }

    private fun commit() {
        val v = target ?: return
        val cx = ((v.left + v.translationX + v.width / 2f) / size.width).coerceIn(0f, 1f)
        val cy = ((v.top + v.translationY + v.height / 2f) / size.height).coerceIn(0f, 1f)
        when (v.tag) {
            CoverContent.TAG_CLOCK -> {
                prefs.clockX = cx
                prefs.clockY = cy
                prefs.clockSize = (prefs.clockSize * pinch).coerceIn(0.1f, 1f)
            }
            CoverContent.TAG_DIGITAL -> {
                prefs.digitalX = cx
                prefs.digitalY = cy
                prefs.digitalSize = (prefs.digitalSize * pinch).coerceIn(0.15f, 1f)
            }
            CoverContent.TAG_APPS -> {
                prefs.appsX = cx
                prefs.appsY = cy
                prefs.appsSize = (prefs.appsSize * pinch).coerceIn(0.05f, 0.25f)
            }
        }
        onChanged()
    }

    private companion object {
        /** ドラッグで動かせる部品。 */
        val MOVABLE: Set<Any?> = setOf(CoverContent.TAG_CLOCK, CoverContent.TAG_DIGITAL, CoverContent.TAG_APPS)

        /** 吸い付く位置（画面に対する割合）。 */
        val GRID = listOf(0.25f, 1 / 3f, 0.5f, 2 / 3f, 0.75f)

        /** 吸い付く距離（画面幅に対する割合）。 */
        const val SNAP = 0.025f
    }
}

/** ドラッグ中に格子線を薄く表示し、吸い付いた線を強調する。 */
private class GridGuide(context: Context, private val grid: List<Float>) : View(context) {
    var dragging = false
        set(v) { field = v; invalidate() }
    var snapX: Float? = null
        set(v) { if (field != v) { field = v; invalidate() } }
    var snapY: Float? = null
        set(v) { if (field != v) { field = v; invalidate() } }

    private val faint = Paint().apply { color = 0x40FFFFFF }
    private val strong = Paint().apply { color = 0xFF22D3EE.toInt() }

    override fun onDraw(canvas: Canvas) {
        if (!dragging) return
        val w = width.toFloat()
        val h = height.toFloat()
        faint.strokeWidth = w * 0.004f
        strong.strokeWidth = w * 0.007f
        for (f in grid) {
            canvas.drawLine(f * w, 0f, f * w, h, faint)
            canvas.drawLine(0f, f * h, w, f * h, faint)
        }
        snapX?.let { canvas.drawLine(it, 0f, it, h, strong) }
        snapY?.let { canvas.drawLine(0f, it, w, it, strong) }
    }
}

/** カメラ穴などの切り欠き位置をプレビュー上に示す。 */
private class CutoutGuide(context: Context, private val rects: List<Rect>) : View(context) {
    private val fill = Paint().apply { color = 0x66FF4D4D }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    override fun onDraw(canvas: Canvas) {
        for (r in rects) {
            canvas.drawRect(r, fill)
            canvas.drawRect(r, stroke)
        }
    }
}
