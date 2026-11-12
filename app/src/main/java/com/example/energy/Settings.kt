package com.example.energy

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

// gpvName нужен только для показа в диалоге отрезка; сравнение идёт по gpvId
class Settings(
    val gpvId: Int,
    val gpvName: String,
    val gavId: Int,
    val sgavId: Int,
    val intervalMin: Int
)

fun loadSettings(ctx: Context): Settings {
    val p = prefs(ctx)
    return Settings(
        gpvId = p.getInt("gpv", -1),
        gpvName = p.getString("gpv_name", "") ?: "",
        gavId = p.getInt("gav", -1),
        sgavId = p.getInt("sgav", -1),
        intervalMin = p.getInt("interval", 15).coerceAtLeast(15)
    )
}

fun saveSettings(ctx: Context, s: Settings) {
    prefs(ctx).edit()
        .putInt("gpv", s.gpvId)
        .putString("gpv_name", s.gpvName)
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
    private lateinit var banner: TextView
    private lateinit var updateBtn: Button

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val s = loadSettings(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        root.addView(TextView(this).apply {
            text = "Версия ${installedVersionName(this@SettingsActivity)}, " +
                "сборка ${installedBuild(this@SettingsActivity)}"
            textSize = 14f
        })
        banner = TextView(this).apply {
            textSize = 14f
            setPadding(0, 32, 0, 16)
        }
        root.addView(banner)
        root.addView(Button(this).apply {
            text = "Проверить сейчас"
            setOnClickListener { checkNow() }
        })
        updateBtn = Button(this).apply {
            text = "Обновить приложение"
            setOnClickListener { installNow() }
        }
        root.addView(updateBtn)

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

        renderUpdate()
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

    private fun renderUpdate() {
        val local = installedBuild(this)
        val remote = prefs(this).getInt("remote_build", 0)
        banner.text = when {
            remote == 0 -> "Обновления ещё не проверялись"
            remote > local -> "Доступна новая версия (сборка $remote)"
            else -> "Установлена последняя версия"
        }
        updateBtn.isEnabled = remote > local
    }

    private fun checkNow() {
        banner.text = "Проверяю…"
        Thread {
            val ok = checkForUpdate(this) != null
            runOnUiThread {
                if (!ok) Toast.makeText(this, "Не удалось проверить обновления", Toast.LENGTH_LONG).show()
                renderUpdate()
            }
        }.start()
    }

    private fun installNow() {
        val url = prefs(this).getString("remote_url", null) ?: return
        // Без этого разрешения Android не даст установить APK; открываем нужный экран
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(
                this, "Разрешите установку для приложения, затем нажмите кнопку ещё раз",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES", Uri.parse("package:$packageName")))
            return
        }
        banner.text = "Загрузка обновления…"
        Thread {
            try {
                val uri = downloadApk(this, url)
                runOnUiThread {
                    try {
                        startActivity(installIntent(uri))
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(this, "Нет приложения для установки APK", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Не удалось загрузить обновление", Toast.LENGTH_LONG).show()
                    renderUpdate()
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

    private fun chosen(sp: Spinner): Queue? = lists[sp]?.getOrNull(sp.selectedItemPosition)

    private fun save() {
        if (lists.size < 3) {
            Toast.makeText(this, "Подождите, списки ещё загружаются", Toast.LENGTH_SHORT).show()
            return
        }
        val g = chosen(gpv)
        saveSettings(this, Settings(
            gpvId = g?.id ?: -1,
            gpvName = g?.name ?: "",
            gavId = chosen(gav)?.id ?: -1,
            sgavId = chosen(sgav)?.id ?: -1,
            intervalMin = intervals[interval.selectedItemPosition]
        ))
        schedule(this)
        updateAll(this)
        finish()
    }
}
