package com.example.flipcover

import android.content.ComponentName
import android.content.Context
import android.net.Uri

enum class Mode { AURORA, PARTICLES, GIF, VIDEO, EARTH, MOON, DOT_SECONDS, LEVEL_METER, DOT_WAVE, SEQUENCER }

enum class ClockStyle(val label: String) {
    DIGITAL("デジタル"),
    BOLD("ボールド（秒・日付つき）"),
    SIMPLE("シンプル"),
    MINIMAL("ミニマル"),
    NUMBERS("数字"),
    ROMAN("ローマ数字"),
    NEON("ネオン"),
    DOTS("ドット"),
}

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

    var clockStyle: ClockStyle
        // 以前の「アナログ時計にする」設定を引き継ぐ
        get() = runCatching { ClockStyle.valueOf(sp.getString("clockStyle", null)!!) }
            .getOrElse { if (sp.getBoolean("analog", false)) ClockStyle.SIMPLE else ClockStyle.DIGITAL }
        set(v) = sp.edit().putString("clockStyle", v.name).apply()

    /** アニメーションの速さの倍率（1 が標準）。 */
    var animSpeed: Float
        get() = sp.getFloat("animSpeed", 1f)
        set(v) = sp.edit().putFloat("animSpeed", v).apply()

    /** 時計の後ろにぼかした暗い背景を敷く。 */
    var clockBackdrop: Boolean
        get() = sp.getBoolean("clockBackdrop", true)
        set(v) = sp.edit().putBoolean("clockBackdrop", v).apply()

    // 配置はカバー画面に対する割合で持つ（位置は中心、大きさは画面幅に対する割合）
    var clockX: Float
        get() = sp.getFloat("clockX", 0.5f)
        set(v) = sp.edit().putFloat("clockX", v).apply()
    var clockY: Float
        get() = sp.getFloat("clockY", 0.45f)
        set(v) = sp.edit().putFloat("clockY", v).apply()
    var clockSize: Float
        get() = sp.getFloat("clockSize", 0.35f)
        set(v) = sp.edit().putFloat("clockSize", v).apply()
    var appsX: Float
        get() = sp.getFloat("appsX", 0.5f)
        set(v) = sp.edit().putFloat("appsX", v).apply()
    var appsY: Float
        get() = sp.getFloat("appsY", 0.75f)
        set(v) = sp.edit().putFloat("appsY", v).apply()
    var appsSize: Float
        get() = sp.getFloat("appsSize", 0.11f)
        set(v) = sp.edit().putFloat("appsSize", v).apply()

    fun resetLayout() = sp.edit()
        .remove("clockX").remove("clockY").remove("clockSize")
        .remove("appsX").remove("appsY").remove("appsSize")
        .apply()

    /** カバー画面に並べるアプリ（表示順）。 */
    var apps: List<ComponentName>
        get() = sp.getString("apps", null).orEmpty().split('|')
            .mapNotNull { ComponentName.unflattenFromString(it) }
        set(v) = sp.edit().putString("apps", v.joinToString("|") { it.flattenToString() }).apply()

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
