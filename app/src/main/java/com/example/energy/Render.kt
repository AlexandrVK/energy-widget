package com.example.energy

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.sin

private fun colorOf(s: PowerStatus): Int = when (s) {
    PowerStatus.POWER_ON -> 0xFF2E7D32.toInt()
    PowerStatus.POSSIBLE -> 0xFFF9A825.toInt()
    PowerStatus.OUTAGE -> 0xFFC62828.toInt()
}

private fun statusColor(day: Day?, gpvId: Int, slot: Int): Int =
    if (day != null && gpvId >= 0) colorOf(day.statusAt(gpvId, slot)) else 0xFF9E9E9E.toInt()

// Градусы: 0 — 12:00 сверху, по часовой стрелке (в экранных координатах)
private fun rad(deg: Float): Double = Math.toRadians(deg.toDouble())

// Циферблат size x size.
// mode12 = false: 24 часа, одно кольцо секторов от центра.
// mode12 = true: 12 часов, два кольца: внешнее 12:00–24:00, внутреннее 00:00–12:00.
fun drawDial(
    c: Canvas, size: Float, day: Day?, gpvId: Int, nowMs: Long,
    badges: List<String>, warning: String? = null, emergency: Emergency? = null,
    info: List<String> = emptyList(), mode12: Boolean = false
) {
    val cx = size / 2f
    val cy = size / 2f
    val r = size / 2f * 0.96f

    c.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })

    if (mode12) drawRings12(c, cx, cy, r, day, gpvId) else drawPie24(c, cx, cy, r, day, gpvId)
    drawScale(c, cx, cy, r, mode12)

    // Стрелка текущего времени: на 12-часовой шкале — по модулю 12 часов
    if (day != null) {
        val angle = if (mode12) {
            val minutes = ((nowMs - day.fromMs) / 60_000L) % 720L
            -90f + minutes.toFloat() / 720f * 360f
        } else {
            val minutes = (nowMs - day.fromMs) / 60_000f
            -90f + minutes / 1440f * 360f
        }
        drawHand(c, cx, cy, r, angle)
    }

    // ГПВ и ГАВ по адресу: плашка в центре
    if (info.isNotEmpty()) {
        val lineH = r * 0.09f
        val inf = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF212121.toInt()
            textAlign = Paint.Align.CENTER
            textSize = r * 0.08f
            isFakeBoldText = true
        }
        val boxW = info.maxOf { inf.measureText(it) } + r * 0.10f
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

// 24 часа: сектора от центра до 0.74r, каждый 7.5° (получас)
private fun drawPie24(c: Canvas, cx: Float, cy: Float, r: Float, day: Day?, gpvId: Int) {
    val pie = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val rPie = r * 0.74f
    val box = RectF(cx - rPie, cy - rPie, cx + rPie, cy + rPie)
    for (i in 0 until SLOTS) {
        pie.color = statusColor(day, gpvId, i)
        c.drawArc(box, -90f + i * 7.5f, 7.5f, true, pie)
    }
}

// 12 часов: два кольца по 24 получаса (каждый 15°)
private fun drawRings12(c: Canvas, cx: Float, cy: Float, r: Float, day: Day?, gpvId: Int) {
    val textSize = r * 0.085f
    ring(c, cx, cy, r * 0.74f, r * 0.52f, 24, day, gpvId, "12:00–24:00", textSize)
    ring(c, cx, cy, r * 0.49f, r * 0.27f, 0, day, gpvId, "00:00–12:00", textSize)
}

