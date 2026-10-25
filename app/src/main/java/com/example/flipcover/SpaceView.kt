package com.example.flipcover

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** 正距円筒図法の画像。何度も読み直さないよう、読み込んだものは使い回す。 */
private class Texture(val width: Int, val height: Int, val pixels: IntArray) {
    companion object {
        private val cache = HashMap<String, Texture>()

        fun load(context: Context, name: String): Texture = synchronized(cache) {
            cache.getOrPut(name) {
                val bmp = context.assets.open("space/$name").use { BitmapFactory.decodeStream(it) }
                val px = IntArray(bmp.width * bmp.height)
                bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                Texture(bmp.width, bmp.height, px).also { bmp.recycle() }
            }
        }
    }
}

/**
 * 星空の中で地球や月がゆっくり回るアニメーション。
 * 画像は NASA の Blue Marble / Black Marble（地球）と Apollo 撮影の月面写真。
 *
 * 球の各画素がテクスチャのどこに当たるかは最初に一度だけ計算し、
 * 毎フレームは経度をずらして色を拾い直すだけにして軽くしている。
 */
class SpaceView(context: Context, private val planet: Planet) : View(context) {
    enum class Planet { EARTH, MOON }

    private val day = Texture.load(context, if (planet == Planet.EARTH) "earth_day.jpg" else "moon.jpg")
    private val night = if (planet == Planet.EARTH) Texture.load(context, "earth_night.jpg") else null
    private val start = System.nanoTime()

    // 球を描く作業用の画像（実際の表示サイズより小さく作って拡大する）
    private var n = 0
    private var bitmap: Bitmap? = null
    private var out = IntArray(0)
    private var pixelIndex = IntArray(0)
    private var rowOffset = IntArray(0)
    private var baseCol = FloatArray(0)
    private var shade = IntArray(0)
    private var nightWeight = IntArray(0)
    private var alpha = IntArray(0)

