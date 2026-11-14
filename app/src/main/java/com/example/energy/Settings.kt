package com.example.energy

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
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
        sgavId = -1,                       // СГАВ не выбирается, см. SGAV_IDS
        intervalMin = p.getInt("interval", 15).coerceAtLeast(15)
    )
}

// Один экран настроек: обновление приложения, интервал и адрес.
// ГПВ и ГАВ определяются по адресу и только показываются на циферблате.
class SettingsActivity : Activity() {
    private val intervals = listOf(15, 30, 60)
    private val nameItems = HashMap<AutoCompleteTextView, List<Named>>()
    private var loadedCity = -1
    private var loadedStreet = -1

    private lateinit var status: TextView
    private lateinit var checkBtn: Button
    private lateinit var updateBtn: Button
    private lateinit var intervalSp: Spinner
    private lateinit var citySearch: AutoCompleteTextView
    private lateinit var streetSearch: AutoCompleteTextView
    private lateinit var houseSearch: AutoCompleteTextView
    private lateinit var currentLabel: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val s = loadSettings(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        status = TextView(this).apply { textSize = 13f }
        root.addView(status)

        val btnRow = row(root)
        checkBtn = Button(this).apply {
            text = "Проверить"
            setOnClickListener { checkNow() }
        }
        updateBtn = Button(this).apply {
            text = "Обновить"
            setOnClickListener { installNow() }
        }
        btnRow.addView(checkBtn, weightedBy(1f))
        btnRow.addView(updateBtn, weightedBy(1f))

        val intRow = row(root)
        intRow.addView(TextView(this).apply {
            text = "Обновлять каждые, мин"
            textSize = 14f
        }, weightedBy(1f))
        intervalSp = Spinner(this)
        intervalSp.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, intervals.map { it.toString() }
        )
        intervalSp.setSelection(intervals.indexOf(s.intervalMin).coerceAtLeast(0))
        intRow.addView(intervalSp, weightedBy(1f))

        citySearch = search("Населённый пункт")
        root.addView(citySearch, matchWrap())

        val addrRow = row(root)
        streetSearch = search("Улица")
        houseSearch = search("Дом")
        addrRow.addView(streetSearch, weightedBy(2f))
        addrRow.addView(houseSearch, weightedBy(1f))

        currentLabel = TextView(this).apply {
            textSize = 13f
            setPadding(0, 16, 0, 16)
        }
        root.addView(currentLabel)

        root.addView(Button(this).apply {
            text = "Сохранить"
            setOnClickListener { save() }
        })

        setContentView(ScrollView(this).apply { addView(root) })

        onSelect(citySearch) { n ->
            if (n.id != loadedCity) {
                loadedCity = n.id
                loadStreets(n.id)
            }
        }
        onSelect(streetSearch) { n ->
            if (n.id != loadedStreet) {
                loadedStreet = n.id
                loadHouses(n.id)
            }
        }

