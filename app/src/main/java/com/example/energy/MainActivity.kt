package com.example.energy

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.atan2
import kotlin.math.sqrt

// Полноэкранный циферблат: тап по зоне показывает отрезок того же статуса
class DialView(ctx: Context) : View(ctx) {
    var day: Day? = null
    var gpvId = -1
    var badges: List<String> = emptyList()
    var onSlot: ((Int) -> Unit)? = null

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height).toFloat()
        c.translate((width - s) / 2f, (height - s) / 2f)
        drawDial(c, s, day, gpvId, System.currentTimeMillis(), badges)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) {
            val s = minOf(width, height).toFloat()
            val dx = e.x - width / 2f
            val dy = e.y - height / 2f
            if (sqrt(dx * dx + dy * dy) <= s / 2f) {
                var deg = Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())).toFloat()
                if (deg < 0f) deg += 360f
                onSlot?.invoke((deg / 7.5f).toInt().coerceIn(0, SLOTS - 1))
            }
            performClick()
        }
        return true
    }
}

class MainActivity : Activity() {
    private lateinit var dial: DialView
    private lateinit var hint: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        dial = DialView(this).apply { onSlot = { showSlot(it) } }
        hint = TextView(this).apply {
            textSize = 14f
            setPadding(32, 24, 32, 16)
        }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(
            button("Настройки") { startActivity(Intent(this, SettingsActivity::class.java)) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(
            button("Обновить") { refreshNow() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(hint)
        root.addView(dial, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(row)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { action() }
    }

    private fun reload() {
        val s = loadSettings(this)
        val now = System.currentTimeMillis()
        val day = currentDay(loadDays(this), now)
        dial.day = day
        dial.gpvId = s.gpvId
        dial.badges = badgesFor(day, now, s)
        dial.invalidate()
        hint.text = when {
            day == null -> "Нет данных на сегодня. Нажмите «Обновить»."
            s.gpvId < 0 -> "Выберите группу ГПВ в настройках."
            else -> "Нажмите на зону, чтобы увидеть время и статус."
        }
    }

    private fun refreshNow() {
        Thread {
            refreshSchedule(this)
            updateAll(this)
            runOnUiThread { reload() }
        }.start()
    }

    // Показывает весь непрерывный отрезок того же статуса, в который попал тап
    private fun showSlot(i: Int) {
        val day = dial.day ?: return
        val s = loadSettings(this)
        if (s.gpvId < 0) {
            AlertDialog.Builder(this)
                .setMessage("Группа не выбрана")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val status = day.statusAt(s.gpvId, i)
        var first = i
        while (first > 0 && day.statusAt(s.gpvId, first - 1) == status) first--
        var last = i
        while (last < SLOTS - 1 && day.statusAt(s.gpvId, last + 1) == status) last++
        val text = when (status) {
            PowerStatus.POWER_ON -> "свет есть"
            PowerStatus.POSSIBLE -> "возможно отключение"
            PowerStatus.OUTAGE -> "отключение"
        }
        AlertDialog.Builder(this)
            .setTitle("${slotTime(first)} – ${slotTime(last + 1)}")
            .setMessage("ГПВ ${s.gpvId}: $text")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun slotTime(slot: Int) = "%02d:%02d".format((slot / 2) % 24, (slot % 2) * 30)
}
