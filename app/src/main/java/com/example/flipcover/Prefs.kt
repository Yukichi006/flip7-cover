package com.example.flipcover

import android.content.Context
import android.net.Uri

enum class Mode { AURORA, PARTICLES, GIF, VIDEO }

/** 設定値。アプリ画面とサービスの両方から読み書きする。 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    var mode: Mode
        get() = runCatching { Mode.valueOf(sp.getString("mode", null)!!) }.getOrDefault(Mode.AURORA)
        set(v) = sp.edit().putString("mode", v.name).apply()

    var showClock: Boolean
        get() = sp.getBoolean("clock", true)
        set(v) = sp.edit().putBoolean("clock", v).apply()

    var gifUri: Uri?
        get() = sp.getString("gif", null)?.let(Uri::parse)
        set(v) = sp.edit().putString("gif", v?.toString()).apply()

    var videoUri: Uri?
        get() = sp.getString("video", null)?.let(Uri::parse)
        set(v) = sp.edit().putString("video", v?.toString()).apply()

    fun registerListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(l)

    fun unregisterListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(l)
}
