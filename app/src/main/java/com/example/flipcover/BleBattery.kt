package com.example.flipcover

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.UUID

/**
 * Galaxy Fit などの、残量を Android に報告しない Bluetooth LE 機器に直接つなぎ、
 * 標準の「電池サービス」から残量を読む。読んだ値は機器ごとに覚えておき、しばらくは読み直さない。
 * 電池サービスを持たない機器では何も起きない（その機器は表示しない）。
 */
@SuppressLint("MissingPermission")
object BleBattery {
    private val SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    private val LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    /** 読み直すまでの間隔。腕時計などの電池を減らさないよう長めにする。 */
    private const val REFRESH_MS = 10 * 60_000L

    /** つながらない・答えがないときに諦めるまでの時間。 */
    private const val TIMEOUT_MS = 20_000L

    private class Reading(val level: Int, val at: Long)

    private val main = Handler(Looper.getMainLooper())
    private val readings = mutableMapOf<String, Reading>()
    private val tried = mutableMapOf<String, Long>()
    private val statuses = mutableMapOf<String, String>()
    private val listeners = mutableSetOf<() -> Unit>()

    /** 覚えている残量。まだ読めていなければ null。 */
    fun level(device: BluetoothDevice): Int? = readings[device.address]?.level

    /** 最後に問い合わせたときの様子（診断用）。まだ問い合わせていなければ null。 */
    fun status(device: BluetoothDevice): String? = statuses[device.address]

    private fun setStatus(device: BluetoothDevice, text: String) = main.post {
        statuses[device.address] = text
        listeners.toList().forEach { it() }
    }

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    /**
     * しばらく読んでいなければ、[device] に残量を問い合わせる。[force] なら間隔に関係なく問い合わせる。
     * 結果と途中の様子はリスナーに知らせる。
     */
    fun request(context: Context, device: BluetoothDevice, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val last = tried[device.address]
        if (!force && last != null && now - last < REFRESH_MS) return
        tried[device.address] = now
        setStatus(device, "接続しています…")

        var gatt: BluetoothGatt? = null
        var done = false
        val finish = Runnable { runCatching { gatt?.disconnect(); gatt?.close() } }
        val timeout = Runnable {
            if (!done) setStatus(device, "応答がありませんでした（${TIMEOUT_MS / 1000} 秒）")
            finish.run()
        }
        fun end(text: String) {
            done = true
            setStatus(device, text)
            main.removeCallbacks(timeout)
            main.post(finish)
        }
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    setStatus(device, "接続しました。サービスを探しています…")
                    if (!g.discoverServices()) end("サービスを探せませんでした")
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (!done) end("切断されました（status=$status）")
                    main.post { runCatching { g.close() } }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val c = g.getService(SERVICE)?.getCharacteristic(LEVEL)
                if (c == null) {
                    // どんなサービスがあるかを短い番号で並べ、原因を探す手がかりにする
                    val ids = g.services.joinToString(", ") { it.uuid.toString().substring(4, 8) }
                    end("電池サービスがありません（サービス: ${ids.ifEmpty { "なし" }}）")
                } else if (!g.readCharacteristic(c)) {
                    end("電池残量を読めませんでした")
                } else {
                    setStatus(device, "電池残量を読んでいます…")
                }
            }

            override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                val level = value.firstOrNull()?.toInt()?.and(0xFF)
                if (status == BluetoothGatt.GATT_SUCCESS && level != null && level in 0..100) {
                    main.post { readings[device.address] = Reading(level, SystemClock.elapsedRealtime()) }
                    end("読めました: $level%")
                } else {
                    end("読み取りに失敗しました（status=$status）")
                }
            }
        }
        gatt = runCatching {
            device.connectGatt(context.applicationContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        }.getOrNull()
        if (gatt == null) {
            setStatus(device, "接続を始められませんでした")
            return
        }
        main.postDelayed(timeout, TIMEOUT_MS)
    }

    /** 前に読めた機器が切断されたら、古い残量を出し続けないよう忘れる。 */
    fun forget(device: BluetoothDevice) {
        readings.remove(device.address)
        tried.remove(device.address)
    }
}
