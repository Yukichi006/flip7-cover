package com.example.flipcover

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
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
 * 人工衛星から見下ろしたような構図で、地球や月がゆっくり動くアニメーション。
 * 惑星を画面の左下に大きく置き、右上に向かって地平線が弧を描く。
 * 画像は NASA の Blue Marble（地球）と Apollo 撮影の月面写真。地球の雲は tools/make_clouds.py で生成。
 *
 * 画面の各画素が惑星のどこに当たるかは最初に一度だけ計算し、
 * 毎フレームは経度をずらして色を拾い直すだけにして軽くしている。
 */
class SpaceView(context: Context, private val planet: Planet) : View(context) {
    enum class Planet { EARTH, MOON }

    private val earth = planet == Planet.EARTH
    private val surface = Texture.load(context, if (earth) "earth_day.jpg" else "moon.jpg")
    private val clouds = if (earth) Texture.load(context, "clouds.jpg") else null

    // 実際の表示より粗い作業用の画像に描いてから拡大する
    private var bw = 0
    private var bh = 0
    private var bitmap: Bitmap? = null
    private var out = IntArray(0)

    // 惑星が写っている画素ごとの前計算
    private var pixelIndex = IntArray(0)
    private var surfaceRow = IntArray(0)
    private var cloudRow = IntArray(0)
    private var lonBase = FloatArray(0)  // 地球は 0〜1 の割合、月はラジアン
    private var light = IntArray(0)
    private var haze = IntArray(0)
    private var alpha = IntArray(0)

