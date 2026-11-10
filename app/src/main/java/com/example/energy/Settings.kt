package com.example.energy

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class Settings(val gpvId: Int, val gavId: Int, val sgavId: Int, val intervalMin: Int)

fun loadSettings(ctx: Context): Settings {
    val p = prefs(ctx)
    return Settings(
        gpvId = p.getInt("gpv", -1),
        gavId = p.getInt("gav", -1),
        sgavId = p.getInt("sgav", -1),
        intervalMin = p.getInt("interval", 15).coerceAtLeast(15)
    )
}

fun saveSettings(ctx: Context, s: Settings) {
    prefs(ctx).edit()
        .putInt("gpv", s.gpvId)
        .putInt("gav", s.gavId)
        .putInt("sgav", s.sgavId)
        .putInt("interval", s.intervalMin)
        .apply()
}

class SettingsActivity : Activity() {
    private val intervals = listOf(15, 30, 60)
    private val lists = HashMap<Spinner, List<Queue>>()
    private lateinit var gpv: Spinner
    private lateinit var gav: Spinner
    private lateinit var sgav: Spinner
    private lateinit var interval: Spinner

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val s = loadSettings(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        gpv = addRow(root, "ГПВ")
        gav = addRow(root, "ГАВ")
        sgav = addRow(root, "СГАВ")
        interval = addRow(root, "Обновлять каждые, мин")
        interval.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, intervals.map { it.toString() }
        )
        interval.setSelection(intervals.indexOf(s.intervalMin).coerceAtLeast(0))
        root.addView(Button(this).apply {
            text = "Сохранить"
            setOnClickListener { save() }
        })
        setContentView(ScrollView(this).apply { addView(root) })

        Thread {
            try {
                val g = fetchQueues(TYPE_GPV)
                val a = fetchQueues(TYPE_GAV)
                val sg = fetchQueues(TYPE_SGAV)
                runOnUiThread {
                    fill(gpv, g, s.gpvId)
                    fill(gav, a, s.gavId)
                    fill(sgav, sg, s.sgavId)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Не удалось загрузить списки групп", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun addRow(root: LinearLayout, label: String): Spinner {
        root.addView(TextView(this).apply {
            text = label
            textSize = 16f
            setPadding(0, 32, 0, 8)
        })
        val sp = Spinner(this)
        root.addView(sp, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return sp
    }

    private fun fill(sp: Spinner, items: List<Queue>, selectedId: Int) {
        lists[sp] = items
        sp.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, items.map { it.name }
        )
        sp.setSelection(items.indexOfFirst { it.id == selectedId }.coerceAtLeast(0))
    }

    private fun chosenId(sp: Spinner): Int =
        lists[sp]?.getOrNull(sp.selectedItemPosition)?.id ?: -1

    private fun save() {
        if (lists.size < 3) {
            Toast.makeText(this, "Подождите, списки ещё загружаются", Toast.LENGTH_SHORT).show()
            return
        }
        saveSettings(this, Settings(
            chosenId(gpv), chosenId(gav), chosenId(sgav), intervals[interval.selectedItemPosition]
        ))
        schedule(this)
        updateAll(this)
        finish()
    }
}
