package com.example.energy

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

// Очереди дома с сайта off.energy.mk.ua. ID совпадают со справочниками очередей.
class AddressQueues(val gpvId: Int?, val gpvName: String?, val gavId: Int?, val gavName: String?)

// СГАВ открывается для всех сразу, поэтому не выбирается. ID трёх очередей СГАВ.
val SGAV_IDS = listOf(11, 12, 13)

private fun getText(url: String): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.setRequestProperty("Accept", "application/json")
    c.setRequestProperty("User-Agent", "energy-widget")
    c.connectTimeout = 5_000
    c.readTimeout = 10_000
    try {
        return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } finally {
        c.disconnect()
    }
}

// Ответ: [{"outage":{"id":21,"name":"4.1","type_id":3,...},"outageType":{...}}, ...]
// type_id 3 — ГПВ, type_id 1 — ГАВ
fun fetchAddressQueues(houseId: Int): AddressQueues {
    val arr = JSONArray(getText("https://off.energy.mk.ua/api/addr/dom/$houseId/outage-queue"))
    var gpvId: Int? = null
    var gpvName: String? = null
    var gavId: Int? = null
    var gavName: String? = null
    for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i).getJSONObject("outage")
        when (o.getInt("type_id")) {
            TYPE_GPV -> {
                gpvId = o.getInt("id")
                gpvName = o.getString("name")
            }
            TYPE_GAV -> {
                gavId = o.getInt("id")
                gavName = o.getString("name")
            }
        }
    }
    return AddressQueues(gpvId, gpvName, gavId, gavName)
}