    private val dest = Rect()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private class Star(val x: Float, val y: Float, val r: Float, val base: Float, val phase: Float, val speed: Float)
    private var stars = emptyList<Star>()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w == 0 || h == 0) return
        val rnd = Random(7)
        stars = List(90) {
            Star(
                x = rnd.nextFloat() * w, y = rnd.nextFloat() * h,
                r = 0.5f + rnd.nextFloat().pow(3) * 1.6f,
                base = 0.15f + rnd.nextFloat() * 0.5f,
                phase = rnd.nextFloat() * 6.28f, speed = 0.4f + rnd.nextFloat() * 1.4f,
            )
        }
        dest.set(0, 0, w, h)
        prepare(w, h)
    }

    /** 画面の各画素について、惑星上の緯度・経度と明るさを前もって計算する。 */
    private fun prepare(w: Int, h: Int) {
        val scale = RENDER_SCALE
        bw = (w * scale).toInt().coerceAtLeast(1)
        bh = (h * scale).toInt().coerceAtLeast(1)
        bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        out = IntArray(bw * bh)

        // 惑星の位置と大きさ（画面幅に対する割合）。左下の画面外に中心を置く
        val radius = 1.55 * w
        val cx = -0.42 * w
        val cy = 1.62 * w
        val m = orientation()
        val sun = if (earth) norm(0.30, 0.45, 0.84) else norm(-0.2, 0.5, 0.84)
        val glowWidth = 0.012 * w

        val max = bw * bh
        val idx = IntArray(max)
        val sRow = IntArray(max)
        val cRow = IntArray(max)
        val lon0 = FloatArray(max)
        val li = IntArray(max)
        val hz = IntArray(max)
        val al = IntArray(max)
        var count = 0
        for (j in 0 until bh) for (i in 0 until bw) {
            val nx = ((i + 0.5) / scale - cx) / radius
            val ny = -((j + 0.5) / scale - cy) / radius
            val r = sqrt(nx * nx + ny * ny)
            if (r >= 1) {
                // 大気の縁の光は動かないので、ここで一度だけ描いておく
                if (earth) {
                    val g = exp(-(r - 1) * radius / glowWidth) * 0.9
                    if (g > 0.004) out[j * bw + i] = ((g * 255).toInt() shl 24) or 0x286EFF
                }
                continue
            }
            val nz = sqrt(1 - r * r)
            // 画面上の向きを惑星の座標に直す
            val px = m[0] * nx + m[1] * ny + m[2] * nz
            val py = m[3] * nx + m[4] * ny + m[5] * nz
            val pz = m[6] * nx + m[7] * ny + m[8] * nz
            val lat = asin(py.coerceIn(-1.0, 1.0))
            val lon = atan2(px, pz)
            val v = (0.5 - lat / PI)

            idx[count] = j * bw + i
            sRow[count] = (v * surface.height).toInt().coerceIn(0, surface.height - 1) * surface.width
            clouds?.let { cRow[count] = (v * it.height).toInt().coerceIn(0, it.height - 1) * it.width }
            lon0[count] = if (earth) (lon / (2 * PI) + 0.5).toFloat() else lon.toFloat()
            val lambert = nx * sun[0] + ny * sun[1] + nz * sun[2]
            li[count] = ((lambert * (if (earth) 1.1 else 1.0) + (if (earth) 0.15 else 0.12)).coerceIn(0.0, 1.0) * 256).toInt()
            // 地平線に近いほど大気で白っぽく霞む
            hz[count] = if (earth) ((1 - nz).pow(2.2) * 1.15).coerceIn(0.0, 0.85).times(256).toInt() else 0
            al[count] = ((1 - r) * radius * scale).coerceIn(0.0, 1.0).times(255).toInt()
            count++
        }
        pixelIndex = idx.copyOf(count)
        surfaceRow = sRow.copyOf(count)
        cloudRow = cRow.copyOf(count)
        lonBase = lon0.copyOf(count)
        light = li.copyOf(count)
        haze = hz.copyOf(count)
        alpha = al.copyOf(count)
    }

    /**
     * 画面の向きから惑星の向きへの回転（3x3、行優先）。
     * 画面に写る範囲の中ほどを惑星上の決まった地点に合わせ、
     * 北を地平線の方向へ向けて、自転で地表が地平線に沿って流れるようにする。
     */
    private fun orientation(): DoubleArray {
        val a1 = norm(0.45, 0.6, 0.66)
        val t = doubleArrayOf(0.6, 0.8, 0.0)
        val d = dot(t, a1)
        val a2 = norm(t[0] - d * a1[0], t[1] - d * a1[1], t[2] - d * a1[2])
        val a3 = cross(a1, a2)

        // 地球はカリブ海あたり、月は表側の中央
        val la = if (earth) 20 * PI / 180 else 0.0
        val lo = if (earth) -75 * PI / 180 else 0.0
        val b1 = doubleArrayOf(cos(la) * sin(lo), sin(la), cos(la) * cos(lo))
        val b2 = doubleArrayOf(-sin(la) * sin(lo), cos(la), -sin(la) * cos(lo))
        val b3 = cross(b1, b2)

        // M = B · Aᵀ（A, B は各軸を列に並べた行列）
        val a = arrayOf(a1, a2, a3)
        val b = arrayOf(b1, b2, b3)
        return DoubleArray(9) { k ->
            val row = k / 3
            val col = k % 3
            (0 until 3).sumOf { b[it][row] * a[it][col] }
        }
    }

    private fun norm(x: Double, y: Double, z: Double): DoubleArray {
        val l = sqrt(x * x + y * y + z * z)
        return doubleArrayOf(x / l, y / l, z / l)
    }

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    override fun onDraw(canvas: Canvas) {
        // 時計の時刻を使い、表示し直しても続きから動くようにする
        val t = (System.currentTimeMillis() % 86_400_000L) / 1000.0
        canvas.drawColor(0xFF020206.toInt())
        val ts = (t % 3600).toFloat()
        for (s in stars) {
            starPaint.alpha = (s.base * (0.7f + 0.3f * sin(ts * s.speed + s.phase)) * 255).toInt().coerceIn(0, 255)
            canvas.drawCircle(s.x, s.y, s.r, starPaint)
        }

        val bmp = bitmap ?: return
        if (earth) renderEarth(t) else renderMoon(t)
        bmp.setPixels(out, 0, bw, 0, 0, bw, bh)
        canvas.drawBitmap(bmp, null, dest, bitmapPaint)

        // とてもゆっくりした動きなので、電池持ちを優先して 8fps 程度にする
        postInvalidateDelayed(125)
    }

    private fun renderEarth(t: Double) {
        val tex = surface.pixels
        val sw = surface.width
        val cl = clouds!!
        val cloudTex = cl.pixels
        val cw = cl.width
        // 15 分で 1 回転。雲は地表より少し速く流す
        val spin = (t / 900.0 % 1.0).toFloat()
        val cloudSpin = (t * 1.15 / 900.0 % 1.0).toFloat()
        for (k in pixelIndex.indices) {
            val u = lonBase[k]
            var su = u + spin
            if (su >= 1f) su -= 1f
            var cu = u + cloudSpin
            if (cu >= 1f) cu -= 1f
            val g = tex[surfaceRow[k] + min((su * sw).toInt(), sw - 1)]
            val c = cloudTex[cloudRow[k] + min((cu * cw).toInt(), cw - 1)] and 255
            val l = light[k]
            val h = haze[k]

            // 地表（少し暗め）→ 雲 → 大気のかすみ の順に重ねる
            var r = ((g shr 16 and 255) * l * 230) shr 16
            var gg = ((g shr 8 and 255) * l * 230) shr 16
            var b = ((g and 255) * l * 230) shr 16
            val cloudLit = l * c
            r = (r * (255 - c) + 245 * cloudLit / 256) / 255
            gg = (gg * (255 - c) + 248 * cloudLit / 256) / 255
            b = (b * (255 - c) + 255 * cloudLit / 256) / 255
            r = (r * (256 - h) + ((150 * h * l) shr 8)) shr 8
            gg = (gg * (256 - h) + ((190 * h * l) shr 8)) shr 8
            b = (b * (256 - h) + ((255 * h * l) shr 8)) shr 8
            out[pixelIndex[k]] = argb(alpha[k], r, gg, b)
        }
    }

    private fun renderMoon(t: Double) {
        val tex = surface.pixels
        val w = surface.width
        // 月のテクスチャは表側（経度 -90°〜90°）だけなので、その範囲を行き来しながら上空を進む
        val drift = (0.35 * sin(t * 2 * PI / 240)).toFloat()
        val toCol = w / PI.toFloat()
        for (k in pixelIndex.indices) {
            val col = ((lonBase[k] + drift) * toCol + w / 2f).toInt().coerceIn(0, w - 1)
            val d = tex[surfaceRow[k] + col]
            val l = light[k]
            // 写真のコントラストを少し上げる
            out[pixelIndex[k]] = argb(
                alpha[k],
                (contrast(d shr 16 and 255) * l) shr 8,
                (contrast(d shr 8 and 255) * l) shr 8,
                (contrast(d and 255) * l) shr 8,
            )
        }
    }

    private fun contrast(v: Int) = ((v - 128) * 115 / 100 + 128).coerceIn(0, 255)

    private fun argb(a: Int, r: Int, g: Int, b: Int) =
        (a shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private companion object {
        /** 作業用の画像の解像度（表示サイズに対する割合）。上げるほど細かく、重くなる。 */
        const val RENDER_SCALE = 0.6
    }
}
