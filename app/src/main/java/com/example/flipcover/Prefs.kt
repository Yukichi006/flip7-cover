package com.example.flipcover

import android.content.ComponentName
import android.content.Context
import android.graphics.PointF

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
    /** アプリをボタンにまとめ、タップで展開する。オフならアイコンを常に表示する。 */
    var useLauncher: Boolean
        get() = sp.getBoolean("useLauncher", true)
        set(v) = sp.edit().putBoolean("useLauncher", v).apply()

    var launcherStyle: LauncherStyle
        get() = runCatching { LauncherStyle.valueOf(sp.getString("launcherStyle", null)!!) }
            .getOrDefault(LauncherStyle.DOTS)
        set(v) = sp.edit().putString("launcherStyle", v.name).apply()

    // 最初はカメラと重ならない左下に置く
    var launcherX: Float
        get() = sp.getFloat("launcherX", 0.13f)
        set(v) = sp.edit().putFloat("launcherX", v).apply()
    var launcherY: Float
        get() = sp.getFloat("launcherY", 0.89f)
        set(v) = sp.edit().putFloat("launcherY", v).apply()
    var launcherSize: Float
        get() = sp.getFloat("launcherSize", 0.13f)
        set(v) = sp.edit().putFloat("launcherSize", v).apply()

    // ボタンで展開したときにアイコンを並べるグリッドの範囲（中心と幅・高さ、画面に対する割合）。
    // 最初は、カメラのある下の帯を避けて画面いっぱいに広げる
    var gridCx: Float
        get() = sp.getFloat("gridCx", 0.5f)
        set(v) = sp.edit().putFloat("gridCx", v).apply()
    var gridCy: Float
        get() = sp.getFloat("gridCy", 0.42f)
        set(v) = sp.edit().putFloat("gridCy", v).apply()
    var gridW: Float
        get() = sp.getFloat("gridW", 0.92f)
        set(v) = sp.edit().putFloat("gridW", v).apply()
    var gridH: Float
        get() = sp.getFloat("gridH", 0.76f)
        set(v) = sp.edit().putFloat("gridH", v).apply()

    /** スワイプでライトを点けるスイッチを表示する。 */
    var showTorch: Boolean
        get() = sp.getBoolean("showTorch", true)
        set(v) = sp.edit().putBoolean("showTorch", v).apply()

    // ライトのスイッチの中心と幅（画面に対する割合）。最初は右上の隅に置く
    var torchX: Float
        get() = sp.getFloat("torchX", 0.76f)
        set(v) = sp.edit().putFloat("torchX", v).apply()
    var torchY: Float
        get() = sp.getFloat("torchY", 0.08f)
        set(v) = sp.edit().putFloat("torchY", v).apply()
    var torchSize: Float
        get() = sp.getFloat("torchSize", 0.34f)
        set(v) = sp.edit().putFloat("torchSize", v).apply()

    /** 本体と Bluetooth 機器の電池残量を表示する。 */
    var showBattery: Boolean
        get() = sp.getBoolean("showBattery", true)
        set(v) = sp.edit().putBoolean("showBattery", v).apply()

    // 電池残量の中心と幅（画面に対する割合）。最初はライトのスイッチの下、右端に寄せる
    var batteryX: Float
        get() = sp.getFloat("batteryX", 0.86f)
        set(v) = sp.edit().putFloat("batteryX", v).apply()
    var batteryY: Float
        get() = sp.getFloat("batteryY", 0.27f)
        set(v) = sp.edit().putFloat("batteryY", v).apply()
    var batterySize: Float
        get() = sp.getFloat("batterySize", 0.24f)
        set(v) = sp.edit().putFloat("batterySize", v).apply()

    /** カバー画面に置くほかのアプリのウィジェットの番号（表示順）。 */
    var widgetIds: List<Int>
        get() = sp.getString("widgetIds", null)?.split('|')?.mapNotNull { it.toIntOrNull() }
            // 1 つだけ置けたころの設定を引き継ぐ
            ?: listOf(sp.getInt("widgetId", -1)).filter { it != -1 }
        set(v) = sp.edit().putString("widgetIds", v.joinToString("|")).remove("widgetId").apply()

    /** ウィジェットをボタンにまとめ、タップで展開する。オフならウィジェットを常に表示する。 */
    var useWidgetButton: Boolean
        get() = sp.getBoolean("useWidgetButton", true)
        set(v) = sp.edit().putBoolean("useWidgetButton", v).apply()

    var widgetButtonStyle: LauncherStyle
        get() = runCatching { LauncherStyle.valueOf(sp.getString("widgetButtonStyle", null)!!) }
            .getOrDefault(LauncherStyle.CARDS)
        set(v) = sp.edit().putString("widgetButtonStyle", v.name).apply()

    // 最初はアプリのボタンの上に置く
    var widgetButtonX: Float
        get() = sp.getFloat("widgetButtonX", 0.13f)
        set(v) = sp.edit().putFloat("widgetButtonX", v).apply()
    var widgetButtonY: Float
        get() = sp.getFloat("widgetButtonY", 0.75f)
        set(v) = sp.edit().putFloat("widgetButtonY", v).apply()
    var widgetButtonSize: Float
        get() = sp.getFloat("widgetButtonSize", 0.13f)
        set(v) = sp.edit().putFloat("widgetButtonSize", v).apply()

    // ウィジェットを並べる範囲（中心と幅・高さ、画面に対する割合）。
    // 最初は 2×2 のウィジェットが 4 つほぼ正方形に収まり、左下のボタンとも重ならない大きさにする
    var widgetX: Float
        get() = sp.getFloat("widgetX", 0.5f)
        set(v) = sp.edit().putFloat("widgetX", v).apply()
    var widgetY: Float
        get() = sp.getFloat("widgetY", 0.37f)
        set(v) = sp.edit().putFloat("widgetY", v).apply()
    var widgetW: Float
        get() = sp.getFloat("widgetW", 0.74f)
        set(v) = sp.edit().putFloat("widgetW", v).apply()
    var widgetH: Float
        get() = sp.getFloat("widgetH", 0.66f)
        set(v) = sp.edit().putFloat("widgetH", v).apply()

    /** アイコンをまだ動かしていないときに並べる列の中心。 */
    val appsX: Float
        get() = sp.getFloat("appsX", 0.5f)
    val appsY: Float
        get() = sp.getFloat("appsY", 0.75f)
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
        .remove("appPositions")
        .remove("launcherX").remove("launcherY").remove("launcherSize")
        .remove("gridCx").remove("gridCy").remove("gridW").remove("gridH")
        .remove("torchX").remove("torchY").remove("torchSize")
        .remove("widgetX").remove("widgetY").remove("widgetW").remove("widgetH")
        .remove("widgetButtonX").remove("widgetButtonY").remove("widgetButtonSize")
        .remove("batteryX").remove("batteryY").remove("batterySize")
        .apply()

    /** アプリごとのアイコンの位置（中心、画面に対する割合）。まだ動かしていないアプリは null。 */
    fun appPosition(app: ComponentName): PointF? = appPositions()[app.flattenToString()]

    fun setAppPosition(app: ComponentName, x: Float, y: Float) {
        val all = appPositions().toMutableMap()
        all[app.flattenToString()] = PointF(x, y)
        sp.edit().putString("appPositions", all.entries.joinToString("|") { (k, p) -> "$k=${p.x},${p.y}" }).apply()
    }

    private fun appPositions(): Map<String, PointF> =
        sp.getString("appPositions", null).orEmpty().split('|').mapNotNull { entry ->
            val (key, value) = entry.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
            val (x, y) = value.split(',').mapNotNull { it.toFloatOrNull() }.takeIf { it.size == 2 }
                ?: return@mapNotNull null
            key to PointF(x, y)
        }.toMap()

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
