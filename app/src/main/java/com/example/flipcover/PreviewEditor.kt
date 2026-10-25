package com.example.flipcover

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Size
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout

/**
 * プレビュー枠にカバー画面の内容を実寸で組み立てて縮小表示し、
 * 時計とアプリ列をドラッグで移動、2本指で拡大・縮小できるようにする。
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

    private val scaleDetector = ScaleGestureDetector(frame.context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                // 何もない所でピンチしたときは時計を対象にする
                val v = target ?: content?.findViewWithTag<View>(CoverContent.TAG_CLOCK) ?: return false
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
                target = hit(root, x, y)
                pinch = 1f
                changed = false
                lastX = x
                lastY = y
            }
            MotionEvent.ACTION_MOVE -> if (!scaleDetector.isInProgress && e.pointerCount == 1) {
                target?.let { v ->
                    v.translationX += x - lastX
                    v.translationY += y - lastY
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
                if (changed) commit()
                target = null
            }
        }
        return true
    }

    /** 指の下にある時計かアプリ列を探す。小さくても掴めるよう判定を少し広げる。 */
    private fun hit(root: FrameLayout, x: Float, y: Float): View? {
        val slop = size.width * 0.04f
        for (i in root.childCount - 1 downTo 0) {
            val v = root.getChildAt(i)
            if (v.tag != CoverContent.TAG_CLOCK && v.tag != CoverContent.TAG_APPS) continue
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
            CoverContent.TAG_APPS -> {
                prefs.appsX = cx
                prefs.appsY = cy
                prefs.appsSize = (prefs.appsSize * pinch).coerceIn(0.05f, 0.25f)
            }
        }
        onChanged()
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
