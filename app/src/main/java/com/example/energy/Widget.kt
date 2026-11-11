package com.example.energy

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.widget.RemoteViews
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

const val ACTION_REFRESH = "com.example.energy.REFRESH"
private const val WORK_NAME = "energy_refresh"
private val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

class EnergyWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { renderWidget(ctx, mgr, it) }
        schedule(ctx)
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle) {
        renderWidget(ctx, mgr, id)
    }

    override fun onDisabled(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_REFRESH) {
            val pending = goAsync()
            Thread {
                try {
                    refreshSchedule(ctx)
                } finally {
                    updateAll(ctx)
                    pending.finish()
                }
            }.start()
        }
    }
}

fun updateAll(ctx: Context) {
    val mgr = AppWidgetManager.getInstance(ctx)
    mgr.getAppWidgetIds(ComponentName(ctx, EnergyWidget::class.java))
        .forEach { renderWidget(ctx, mgr, it) }
}

// Плашки ГАВ и СГАВ — только если по выбранному номеру сейчас отключение
fun badgesFor(day: Day?, nowMs: Long, s: Settings): List<String> {
    if (day == null) return emptyList()
    val slot = day.slotAt(nowMs)
    return buildList {
        if (s.gavId >= 0 && day.statusAt(s.gavId, slot) == PowerStatus.OUTAGE) add("ГАВ")
        if (s.sgavId >= 0 && day.statusAt(s.sgavId, slot) == PowerStatus.OUTAGE) add("СГАВ")
    }
}

// Текст предупреждения, если последняя попытка загрузки не удалась
fun warningText(ctx: Context): String? {
    if (!connectionFailed(ctx)) return null
    val at = fetchedAt(ctx)
    return if (at == 0L) {
        "Нет связи с сайтом · данных нет"
    } else {
        val time = SimpleDateFormat("HH:mm").format(Date(at))
        "Нет связи · данные на $time, могут быть неактуальны"
    }
}

// Время последней успешной загрузки для верхней строки виджета
fun updatedText(ctx: Context): String {
    val at = fetchedAt(ctx)
    return if (at == 0L) "--:--" else SimpleDateFormat("HH:mm").format(Date(at))
}

fun renderWidget(ctx: Context, mgr: AppWidgetManager, id: Int) {
    val opts = mgr.getAppWidgetOptions(id)
    val dp = minOf(
        opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH),
        opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
    ).coerceAtLeast(80)
    val px = (dp * ctx.resources.displayMetrics.density).toInt()

    val now = System.currentTimeMillis()
    val s = loadSettings(ctx)
    val day = currentDay(loadDays(ctx), now)

    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    drawDial(
        Canvas(bmp), px.toFloat(), day, s.gpvId, now,
        badgesFor(day, now, s), warningText(ctx)
    )

    val views = RemoteViews(ctx.packageName, R.layout.widget_energy)
    views.setImageViewBitmap(R.id.dial, bmp)
    views.setTextViewText(R.id.updated, updatedText(ctx))
    views.setOnClickPendingIntent(R.id.dial, pendingActivity(ctx, MainActivity::class.java, 10))
    views.setOnClickPendingIntent(R.id.gear, pendingActivity(ctx, SettingsActivity::class.java, 11))
    views.setOnClickPendingIntent(R.id.refresh, pendingRefresh(ctx))
    mgr.updateAppWidget(id, views)
}

private fun pendingActivity(ctx: Context, cls: Class<*>, req: Int): PendingIntent =
    PendingIntent.getActivity(ctx, req, Intent(ctx, cls), FLAGS)

private fun pendingRefresh(ctx: Context): PendingIntent =
    PendingIntent.getBroadcast(
        ctx, 12,
        Intent(ctx, EnergyWidget::class.java).setAction(ACTION_REFRESH),
        FLAGS
    )

fun schedule(ctx: Context) {
    val minutes = loadSettings(ctx).intervalMin.toLong().coerceAtLeast(15)
    val req = PeriodicWorkRequestBuilder<RefreshWorker>(minutes, TimeUnit.MINUTES).build()
    WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
        WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req
    )
    scheduleUpdateCheck(ctx)
}

class RefreshWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        refreshSchedule(applicationContext)
        updateAll(applicationContext)
        return Result.success()
    }
}