        renderStatus()
        renderCurrent()
        loadAddress()
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
    }

    private fun row(root: LinearLayout): LinearLayout {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(r, matchWrap())
        return r
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun weightedBy(w: Float) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w).apply {
        setMargins(4, 4, 4, 4)
    }

    // Подпись поля — подсказка внутри самого поля, чтобы экономить высоту
    private fun search(h: String): AutoCompleteTextView = AutoCompleteTextView(this).apply {
        hint = h
        threshold = 1
        setSingleLine(true)
        textSize = 14f
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun renderStatus() {
        val local = installedBuild(this)
        val remote = prefs(this).getInt("remote_build", 0)
        val upd = when {
            remote == 0 -> "обновления не проверялись"
            remote > local -> "доступна сборка $remote"
            else -> "последняя версия"
        }
        status.text = "Версия ${installedVersionName(this)} (сборка $local): $upd"
        updateBtn.isEnabled = remote > local
    }

    private fun renderCurrent() {
        val p = prefs(this)
        val addr = p.getString("house_label", "") ?: ""
        if (addr.isEmpty()) {
            currentLabel.text = "Адрес не выбран: ГПВ и ГАВ не определены"
        } else {
            val gpv = (p.getString("gpv_name", "") ?: "").ifEmpty { "—" }
            val gav = (p.getString("gav_name", "") ?: "").ifEmpty { "—" }
            currentLabel.text = "Адрес: $addr\nГПВ $gpv · ГАВ $gav"
        }
    }

    // Сохранённый адрес: город → улица → дом
    private fun loadAddress() {
        val p = prefs(this)
        val savedCity = p.getInt("city_id", -1)
        val savedStreet = p.getInt("street_id", -1)
        val savedHouse = p.getInt("house_id", -1)
        loadedCity = savedCity
        loadedStreet = savedStreet
        Thread {
            try {
                val cities = fetchCities()
                val streets = if (savedCity >= 0) fetchStreets(savedCity) else emptyList()
                val houses = if (savedStreet >= 0) fetchHouses(savedStreet) else emptyList()
                runOnUiThread {
                    fillSearch(citySearch, cities, savedCity)
                    fillSearch(streetSearch, streets, savedStreet)
                    fillSearch(houseSearch, houses, savedHouse)
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
                    fillSearch(houseSearch, emptyList(), -1)
                    fillSearch(streetSearch, streets, -1)
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
                runOnUiThread { fillSearch(houseSearch, houses, -1) }
            } catch (e: Exception) {
                runOnUiThread { toast("Не удалось загрузить дома") }
            }
        }.start()
    }

    private fun fillSearch(v: AutoCompleteTextView, items: List<Named>, selectedId: Int) {
        nameItems[v] = items
        v.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, items.map { it.name }))
        v.setText(items.firstOrNull { it.id == selectedId }?.name ?: "", false)
    }

    // Точное совпадение по названию, иначе первый пункт, который начинается с введённого текста
    private fun resolve(v: AutoCompleteTextView, text: String): Named? {
        val items = nameItems[v] ?: return null
        val t = text.trim()
        if (t.isEmpty()) return null
        return items.firstOrNull { it.name.equals(t, ignoreCase = true) }
            ?: items.firstOrNull { it.name.startsWith(t, ignoreCase = true) }
    }

    private fun chosen(v: AutoCompleteTextView): Named? = resolve(v, v.text.toString())

    // Выбор из списка или уход с поля с введённым текстом
    private fun onSelect(v: AutoCompleteTextView, action: (Named) -> Unit) {
        v.setOnItemClickListener { _, _, pos, _ ->
            val name = v.adapter.getItem(pos).toString()
            resolve(v, name)?.let(action)
        }
        v.setOnFocusChangeListener { _, has ->
            if (!has) resolve(v, v.text.toString())?.let(action)
        }
    }

    private fun checkNow() {
        status.text = "Проверяю…"
        Thread {
            val ok = checkForUpdate(this) != null
            runOnUiThread {
                if (!ok) toast("Не удалось проверить обновления")
                renderStatus()
            }
        }.start()
    }

    private fun installNow() {
        val url = prefs(this).getString("remote_url", null) ?: return
        // Без этого разрешения Android не даст установить APK; открываем нужный экран
        if (!packageManager.canRequestPackageInstalls()) {
            toast("Разрешите установку для приложения, затем нажмите кнопку ещё раз")
            startActivity(Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES", Uri.parse("package:$packageName")))
            return
        }
        status.text = "Загрузка обновления…"
        Thread {
            try {
                val uri = downloadApk(this, url)
                runOnUiThread {
                    try {
                        startActivity(installIntent(uri))
                    } catch (e: ActivityNotFoundException) {
                        toast("Нет приложения для установки APK")
                    }
                    renderStatus()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    toast("Не удалось загрузить обновление")
                    renderStatus()
                }
            }
        }.start()
    }

    private fun save() {
        val p = prefs(this)
        val street = chosen(streetSearch)
        val house = chosen(houseSearch)
        val intervalMin = intervals[intervalSp.selectedItemPosition]

        // Дом не выбран — адрес и аварии сбрасываются, интервал сохраняется
        if (house == null) {
            p.edit()
                .putInt("interval", intervalMin)
                .putInt("city_id", -1)
                .putInt("street_id", -1)
                .putInt("house_id", -1)
                .putString("house_label", "")
                .putInt("gpv", -1).putString("gpv_name", "")
                .putInt("gav", -1).putString("gav_name", "")
                .putLong("em_finish", 0L)
                .apply()
            finishSave()
            return
        }

        // ГПВ и ГАВ запрашиваем только при смене дома
        val changed = house.id != p.getInt("house_id", -1)
        val cityId = chosen(citySearch)?.id ?: -1
        val streetId = street?.id ?: -1
        val label = if (street != null) "${street.name}, ${house.name}" else house.name

        Thread {
            val q: AddressQueues? = if (changed) {
                try {
                    fetchAddressQueues(house.id)
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }
            runOnUiThread {
                if (changed && q == null) {
                    // Не сохраняем новый адрес без ГПВ: остаёмся на экране, можно повторить
                    p.edit().putInt("interval", intervalMin).apply()
                    toast("Не удалось определить ГПВ по адресу, нажмите «Сохранить» ещё раз")
                } else {
                    val edit = p.edit()
                        .putInt("interval", intervalMin)
                        .putInt("city_id", cityId)
                        .putInt("street_id", streetId)
                        .putInt("house_id", house.id)
                        .putString("house_label", label)
                    if (changed && q != null) {
                        edit.putInt("gpv", q.gpvId ?: -1)
                            .putString("gpv_name", q.gpvName ?: "")
                            .putInt("gav", q.gavId ?: -1)
                            .putString("gav_name", q.gavName ?: "")
                    }
                    edit.apply()
                    finishSave()
                }
            }
        }.start()
    }

    private fun finishSave() {
        schedule(this)
        Thread {
            refreshEmergency(applicationContext)
            updateAll(applicationContext)
        }.start()
        finish()
    }
}
