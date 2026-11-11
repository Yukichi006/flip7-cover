package com.example.flipcover

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「閉じたら出す」仕組みが、いつ・どの画面がどう点いているときに・どう判断したかの記録。
 * うまく出ないときに、設定画面の「全般」の下で読めるようにする。端末の中にだけ残し、新しい 30 行だけを持つ。
 * 書くのは時刻と画面の番号・点き方だけ（アプリの名前や通知などは書かない）。
 */
object CoverLog {
    private const val MAX = 30

    // カバー画面の表示は、設定が変わるたびに作り直すので、設定とは別のファイルに書く
    private fun sp(context: Context) = context.getSharedPreferences("log", Context.MODE_PRIVATE)

    fun add(context: Context, line: String) {
        val time = SimpleDateFormat("M/d H:mm:ss.SSS", Locale.US).format(Date())
        val lines = (listOf("$time $line") + sp(context).getString("lines", "").orEmpty().split("\n")).filter { it.isNotEmpty() }.take(MAX)
        sp(context).edit().putString("lines", lines.joinToString("\n")).apply()
    }

    fun text(context: Context): String = sp(context).getString("lines", "").orEmpty().ifEmpty { "まだありません" }

    /** いまの画面ごとの点き方（例：0=ON 1=OFF）。 */
    fun states(context: Context): String {
        val dm = context.getSystemService(DisplayManager::class.java) ?: return "画面の情報なし"
        return dm.displays.joinToString(" ") { d ->
            val state = when (d.state) {
                Display.STATE_ON -> "ON"
                Display.STATE_OFF -> "OFF"
                Display.STATE_DOZE, Display.STATE_DOZE_SUSPEND -> "DOZE"
                else -> d.state.toString()
            }
            "${d.displayId}=$state"
        }
    }
}
