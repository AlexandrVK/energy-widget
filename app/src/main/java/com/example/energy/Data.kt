package com.example.energy

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

const val SLOTS = 48                       // получасы в сутках
const val SLOT_MS = 30 * 60_000L
const val TYPE_GPV = 3                     // справочник ГПВ
const val TYPE_GAV = 1                     // справочник ГАВ (20 позиций)
const val TYPE_SGAV = 2                    // справочник СГАВ (3 позиции)
private const val API_SCHEDULE = "https://off.energy.mk.ua/api/v2/schedule/active"
private const val API_QUEUES = "https://off.energy.mk.ua/api/outage-queue/by-type/%d"

enum class PowerStatus { POWER_ON, POSSIBLE, OUTAGE }

class Queue(val id: Int, val name: String)

class Day(val fromMs: Long, val toMs: Long, private val byQueue: Map<Int, Array<PowerStatus>>) {
    fun statusAt(queueId: Int, slot: Int): PowerStatus =
        byQueue[queueId]?.get(slot) ?: PowerStatus.POWER_ON   // нет записи = свет есть

    fun slotAt(nowMs: Long): Int =
        ((nowMs - fromMs) / SLOT_MS).toInt().coerceIn(0, SLOTS - 1)
}

fun prefs(ctx: Context) = ctx.getSharedPreferences("energy", Context.MODE_PRIVATE)

private fun httpGet(url: String): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 5_000
    c.readTimeout = 5_000
    try {
        return c.inputStream.bufferedReader().use { it.readText() }
    } finally {
        c.disconnect()
    }
}

fun parseDays(json: String): List<Day> {
    val days = JSONArray(json)
    return (0 until days.length()).map { i ->
        val d = days.getJSONObject(i)
        val byQueue = HashMap<Int, Array<PowerStatus>>()
        val series = d.getJSONArray("series")
        for (j in 0 until series.length()) {
            val s = series.getJSONObject(j)
            val st = when (s.getString("type")) {
                "OFF", "SURE_OFF" -> PowerStatus.OUTAGE
                "PROBABLY_OFF" -> PowerStatus.POSSIBLE
                else -> continue
            }
            val arr = byQueue.getOrPut(s.getInt("outage_queue_id")) {
                Array(SLOTS) { PowerStatus.POWER_ON }
            }
            val slot = s.getInt("time_series_id") - 1
            if (st.ordinal > arr[slot].ordinal) arr[slot] = st
        }
        Day(
            Instant.parse(d.getString("from")).toEpochMilli(),
            Instant.parse(d.getString("to")).toEpochMilli(),
            byQueue
        )
    }
}

fun fetchQueues(type: Int): List<Queue> {
    val arr = JSONArray(httpGet(API_QUEUES.format(type)))
    return (0 until arr.length())
        .map { arr.getJSONObject(it) }
        .filter { it.getInt("deleted") == 0 }
        .map { Queue(it.getInt("id"), it.getString("name")) }
}

// Есть ли сейчас рабочий выход в интернет (сеть подключена и проверена системой)
fun isOnline(ctx: Context): Boolean {
    val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
    val net = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(net) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

// Разовое обновление, которое система запустит сама, как только появится сеть
fun refreshWhenOnline(ctx: Context) {
    val req = OneTimeWorkRequestBuilder<RefreshWorker>()
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()
    WorkManager.getInstance(ctx)
        .enqueueUniqueWork("energy_catchup", ExistingWorkPolicy.KEEP, req)
}

// Несколько попыток подряд. Возвращает true, если данные получены.
// Нет сети — не считается сбоем сайта: флаг не ставим, а обновление откладываем до появления сети.
// При успехе сохраняет время и сбрасывает флаг сбоя. При неудаче (сеть есть, сайт не ответил) ставит флаг.
fun refreshSchedule(ctx: Context, attempts: Int = 3): Boolean {
    if (!isOnline(ctx)) {
        refreshWhenOnline(ctx)
        return false
    }
    repeat(attempts) { attempt ->
        try {
            val json = httpGet(API_SCHEDULE)
            parseDays(json)                // проверка формата до сохранения
            prefs(ctx).edit()
                .putString("schedule", json)
                .putLong("fetched_at", System.currentTimeMillis())
                .putBoolean("failed", false)
                .apply()
            return true
        } catch (e: Exception) {
            if (attempt < attempts - 1) Thread.sleep(1_000L * (attempt + 1))
        }
    }
    prefs(ctx).edit().putBoolean("failed", true).apply()
    return false
}

fun connectionFailed(ctx: Context): Boolean = prefs(ctx).getBoolean("failed", false)

// 0, если данных ещё не было
fun fetchedAt(ctx: Context): Long = prefs(ctx).getLong("fetched_at", 0L)

fun loadDays(ctx: Context): List<Day> = try {
    parseDays(prefs(ctx).getString("schedule", "[]") ?: "[]")
} catch (e: Exception) {
    emptyList()
}

fun currentDay(days: List<Day>, nowMs: Long): Day? =
    days.firstOrNull { nowMs >= it.fromMs && nowMs < it.toMs }
