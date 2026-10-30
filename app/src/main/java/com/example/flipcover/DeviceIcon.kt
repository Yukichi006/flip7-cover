package com.example.flipcover

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** 電池残量の行に付ける、機器の種類の小さな線画アイコン（Android の Bluetooth 設定のものに似せる）。 */
enum class DeviceKind {
    PHONE, WATCH, HEADPHONES, RING, KEYBOARD, OTHER;

    companion object {
        /**
         * 機器が名乗る種類から決める。種類を名乗らない機器（ペアリングしていないリングなど）は名前から推し量る。
         */
        @SuppressLint("MissingPermission")
        fun of(device: BluetoothDevice, name: String): DeviceKind {
            val n = name.lowercase()
            if (RING_WORDS.any { it in n }) return RING
            when (runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull()) {
                BluetoothClass.Device.Major.AUDIO_VIDEO -> return HEADPHONES
                BluetoothClass.Device.Major.WEARABLE, BluetoothClass.Device.Major.HEALTH -> return WATCH
                BluetoothClass.Device.Major.PERIPHERAL -> return KEYBOARD
                BluetoothClass.Device.Major.PHONE -> return PHONE
            }
            return when {
                WATCH_WORDS.any { it in n } -> WATCH
                AUDIO_WORDS.any { it in n } -> HEADPHONES
                else -> OTHER
            }
        }

        private val RING_WORDS = listOf("ring", "evofit", "oura")
        private val WATCH_WORDS = listOf("watch", "fit", "band")
        private val AUDIO_WORDS = listOf("buds", "tws", "ear", "headphone", "headset", "speaker", "airpods")
    }
}

/** [kind] のアイコンを、[cx], [cy] を中心とした一辺 [s] の正方形に描く。 */
fun drawDeviceIcon(canvas: Canvas, kind: DeviceKind, cx: Float, cy: Float, s: Float, paint: Paint) {
    val rect = RectF()
    val left = cx - s / 2
    val top = cy - s / 2
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = s * 0.09f
    paint.strokeCap = Paint.Cap.ROUND
    paint.strokeJoin = Paint.Join.ROUND
    when (kind) {
        DeviceKind.PHONE -> {
            rect.set(cx - s * 0.24f, top + s * 0.06f, cx + s * 0.24f, top + s * 0.94f)
            canvas.drawRoundRect(rect, s * 0.1f, s * 0.1f, paint)
            canvas.drawLine(cx - s * 0.07f, top + s * 0.8f, cx + s * 0.07f, top + s * 0.8f, paint)
        }
        DeviceKind.WATCH -> {
            // 縦長の本体と、上下のバンド
            rect.set(cx - s * 0.22f, top + s * 0.2f, cx + s * 0.22f, top + s * 0.8f)
            canvas.drawRoundRect(rect, s * 0.1f, s * 0.1f, paint)
            canvas.drawLine(cx - s * 0.12f, top + s * 0.2f, cx - s * 0.12f, top + s * 0.04f, paint)
            canvas.drawLine(cx + s * 0.12f, top + s * 0.2f, cx + s * 0.12f, top + s * 0.04f, paint)
            canvas.drawLine(cx - s * 0.12f, top + s * 0.8f, cx - s * 0.12f, top + s * 0.96f, paint)
            canvas.drawLine(cx + s * 0.12f, top + s * 0.8f, cx + s * 0.12f, top + s * 0.96f, paint)
        }
        DeviceKind.HEADPHONES -> {
            rect.set(left + s * 0.12f, top + s * 0.12f, left + s * 0.88f, top + s * 0.88f)
            canvas.drawArc(rect, 180f, 180f, false, paint)
            canvas.drawLine(left + s * 0.12f, cy, left + s * 0.12f, top + s * 0.72f, paint)
            canvas.drawLine(left + s * 0.88f, cy, left + s * 0.88f, top + s * 0.72f, paint)
            rect.set(left + s * 0.06f, top + s * 0.58f, left + s * 0.3f, top + s * 0.9f)
            canvas.drawRoundRect(rect, s * 0.08f, s * 0.08f, paint)
            rect.set(left + s * 0.7f, top + s * 0.58f, left + s * 0.94f, top + s * 0.9f)
            canvas.drawRoundRect(rect, s * 0.08f, s * 0.08f, paint)
        }
        DeviceKind.RING -> {
            // 斜めから見た指輪：楕円の輪と、上の小さなセンサー
            rect.set(left + s * 0.12f, top + s * 0.3f, left + s * 0.88f, top + s * 0.94f)
            canvas.drawOval(rect, paint)
            rect.set(cx - s * 0.12f, top + s * 0.1f, cx + s * 0.12f, top + s * 0.3f)
            canvas.drawRoundRect(rect, s * 0.05f, s * 0.05f, paint)
        }
        DeviceKind.KEYBOARD -> {
            rect.set(left + s * 0.04f, top + s * 0.24f, left + s * 0.96f, top + s * 0.76f)
            canvas.drawRoundRect(rect, s * 0.08f, s * 0.08f, paint)
            for (k in 0 until 4) {
                val x = left + s * (0.24f + k * 0.17f)
                canvas.drawPoint(x, top + s * 0.42f, paint)
            }
            canvas.drawLine(left + s * 0.3f, top + s * 0.6f, left + s * 0.7f, top + s * 0.6f, paint)
        }
        DeviceKind.OTHER -> {
            // Bluetooth のマーク
            val path = Path().apply {
                moveTo(cx - s * 0.2f, top + s * 0.3f)
                lineTo(cx + s * 0.2f, top + s * 0.68f)
                lineTo(cx, top + s * 0.88f)
                lineTo(cx, top + s * 0.12f)
                lineTo(cx + s * 0.2f, top + s * 0.32f)
                lineTo(cx - s * 0.2f, top + s * 0.7f)
            }
            canvas.drawPath(path, paint)
        }
    }
}
