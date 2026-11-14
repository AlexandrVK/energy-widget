package com.example.energy

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
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

// Общая основа экранов с выпадающими списками
abstract class SpinnerScreen : Activity() {
    protected val queueLists = HashMap<Spinner, List<Queue>>()
    protected val namedLists = HashMap<Spinner, List<Named>>()

    protected fun newRoot(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(48, 48, 48, 48)
    }

    protected fun showRoot(root: LinearLayout) {
        setContentView(ScrollView(this).apply { addView(root) })
    }

    protected fun addLabel(root: LinearLayout, text: String, size: Float = 16f, topPad: Int = 32): TextView {
        val tv = TextView(this).apply {
            this.text = text
            textSize = size
            setPadding(0, topPad, 0, 8)
        }
        root.addView(tv)
        return tv
    }

    protected fun addRow(root: LinearLayout, label: String): Spinner {
        addLabel(root, label)
        val sp = Spinner(this)
        root.addView(sp, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return sp
    }

    protected fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    protected fun fillQueues(sp: Spinner, items: List<Queue>, selectedId: Int) {
        queueLists[sp] = items
        sp.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, items.map { it.name }
        )
        sp.setSelection(items.indexOfFirst { it.id == selectedId }.coerceAtLeast(0))
    }

    protected fun fillNamed(sp: Spinner, items: List<Named>, selectedId: Int) {
        namedLists[sp] = items
        sp.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, items.map { it.name }
        )
        sp.setSelection(items.indexOfFirst { it.id == selectedId }.coerceAtLeast(0))
    }

    protected fun chosenQueue(sp: Spinner): Queue? =
        queueLists[sp]?.getOrNull(sp.selectedItemPosition)

    protected fun chosenNamed(sp: Spinner): Named? =
        namedLists[sp]?.getOrNull(sp.selectedItemPosition)

    // Вызывает action с ID выбранного пункта при каждом изменении выбора
    protected fun onPick(sp: Spinner, action: (Int) -> Unit) {
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                action(chosenNamed(sp)?.id ?: -1)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
}

// Главный экран настроек: версия, обновление и переход к разделам
class SettingsActivity : Activity() {
    private lateinit var banner: TextView
    private lateinit var updateBtn: Button

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
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

        root.addView(Button(this).apply {
            text = "Графики отключений (ГПВ, ГАВ, СГАВ)"
            setOnClickListener { openScreen(GroupSettingsActivity::class.java) }
        })
        root.addView(Button(this).apply {
            text = "Аварийные отключения (дом)"
            setOnClickListener { openScreen(HouseSettingsActivity::class.java) }
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        renderUpdate()
    }

    private fun openScreen(cls: Class<*>) {
        startActivity(Intent(this, cls))
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
}

// Экран графиков: ГПВ, ГАВ, СГАВ и периодичность обновления
class GroupSettingsActivity : SpinnerScreen() {
    private val intervals = listOf(15, 30, 60)
    private lateinit var gpv: Spinner
    private lateinit var gav: Spinner
    private lateinit var sgav: Spinner
    private lateinit var interval: Spinner

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val s = loadSettings(this)
        val root = newRoot()
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
        showRoot(root)

        Thread {
            try {
                val g = fetchQueues(TYPE_GPV)
                val a = fetchQueues(TYPE_GAV)
                val sg = fetchQueues(TYPE_SGAV)
                runOnUiThread {
                    fillQueues(gpv, g, s.gpvId)
                    fillQueues(gav, a, s.gavId)
                    fillQueues(sgav, sg, s.sgavId)
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Не удалось загрузить списки групп") }
            }
        }.start()
    }

    private fun save() {
        if (queueLists.size < 3) {
            toast("Подождите, списки ещё загружаются")
            return
        }
        val g = chosenQueue(gpv)
        saveSettings(this, Settings(
            gpvId = g?.id ?: -1,
            gpvName = g?.name ?: "",
            gavId = chosenQueue(gav)?.id ?: -1,
            sgavId = chosenQueue(sgav)?.id ?: -1,
            intervalMin = intervals[interval.selectedItemPosition]
        ))
        schedule(this)
        updateAll(this)
        finish()
    }
}

// Экран дома для аварийных отключений: населённый пункт → улица → дом
class HouseSettingsActivity : SpinnerScreen() {
    private lateinit var citySp: Spinner
    private lateinit var streetSp: Spinner
    private lateinit var houseSp: Spinner
    private lateinit var houseLabel: TextView
    private var loadedCity = -1
    private var loadedStreet = -1

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = newRoot()
        addLabel(root, "Дом для аварийных отключений", 18f, 0)
        houseLabel = addLabel(root, "", 14f, 8)
        renderHouseLabel()
        citySp = addRow(root, "Населённый пункт")
        streetSp = addRow(root, "Улица")
        houseSp = addRow(root, "Дом")
        root.addView(Button(this).apply {
            text = "Сохранить"
            setOnClickListener { save() }
        })
        showRoot(root)
        loadHouseLists()
    }

    private fun renderHouseLabel() {
        val label = prefs(this).getString("house_label", "") ?: ""
        houseLabel.text = if (label.isEmpty()) "Дом не выбран" else "Текущий дом: $label"
    }

    // Город → улицы → дом: каждый уровень грузится при выборе предыдущего
    private fun loadHouseLists() {
        val p = prefs(this)
        val savedCity = p.getInt("city_id", -1)
        val savedStreet = p.getInt("street_id", -1)
        val savedHouse = p.getInt("house_id", -1)
        loadedCity = savedCity
        loadedStreet = savedStreet

        onPick(citySp) { cid ->
            if (cid >= 0 && cid != loadedCity) {
                loadedCity = cid
                loadStreets(cid)
            }
        }
        onPick(streetSp) { sid ->
            if (sid >= 0 && sid != loadedStreet) {
                loadedStreet = sid
                loadHouses(sid)
            }
        }

        Thread {
            try {
                val cities = fetchCities()
                val streets = if (savedCity >= 0) fetchStreets(savedCity) else emptyList()
                val houses = if (savedStreet >= 0) fetchHouses(savedStreet) else emptyList()
                runOnUiThread {
                    fillNamed(citySp, cities, savedCity)
                    fillNamed(streetSp, streets, savedStreet)
                    fillNamed(houseSp, houses, savedHouse)
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Не удалось загрузить адреса") }
            }
        }.start()
    }

    private fun loadStreets(cityId: Int) {
        Thread {
            try {
                val streets = fetchStreets(cityId)
                runOnUiThread {
                    fillNamed(houseSp, emptyList(), -1)
                    fillNamed(streetSp, streets, -1)
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Не удалось загрузить улицы") }
            }
        }.start()
    }

    private fun loadHouses(streetId: Int) {
        Thread {
            try {
                val houses = fetchHouses(streetId)
                runOnUiThread { fillNamed(houseSp, houses, -1) }
            } catch (e: Exception) {
                runOnUiThread { toast("Не удалось загрузить дома") }
            }
        }.start()
    }

    private fun save() {
        if (namedLists.size < 3) {
            toast("Подождите, адреса ещё загружаются")
            return
        }
        val street = chosenNamed(streetSp)
        val house = chosenNamed(houseSp)
        prefs(this).edit()
            .putInt("city_id", chosenNamed(citySp)?.id ?: -1)
            .putInt("street_id", street?.id ?: -1)
            .putInt("house_id", house?.id ?: -1)
            .putString("house_label",
                if (street != null && house != null) "${street.name}, ${house.name}" else "")
            .apply()
        Thread {
            refreshEmergency(applicationContext)
            updateAll(applicationContext)
        }.start()
        finish()
    }
}
