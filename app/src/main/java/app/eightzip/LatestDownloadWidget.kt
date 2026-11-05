package app.eightzip

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.View
import android.widget.RemoteViews
import java.io.File

/**
 * Home-screen widget: shows the newest archive in Downloads with one-tap Extract.
 * Tapping opens 8zip, which does the extraction (progress, password prompt and all).
 */
class LatestDownloadWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, manager, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) refreshAll(context) else super.onReceive(context, intent)
    }

    companion object {
        const val ACTION_REFRESH = "app.eightzip.action.REFRESH_WIDGET"
        const val ACTION_EXTRACT_LATEST = "app.eightzip.action.EXTRACT_LATEST"
        const val ACTION_OPEN_LATEST = "app.eightzip.action.OPEN_LATEST"

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, LatestDownloadWidget::class.java))
            ids.forEach { update(context, manager, it) }
        }

        private fun update(context: Context, manager: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_latest)
            val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
            val latest = if (allowed) LatestDownload.find(File(Environment.getExternalStorageDirectory(), "Download")) else null

            val immutable = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            fun launch(action: String, code: Int, file: File?): PendingIntent {
                val i = Intent(context, MainActivity::class.java).setAction(action)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                if (file != null) i.putExtra("path", file.path)
                return PendingIntent.getActivity(context, id * 10 + code, i, immutable)
            }

            views.setOnClickPendingIntent(
                R.id.widget_refresh,
                PendingIntent.getBroadcast(
                    context, id * 10 + 3,
                    Intent(context, LatestDownloadWidget::class.java).setAction(ACTION_REFRESH),
                    immutable
                )
            )

            when {
                !allowed -> {
                    views.setTextViewText(R.id.widget_name, context.getString(R.string.widget_need_access))
                    views.setTextViewText(R.id.widget_detail, context.getString(R.string.widget_tap_to_grant))
                    views.setViewVisibility(R.id.widget_extract, View.GONE)
                    views.setOnClickPendingIntent(R.id.widget_open_area, launch(Intent.ACTION_MAIN, 0, null))
                }
                latest == null -> {
                    views.setTextViewText(R.id.widget_name, context.getString(R.string.widget_none))
                    views.setTextViewText(R.id.widget_detail, context.getString(R.string.widget_none_hint))
                    views.setViewVisibility(R.id.widget_extract, View.GONE)
                    views.setOnClickPendingIntent(R.id.widget_open_area, launch(Intent.ACTION_MAIN, 0, null))
                }
                else -> {
                    val done = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                        .getString("last_extracted", null) == "${latest.path}|${latest.lastModified()}"
                    val age = DateUtils.getRelativeTimeSpanString(
                        latest.lastModified(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE
                    )
                    views.setTextViewText(R.id.widget_name, latest.name)
                    views.setTextViewText(
                        R.id.widget_detail,
                        Formatter.formatShortFileSize(context, latest.length()) + " · " + age + if (done) " · " + context.getString(R.string.widget_extracted) else ""
                    )
                    views.setTextViewText(R.id.widget_extract, context.getString(if (done) R.string.widget_again else R.string.widget_extract))
                    views.setViewVisibility(R.id.widget_extract, View.VISIBLE)
                    views.setOnClickPendingIntent(R.id.widget_extract, launch(ACTION_EXTRACT_LATEST, 1, latest))
                    views.setOnClickPendingIntent(R.id.widget_open_area, launch(ACTION_OPEN_LATEST, 2, latest))
                }
            }
            manager.updateAppWidget(id, views)
        }
    }
}
