package com.example.energy

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.sin

private fun colorOf(s: PowerStatus): Int = when (s) {
    PowerStatus.POWER_ON -> 0xFF2E7D32.toInt()
    PowerStatus.POSSIBLE -> 0xFFF9A825.toInt()
    PowerStatus.OUTAGE -> 0xFFC62828.toInt()
}

// Циферблат size x size. 00:00 наверху, по часовой стрелке.
// warning — красная полоса внизу; emergency — красный квадрат с аварией в центре.
fun drawDial(
    c: Canvas, size: Float, day: Day?, gpvId: Int, nowMs: Long,
    badges: List<String>, warning: String? = null, emergency: Emergency? = null,
    info: List<String> = emptyList()
) {
    val cx = size / 2f
    val cy = size / 2f
    val r = size / 2f * 0.96f

    c.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })

    // Цветные секторы от центра по статусам ГПВ
    val pie = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val rPie = r * 0.74f
    val box = RectF(cx - rPie, cy - rPie, cx + rPie, cy + rPie)
    for (i in 0 until SLOTS) {
        pie.color = if (day != null && gpvId >= 0) colorOf(day.statusAt(gpvId, i))
        else 0xFF9E9E9E.toInt()
        c.drawArc(box, -90f + i * 7.5f, 7.5f, true, pie)
    }

    // Подписи часов между секторами и краем, риски по краю
    val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF424242.toInt() }
    val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF212121.toInt()
        textAlign = Paint.Align.CENTER
        textSize = r * 0.11f
        isFakeBoldText = true
    }
    for (i in 0 until SLOTS) {
        val hour = i % 2 == 0
        val a = Math.toRadians((-90 + i * 7.5).toDouble())
        val cosA = cos(a).toFloat()
        val sinA = sin(a).toFloat()
        tick.strokeWidth = if (hour) r * 0.03f else r * 0.012f
        val rOut = r * 0.99f
        val rIn = if (hour) r * 0.91f else r * 0.95f
        c.drawLine(cx + rOut * cosA, cy + rOut * sinA, cx + rIn * cosA, cy + rIn * sinA, tick)
        if (hour) {
            val rt = r * 0.82f
            val y = cy + rt * sinA - (label.descent() + label.ascent()) / 2f
            c.drawText("%02d".format(i / 2), cx + rt * cosA, y, label)
        }
    }

    // Стрелка текущего времени
    if (day != null) {
        val minutes = (nowMs - day.fromMs) / 60_000f
        val a = Math.toRadians((-90 + minutes / 1440f * 360f).toDouble())
        val hand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            strokeWidth = r * 0.02f
            strokeCap = Paint.Cap.ROUND
        }
        val len = r * 0.62f
        c.drawLine(cx, cy, cx + len * cos(a).toFloat(), cy + len * sin(a).toFloat(), hand)
    }

    // ГПВ и ГАВ по адресу: две строки в центре, на белой подложке
    if (info.isNotEmpty()) {
        val inf = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF212121.toInt()
            textAlign = Paint.Align.CENTER
            textSize = r * 0.10f
            isFakeBoldText = true
        }
        val lineH = r * 0.12f
        val boxW = info.maxOf { inf.measureText(it) } + r * 0.12f
        val boxH = info.size * lineH + r * 0.06f
        val plate = RectF(cx - boxW / 2f, cy - boxH / 2f, cx + boxW / 2f, cy + boxH / 2f)
        c.drawRoundRect(plate, r * 0.05f, r * 0.05f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        var y = cy - (info.size * lineH) / 2f + lineH * 0.8f
        info.forEach { line ->
            c.drawText(line, cx, y, inf)
            y += lineH
        }
    }

    // Плашки ГАВ / СГАВ поверх циферблата
    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC62828.toInt() }
    val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = r * 0.17f
        isFakeBoldText = true
    }
    val gap = r * 0.04f
    val bw = r * 0.7f
    val bh = r * 0.26f
    val total = badges.size * bh + (badges.size - 1) * gap
    badges.forEachIndexed { k, text ->
        val top = cy - total / 2f + k * (bh + gap)
        val rect = RectF(cx - bw / 2f, top, cx + bw / 2f, top + bh)
        c.drawRoundRect(rect, bh * 0.25f, bh * 0.25f, bg)
        c.drawText(text, cx, rect.centerY() - (fg.descent() + fg.ascent()) / 2f, fg)
    }

    // Аварийный баннер в центре: алый квадрат поверх плашек
    if (emergency != null) {
        val side = r * 0.66f
        val sq = RectF(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f)
        c.drawRoundRect(sq, side * 0.08f, side * 0.08f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE02B2B.toInt() })
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = side * 0.13f
            isFakeBoldText = true
        }
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = side * 0.10f
        }
        val lines = listOf("АВАРИЯ", "до ${kyivTime(emergency.finishMs)}") +
            wrap(emergency.reason, body, side * 0.86f, 4)
        val lineH = side * 0.13f
        var y = cy - (lines.size * lineH) / 2f + lineH * 0.8f
        lines.forEachIndexed { i, s ->
            c.drawText(s, cx, y, if (i < 2) title else body)
            y += lineH
        }
    }

    // Предупреждение о нет связи с сайтом — внизу, во всю ширину
    if (warning != null) {
        val h = size * 0.08f
        c.drawRect(0f, size - h, size, size, Paint().apply { color = 0xFFC62828.toInt() })
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = size * 0.05f
            isFakeBoldText = true
        }
        val maxW = size * 0.94f
        val w = t.measureText(warning)
        if (w > maxW) t.textSize *= maxW / w
        c.drawText(warning, cx, size - h / 2f - (t.descent() + t.ascent()) / 2f, t)
    }
}

// Переносит текст по словам в maxW; при превышении числа строк последнюю обрезает
private fun wrap(text: String, p: Paint, maxW: Float, maxLines: Int): List<String> {
    val lines = mutableListOf<String>()
    var cur = ""
    for (word in text.split(" ").filter { it.isNotEmpty() }) {
        val cand = if (cur.isEmpty()) word else "$cur $word"
        if (p.measureText(cand) <= maxW) {
            cur = cand
        } else {
            if (cur.isNotEmpty()) lines += cur
            cur = word
        }
    }
    if (cur.isNotEmpty()) lines += cur
    if (lines.size > maxLines) {
        val kept = lines.take(maxLines).toMutableList()
        kept[maxLines - 1] = kept[maxLines - 1].dropLast(1) + "…"
        return kept
    }
    return lines
}