// Кольцо: сектора от rIn до rOut, внутри белый круг. offset — первый получас кольца.
private fun ring(
    c: Canvas, cx: Float, cy: Float, rOut: Float, rIn: Float, offset: Int,
    day: Day?, gpvId: Int, label: String, textSize: Float
) {
    val box = RectF(cx - rOut, cy - rOut, cx + rOut, cy + rOut)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    for (k in 0 until 24) {
        p.color = statusColor(day, gpvId, offset + k)
        c.drawArc(box, -90f + k * 15f, 15f, true, p)
    }
    c.drawCircle(cx, cy, rIn, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
    drawArcText(c, label, cx, cy, (rOut + rIn) / 2f, textSize)
}

// Подпись по дуге, вверху кольца. Буквы повёрнуты по касательной.
private fun drawArcText(c: Canvas, text: String, cx: Float, cy: Float, radius: Float, textSize: Float) {
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF212121.toInt()
        this.textSize = textSize
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        this.textSize = textSize
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        style = Paint.Style.STROKE
        strokeWidth = textSize * 0.25f
    }
    val total = fill.measureText(text)
    var pos = -total / 2f
    for (ch in text) {
        val s = ch.toString()
        val w = fill.measureText(s)
        val mid = pos + w / 2f
        val theta = -90f + Math.toDegrees(mid.toDouble() / radius).toFloat()
        val a = rad(theta)
        val x = cx + radius * cos(a).toFloat()
        val y = cy + radius * sin(a).toFloat()
        for (p in listOf(halo, fill)) {
            c.save()
            c.translate(x, y)
            c.rotate(theta + 90f)
            c.drawText(s, 0f, textSize * 0.35f, p)
            c.restore()
        }
        pos += w
    }
}

// Риски и цифры. Риски по внутреннему краю шкалы, цифры снаружи над ними.
// 24 часа: 24 цифры, 48 рисок. 12 часов: 12 цифр, 24 риски.
private fun drawScale(c: Canvas, cx: Float, cy: Float, r: Float, mode12: Boolean) {
    val hours = if (mode12) 12 else 24
    val steps = hours * 2
    val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF424242.toInt() }
    val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF212121.toInt()
        textAlign = Paint.Align.CENTER
        textSize = r * 0.10f
        isFakeBoldText = true
    }
    for (i in 0 until steps) {
        val hour = i % 2 == 0
        val a = rad(-90f + i * 360f / steps)
        val cosA = cos(a).toFloat()
        val sinA = sin(a).toFloat()
        tick.strokeWidth = if (hour) r * 0.03f else r * 0.012f
        val rOut = r * 0.86f
        val rIn = if (hour) r * 0.80f else r * 0.83f
        c.drawLine(cx + rOut * cosA, cy + rOut * sinA, cx + rIn * cosA, cy + rIn * sinA, tick)
    }
    for (h in 0 until hours) {
        val a = rad(-90f + h * 360f / hours)
        val text = if (mode12) (if (h == 0) "12" else h.toString()) else "%02d".format(h)
        val rt = r * 0.93f
        val y = cy + rt * sin(a).toFloat() - (label.descent() + label.ascent()) / 2f
        c.drawText(text, cx + rt * cos(a).toFloat(), y, label)
    }
}

// Стрелка: заострённый клин с белой обводкой, круглая втулка в центре
private fun drawHand(c: Canvas, cx: Float, cy: Float, r: Float, angleDeg: Float) {
    val a = rad(angleDeg)
    val dx = cos(a).toFloat()
    val dy = sin(a).toFloat()
    val px = -dy
    val py = dx
    val tipLen = r * 0.68f
    val tail = r * 0.10f
    val half = r * 0.03f
    val path = Path().apply {
        moveTo(cx + dx * tipLen, cy + dy * tipLen)
        lineTo(cx + px * half, cy + py * half)
        lineTo(cx - dx * tail, cy - dy * tail)
        lineTo(cx - px * half, cy - py * half)
        close()
    }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF212121.toInt()
        style = Paint.Style.FILL
    }
    val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = r * 0.012f
        strokeJoin = Paint.Join.ROUND
    }
    c.drawPath(path, fill)
    c.drawPath(path, edge)
    c.drawCircle(cx, cy, r * 0.045f, fill)
    c.drawCircle(cx, cy, r * 0.045f, edge)
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
