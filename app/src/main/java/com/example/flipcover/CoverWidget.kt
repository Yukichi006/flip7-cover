package com.example.flipcover

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews

/**
 * Galaxy のカバー画面に置けるウィジェット（Samsung の公式の仕組み。設定 →「カバー画面」→「ウィジェット」で追加する）。
 *
 * 時刻と日付を出し、押すと FlipCover の全画面表示（[CoverActivity]）をカバー画面で開く。
 * 時刻は Android の時計の部品が自分で進めるので、FlipCover が裏で動き続ける必要はない。権限も要らない。
 */
class CoverWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val open = PendingIntent.getActivity(
            context,
            0,
            CoverActivity.intent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            CoverActivity.options(context),
        )
        val views = RemoteViews(context.packageName, R.layout.widget_cover).apply {
            setOnClickPendingIntent(R.id.widgetRoot, open)
        }
        ids.forEach { manager.updateAppWidget(it, views) }
    }
}
