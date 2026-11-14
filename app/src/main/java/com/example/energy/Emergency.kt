package com.example.energy

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private const val API_BASE = "https://www.energy.mk.ua/outage/api/v1"
private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.0.0 Safari/537.36"
const val BRANCH_ID = 15                   // Миколаївський РЕМ
val KYIV: ZoneId = ZoneId.of("Europe/Kyiv")

class Named(val id: Int, val name: String)

class Emergency(val startMs: Long, val finishMs: Long, val reason: String)

private fun getText(url: String): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.setRequestProperty("Accept", "application/json")
    c.setRequestProperty("User-Agent", UA)
    c.connectTimeout = 5_000
    c.readTimeout = 10_000
    try {
        return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } finally {
        c.disconnect()
    }
}

private fun postJson(url: String, body: String): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.requestMethod = "POST"
    c.doOutput = true
    c.setRequestProperty("Content-Type", "application/json")
    c.setRequestProperty("Accept", "application/json")
    c.setRequestProperty("Origin", "https://www.energy.mk.ua")
    c.setRequestProperty("Referer", "https://www.energy.mk.ua/vidklyuchennya/")
    c.setRequestProperty("User-Agent", UA)
    c.connectTimeout = 5_000
    c.readTimeout = 10_000
    try {
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } finally {
        c.disconnect()
    }
}

// Список может быть массивом или объектом с числовыми ключами: разбираем оба варианта
fun parseNamed(text: String): List<Named> {
    val out = ArrayList<Named>()
    val t = text.trim()
    if (t.startsWith("[")) {
        val a = JSONArray(t)
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            out += Named(o.getInt("id"), o.getString("name"))
        }
    } else {
        val o = JSONObject(t)
        o.keys().forEach { k ->
            val x = o.getJSONObject(k)
            out += Named(x.getInt("id"), x.getString("name"))
        }
    }
    return out.sortedBy { it.name }
}

fun fetchCities(): List<Named> = parseNamed(getText("$API_BASE/address/branch/$BRANCH_ID/city"))
fun fetchStreets(cityId: Int): List<Named> = parseNamed(getText("$API_BASE/address/city/$cityId/street"))
fun fetchHouses(streetId: Int): List<Named> = parseNamed(getText("$API_BASE/address/street/$streetId/house"))

// Сутки по Киеву: вчера и сегодня, чтобы поймать аварию, начавшуюся вчера
private fun rangeSeconds(): Pair<Long, Long> {
    val today = LocalDate.now(KYIV)
    val from = today.minusDays(1).atStartOfDay(KYIV).toEpochSecond()
    val to = today.atStartOfDay(KYIV).toEpochSecond() + 86399
    return Pair(from, to)
}

// Все аварии по дому, со всех страниц. Лимит страниц защищает от зацикливания.
fun fetchAvariaForHouse(houseId: Int): List<Emergency> {
    val (from, to) = rangeSeconds()
    val perPage = 100
    val result = ArrayList<Emergency>()
    for (page in 1..20) {
        val body = JSONObject().apply {
            put("page", page)
            put("perPage", perPage)
            put("from", from)
            put("to", to)
            put("branch", BRANCH_ID)
            put("type", "AVARIYA")
            put("house_id", houseId)
        }.toString()
        val arr = JSONObject(postJson("$API_BASE/outage", body)).getJSONArray("data")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val reason = o.optString("reason", "").trim()
            val start = Instant.parse(o.getString("start_at")).toEpochMilli()
            val finish = Instant.parse(o.getString("finish_at")).toEpochMilli()
            if (finish <= start) continue                                  // нулевая длительность
            if (reason.isEmpty() || reason.startsWith("Відновлення")) continue  // не авария
            result += Emergency(start, finish, reason)
        }
        if (arr.length() < perPage) break
    }
    return result
}

// Выбирает аварию, действующую сейчас; если их несколько — с самым поздним окончанием
fun activeEmergency(list: List<Emergency>, nowMs: Long): Emergency? =
    list.filter { nowMs >= it.startMs && nowMs < it.finishMs }.maxByOrNull { it.finishMs }

// Вызывается из refreshSchedule: тот же цикл, что и график. При ошибке старые данные остаются.
fun refreshEmergency(ctx: Context) {
    val houseId = prefs(ctx).getInt("house_id", -1)
    if (houseId < 0) return
    try {
        val e = activeEmergency(fetchAvariaForHouse(houseId), System.currentTimeMillis())
        prefs(ctx).edit()
            .putLong("em_start", e?.startMs ?: 0L)
            .putLong("em_finish", e?.finishMs ?: 0L)
            .putString("em_reason", e?.reason ?: "")
            .apply()
    } catch (e: Exception) {
        // сайт не ответил — оставляем прежние данные
    }
}

// Текущая авария из сохранённых данных; null, если её нет или она уже закончилась
fun currentEmergency(ctx: Context, nowMs: Long): Emergency? {
    val p = prefs(ctx)
    val start = p.getLong("em_start", 0L)
    val finish = p.getLong("em_finish", 0L)
    if (finish == 0L || nowMs < start || nowMs >= finish) return null
    return Emergency(start, finish, p.getString("em_reason", "") ?: "")
}

fun kyivTime(ms: Long): String {
    val z = Instant.ofEpochMilli(ms).atZone(KYIV)
    return "%02d:%02d".format(z.hour, z.minute)
}
