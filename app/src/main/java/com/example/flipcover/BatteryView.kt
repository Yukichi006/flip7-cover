package com.example.flipcover

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.BatteryManager
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

/**
 * 本体と、接続中の Bluetooth 機器の電池残量を、横棒と % で小さく並べる。
 * 1 行目に名前、2 行目に棒と % を右寄せで描く。上から本体、Bluetooth 機器の順。
 *
 * Bluetooth 機器の残量は、機器が Android に報告している値を使う（イヤホンやヘッドホンの多くが対応）。
 * 報告しない Bluetooth LE 機器（Galaxy Fit など）は、[BleBattery] で機器に直接問い合わせる。
 * 読むには「付近のデバイス」の許可が要り、許可がなければ本体だけを表示する。
 */
@SuppressLint("ViewConstructor")
class BatteryView(context: Context) : View(context) {
    private class Row(val name: String, val level: Int, val charging: Boolean)

    private var rows = emptyList<Row>()
    private var phoneLevel = -1
    private var phoneCharging = false

    /** 残量が変わったときの通知で知った、機器ごとの残量（アドレス → %）。 */
    private val btLevels = mutableMapOf<String, Int>()

    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Gear.GRAY
        textAlign = Paint.Align.RIGHT
    }
    private val percent = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Gear.ON
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        // 数字の幅をそろえ、残量が変わっても棒の長さがずれないようにする
        fontFeatureSettings = "tnum"
    }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> readPhone(intent)
                ACTION_BT_BATTERY -> {
                    val device = intent.btDevice() ?: return
                    btLevels[device.address] = intent.getIntExtra(EXTRA_BT_BATTERY, -1)
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> intent.btDevice()?.let {
                    btLevels.remove(it.address)
                    BleBattery.forget(it)
                }
            }
            refresh()
        }
    }

    // 直接問い合わせた機器の残量が届いたら描き直す
    private val onBleLevel: () -> Unit = { refresh() }

    // 通知が来ない機器もあるので、ときどき読み直す
    private val poll = object : Runnable {
        override fun run() {
            refresh()
            postDelayed(this, POLL_MS)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply {
            addAction(ACTION_BT_BATTERY)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        // 電池の通知は登録した時点の状態がすぐに届く
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)?.let { readPhone(it) }
        BleBattery.addListener(onBleLevel)
        post(poll)
    }

    override fun onDetachedFromWindow() {
        runCatching { context.unregisterReceiver(receiver) }
        removeCallbacks(poll)
        BleBattery.removeListener(onBleLevel)
        super.onDetachedFromWindow()
    }

    private fun readPhone(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level >= 0 && scale > 0) phoneLevel = level * 100 / scale
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        phoneCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun refresh() {
        val list = mutableListOf<Row>()
        if (phoneLevel >= 0) list += Row("本体", phoneLevel, phoneCharging)
        list += bluetoothRows()
        rows = list.take(MAX_ROWS)
        invalidate()
    }

    /** 残量が分かる（＝接続中の）Bluetooth 機器。許可がなければ空。 */
    @SuppressLint("MissingPermission")
    private fun bluetoothRows(): List<Row> {
        if (!canReadBluetooth(context)) return emptyList()
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return emptyList()
        val bonded = runCatching { manager.adapter?.bondedDevices }.getOrNull().orEmpty()
        // Galaxy Fit などの Bluetooth LE 機器は、つながっていても Android に残量を報告しないので、直接問い合わせる
        val leConnected = runCatching { manager.getConnectedDevices(BluetoothProfile.GATT) }.getOrNull().orEmpty()
            .map { it.address }.toSet()
        return bonded.mapNotNull { d ->
            val reported = readBtLevel(d).takeIf { it in 0..100 } ?: btLevels[d.address]?.takeIf { it in 0..100 }
            val le = d.type == BluetoothDevice.DEVICE_TYPE_LE || d.type == BluetoothDevice.DEVICE_TYPE_DUAL
            val level = reported ?: if (le && (d.address in leConnected || isConnected(d))) {
                BleBattery.request(context, d)
                BleBattery.level(d)
            } else null
            level ?: return@mapNotNull null
            Row(runCatching { d.alias ?: d.name }.getOrNull() ?: d.address, level, false)
        }.sortedBy { it.name }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val rowH = w * ROW_RATIO
        label.textSize = rowH * 0.3f
        percent.textSize = rowH * 0.36f
        label.setShadowLayer(rowH * 0.12f, 0f, 0f, SHADOW)
        percent.setShadowLayer(rowH * 0.12f, 0f, 0f, SHADOW)
        val pctW = percent.measureText("100%")
        val gap = rowH * 0.14f
        val segH = rowH * 0.17f

        rows.forEachIndexed { i, row ->
            val top = i * rowH
            val name = if (row.charging) "${row.name}・充電中" else row.name
            canvas.drawText(TextUtils.ellipsize(name, label, w, TextUtils.TruncateAt.END).toString(),
                w, top + label.textSize, label)

            val baseline = top + label.textSize + percent.textSize * 1.1f
            canvas.drawText("${row.level}%", w, baseline, percent)

            // 棒は MPC のメーターのように、角丸の四角を SEGMENTS 個並べ、残量の分だけ点ける。
            // % の文字の高さの真ん中にそろえる
            val cy = baseline - percent.textSize * 0.36f
            val right = w - pctW - gap
            val cellGap = segH * 0.45f
            val cellW = (right - cellGap * (SEGMENTS - 1)) / SEGMENTS
            // 1% でも残っていれば 1 つは点ける
            val lit = ((row.level + 5) / 10).coerceAtLeast(if (row.level > 0) 1 else 0)
            val on = when {
                row.charging -> Gear.YELLOW
                row.level <= LOW -> Gear.ORANGE
                else -> Gear.ON
            }
            for (k in 0 until SEGMENTS) {
                val x = k * (cellW + cellGap)
                rect.set(x, cy - segH / 2, x + cellW, cy + segH / 2)
                bar.color = if (k < lit) on else Gear.OFF
                canvas.drawRoundRect(rect, segH * 0.25f, segH * 0.25f, bar)
            }
        }
    }

    companion object {
        /** 1 行の高さ（幅に対する割合）。 */
        const val ROW_RATIO = 0.2f

        /** 並べる行の最大数（本体 + Bluetooth 機器 3 つ）。 */
        const val MAX_ROWS = 4

        /** 棒を区切るセグメントの数（1 つで 10%）。 */
        private const val SEGMENTS = 10

        /** これ以下ならオレンジにして知らせる（%）。 */
        private const val LOW = 20

        private const val POLL_MS = 60_000L
        private val SHADOW = Color.argb(160, 0, 0, 0)

        // Bluetooth 機器の残量が変わったときにシステムが送る通知（公開 API ではないが広く使われている）
        private const val ACTION_BT_BATTERY = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        private const val EXTRA_BT_BATTERY = "android.bluetooth.device.extra.BATTERY_LEVEL"

        fun canReadBluetooth(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

        /** 機器が報告している残量。読めなければ -1。 */
        private fun readBtLevel(device: BluetoothDevice): Int =
            runCatching { BluetoothDevice::class.java.getMethod("getBatteryLevel").invoke(device) as Int }
                .getOrDefault(-1)

        /** 機器がつながっているか（公開 API ではないので、読めなければ false）。 */
        private fun isConnected(device: BluetoothDevice): Boolean =
            runCatching { BluetoothDevice::class.java.getMethod("isConnected").invoke(device) as Boolean }
                .getOrDefault(false)

        private fun Intent.btDevice(): BluetoothDevice? =
            IntentCompat.getParcelableExtra(this, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    }
}
