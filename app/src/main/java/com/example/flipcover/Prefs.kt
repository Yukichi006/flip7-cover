package com.example.flipcover

import android.content.ComponentName
import android.content.Context

enum class Mode { EARTH, MOON, DOT_SECONDS, LEVEL_METER, DOT_WAVE, SEQUENCER, DOT_CLOCK }

/** [dial] があるものは背景のドットで、[light] のものは淡い光で、背景と一体に描く時計。 */
enum class ClockStyle(val label: String, val dial: DotDialView.Pattern? = null, val light: Boolean = false) {
    DIGITAL("デジタル"),
    BOLD("ボールド（秒・日付つき）"),
    SIMPLE("シンプル"),
    MINIMAL("ミニマル"),
    NUMBERS("数字"),
    ROMAN("ローマ数字"),
    NEON("ネオン"),
    DIAL_CHANNEL("ドット列の針（背景と一体）", DotDialView.Pattern.CHANNEL),
    DIAL_RADIAL("放射グリッド（背景と一体）", DotDialView.Pattern.RADIAL),
    DIAL_TICKS("目盛りリング（背景と一体）", DotDialView.Pattern.TICKS),
    DIAL_SECTOR("扇形（背景と一体）", DotDialView.Pattern.SECTOR),
    DIAL_RADAR("レーダー（背景と一体）", DotDialView.Pattern.RADAR),
    LIGHT_SECTOR("光の扇形（ドットなし・背景と一体）", light = true),
    ;

    /** 背景と一体に描く時計か。 */
    val integrated get() = dial != null || light
}

/** 設定値。アプリ画面とサービスの両方から読み書きする。 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    var mode: Mode
        get() = runCatching { Mode.valueOf(sp.getString("mode", null)!!) }.getOrDefault(Mode.DOT_WAVE)
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

    /** 分針を太く長くする（ドット系の時計）。 */
    var boldMinute: Boolean
        get() = sp.getBoolean("boldMinute", false)
        set(v) = sp.edit().putBoolean("boldMinute", v).apply()

    /** 分針の先に丸、外周に印を付ける（ドット系の時計）。 */
    var minuteTip: Boolean
        get() = sp.getBoolean("minuteTip", false)
        set(v) = sp.edit().putBoolean("minuteTip", v).apply()

    /** アナログ時計とは別に、秒つきのデジタル時計も表示する。 */
    var showDigital: Boolean
        get() = sp.getBoolean("showDigital", false)
        set(v) = sp.edit().putBoolean("showDigital", v).apply()

    /** ドット系の時計で、針のドットを先端ほど小さくする。 */
    var taperHands: Boolean
        get() = sp.getBoolean("taperHands", true)
        set(v) = sp.edit().putBoolean("taperHands", v).apply()

    /** ドット系の時計の、扇形や残像の光の強さ（0〜1）。 */
    var glow: Float
        get() = sp.getFloat("glow", 0.5f)
        set(v) = sp.edit().putFloat("glow", v).apply()

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

    var digitalX: Float
        get() = sp.getFloat("digitalX", 0.5f)
        set(v) = sp.edit().putFloat("digitalX", v).apply()
    var digitalY: Float
        get() = sp.getFloat("digitalY", 0.15f)
        set(v) = sp.edit().putFloat("digitalY", v).apply()
    var digitalSize: Float
        get() = sp.getFloat("digitalSize", 0.42f)
        set(v) = sp.edit().putFloat("digitalSize", v).apply()

    fun resetLayout() = sp.edit()
        .remove("clockX").remove("clockY").remove("clockSize")
        .remove("appsX").remove("appsY").remove("appsSize")
        .remove("digitalX").remove("digitalY").remove("digitalSize")
        .apply()

    /** カバー画面に並べるアプリ（表示順）。 */
    var apps: List<ComponentName>
        get() = sp.getString("apps", null).orEmpty().split('|')
            .mapNotNull { ComponentName.unflattenFromString(it) }
        set(v) = sp.edit().putString("apps", v.joinToString("|") { it.flattenToString() }).apply()

    fun registerListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(l)

    fun unregisterListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(l)
}