    private val dest = RectF()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private class Star(val x: Float, val y: Float, val r: Float, val base: Float, val phase: Float, val speed: Float)
    private var stars = emptyList<Star>()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val rnd = Random(7)
        stars = List(260) {
            Star(
                x = rnd.nextFloat() * w, y = rnd.nextFloat() * h,
                r = 0.6f + rnd.nextFloat().pow(3) * 2.4f,
                base = 0.25f + rnd.nextFloat() * 0.75f,
                phase = rnd.nextFloat() * 6.28f, speed = 0.4f + rnd.nextFloat() * 1.6f,
            )
        }
        val diameter = min(w, h) * 0.8f
        dest.set(w / 2f - diameter / 2, h / 2f - diameter / 2, w / 2f + diameter / 2, h / 2f + diameter / 2)
        glowPaint.shader = RadialGradient(
            w / 2f, h / 2f, diameter / 2 * 1.12f,
            intArrayOf(Color.TRANSPARENT, 0x6640A0FF, 0x3340A0FF, Color.TRANSPARENT),
            floatArrayOf(0.86f, 0.9f, 0.94f, 1f), Shader.TileMode.CLAMP,
        )
        prepare(min(diameter.toInt(), 480).coerceAtLeast(64))
    }

    /** 球の各画素について、緯度・経度・明るさを前もって計算する。 */
    private fun prepare(size: Int) {
        n = size
        bitmap = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        out = IntArray(n * n)

        val earth = planet == Planet.EARTH
        val tilt = if (earth) 23.4 * PI / 180 else 0.0   // 自転軸の傾き
        val tip = if (earth) 12 * PI / 180 else 4 * PI / 180  // 北極を少しこちらへ向ける
        val sun = if (earth) norm(-0.80, 0.28, 0.53) else norm(-0.35, 0.25, 0.90)

        val max = n * n
        val idx = IntArray(max)
        val rows = IntArray(max)
        val cols = FloatArray(max)
        val sh = IntArray(max)
        val nw = IntArray(max)
        val al = IntArray(max)
        var count = 0
        for (j in 0 until n) for (i in 0 until n) {
            val nx = (i + 0.5) / n * 2 - 1
            val ny = 1 - (j + 0.5) / n * 2
            val r2 = nx * nx + ny * ny
            if (r2 >= 1) continue
            val nz = sqrt(1 - r2)
            // 画面上の点を、傾きを戻した惑星の座標に直す
            val x1 = nx * cos(tilt) + ny * sin(tilt)
            val y1 = -nx * sin(tilt) + ny * cos(tilt)
            val y2 = y1 * cos(tip) - nz * sin(tip)
            val z2 = y1 * sin(tip) + nz * cos(tip)
            val lat = asin(y2.coerceIn(-1.0, 1.0))
            val lon = atan2(x1, z2)

            val lambert = nx * sun[0] + ny * sun[1] + nz * sun[2]
            val row = ((0.5 - lat / PI) * day.height).toInt().coerceIn(0, day.height - 1)
            idx[count] = j * n + i
            rows[count] = row * day.width
            if (earth) {
                cols[count] = ((lon / (2 * PI) + 0.5) * day.width).toFloat()
                val s = (lambert * 1.3 + 0.04).coerceIn(0.0, 1.0).pow(0.7) * (0.75 + 0.25 * nz)
                sh[count] = (s * 256).toInt()
                nw[count] = ((-lambert * 3).coerceIn(0.0, 1.0) * 1.1 * 256).toInt()
            } else {
                // 月のテクスチャは表側（経度 -90°〜90°）だけ
                cols[count] = lon.toFloat()
                sh[count] = ((0.25 + 0.85 * lambert).coerceIn(0.0, 1.05) * 256).toInt()
            }
            al[count] = ((1 - sqrt(r2)) * n / 2).coerceIn(0.0, 1.0).times(255).toInt()
            count++
        }
        pixelIndex = idx.copyOf(count)
        rowOffset = rows.copyOf(count)
        baseCol = cols.copyOf(count)
        shade = sh.copyOf(count)
        nightWeight = nw.copyOf(count)
        alpha = al.copyOf(count)
    }

    private fun norm(x: Double, y: Double, z: Double): DoubleArray {
        val l = sqrt(x * x + y * y + z * z)
        return doubleArrayOf(x / l, y / l, z / l)
    }

    override fun onDraw(canvas: Canvas) {
        val t = (System.nanoTime() - start) / 1e9f
        canvas.drawColor(0xFF03030A.toInt())
        for (s in stars) {
            val a = (s.base * (0.75f + 0.25f * sin(t * s.speed + s.phase))).coerceIn(0f, 1f)
            starPaint.alpha = (a * 255).toInt()
            canvas.drawCircle(s.x, s.y, s.r, starPaint)
        }

        val bmp = bitmap ?: return
        renderSphere(t)
        bmp.setPixels(out, 0, n, 0, 0, n, n)
        if (planet == Planet.EARTH) canvas.drawCircle(dest.centerX(), dest.centerY(), dest.width() / 2 * 1.12f, glowPaint)
        canvas.drawBitmap(bmp, null, dest, bitmapPaint)

        // ゆっくりした動きなので 20fps 程度で十分
        postInvalidateDelayed(50)
    }

    private fun renderSphere(t: Float) {
        val w = day.width
        val tex = day.pixels
        val nightTex = night?.pixels
        if (planet == Planet.EARTH) {
            // 3 分で 1 回転
            val off = (t / 180f % 1f) * w
            for (k in pixelIndex.indices) {
                var c = (baseCol[k] + off).toInt()
                if (c >= w) c -= w
                val p = rowOffset[k] + c
                val d = tex[p]
                val s = shade[k]
                var r = ((d shr 16 and 255) * s) shr 8
                var g = ((d shr 8 and 255) * s) shr 8
                var b = ((d and 255) * s) shr 8
                val nw = nightWeight[k]
                if (nightTex != null && nw > 0) {
                    val l = nightTex[p]
                    r += ((l shr 16 and 255) * nw) shr 8
                    g += ((l shr 8 and 255) * nw) shr 8
                    b += ((l and 255) * nw) shr 8
                }
                out[pixelIndex[k]] = argb(alpha[k], r, g, b)
            }
        } else {
            // 月は同じ面を地球に向けたまま、左右にゆっくり揺れる（秤動）
            val sway = (0.22 * sin(t * 2 * PI / 40)).toFloat()
            val scale = w / PI.toFloat()
            for (k in pixelIndex.indices) {
                val c = ((baseCol[k] + sway) * scale + w / 2f).toInt().coerceIn(0, w - 1)
                val d = tex[rowOffset[k] + c]
                val s = shade[k]
                out[pixelIndex[k]] = argb(
                    alpha[k],
                    ((d shr 16 and 255) * s) shr 8,
                    ((d shr 8 and 255) * s) shr 8,
                    ((d and 255) * s) shr 8,
                )
            }
        }
    }

    private fun argb(a: Int, r: Int, g: Int, b: Int) =
        (a shl 24) or (min(r, 255) shl 16) or (min(g, 255) shl 8) or min(b, 255)
}
