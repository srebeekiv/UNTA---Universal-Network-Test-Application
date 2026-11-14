package org.unta.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import org.json.JSONObject
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.io.File
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var summary: TextView
    private lateinit var checkBtn: Button
    private lateinit var historyRoot: LinearLayout
    private lateinit var histInfo: TextView
    private lateinit var histSearch: EditText
    private lateinit var histAdapter: BaseAdapter
    private lateinit var tabMeasure: Button
    private lateinit var tabHistory: Button
    private var histAll: List<HistoryItem> = emptyList()
    private val histShown = ArrayList<HistoryItem>()
    private var tab = 0
    private lateinit var status: TextView
    private lateinit var bigSpeed: TextView
    private lateinit var bar: ProgressBar
    private lateinit var barText: TextView
    private lateinit var graphsBox: LinearLayout
    private var curGraph: SpeedGraph? = null
    private var curSub: TextView? = null
    private val sessionRecs = ArrayList<JSONObject>()
    private var running = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("unta", MODE_PRIVATE)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)) }
        fun label(t: String) = TextView(this).apply { text = t; textSize = 13f; setPadding(0, dp(8), 0, 0) }.also { col.addView(it) }
        fun num(v: Int) = EditText(this).apply { setText(v.toString()); textSize = 14f; inputType = InputType.TYPE_CLASS_NUMBER }.also { col.addView(it) }

        summary = TextView(this).apply {
            textSize = 13f; setPadding(0, 0, 0, dp(4))
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }.also { col.addView(it) }
        val direct = CheckBox(this).apply { text = "Напрямую (без прокси)"; isChecked = prefs.getBoolean("direct", true) }.also { col.addView(it) }
        label("Сервер")
        val spin = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, Servers.all.map { it.label })
            setSelection(prefs.getInt("server", 0).coerceIn(0, Servers.all.size - 1))
        }.also { col.addView(it) }
        label("Потоков")
        val threads = num(prefs.getInt("threads", 4))
        label("Секунд на каждый этап (скачивание, отдача)")
        val secs = num(prefs.getInt("secs", 10))
        val run = Button(this).apply { text = "ИЗМЕРИТЬ" }.also { col.addView(it) }
        checkBtn = Button(this).apply { text = "БЫСТРАЯ ПРОВЕРКА ПРОКСИ" }.also { col.addView(it) }

        status = TextView(this).apply { text = "Готов к измерению"; textSize = 14f; setPadding(0, dp(10), 0, 0) }.also { col.addView(it) }
        bigSpeed = TextView(this).apply { text = "—"; textSize = 30f; typeface = Typeface.DEFAULT_BOLD }.also { col.addView(it) }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }.also { col.addView(it) }
        barText = TextView(this).apply { text = "0%"; textSize = 12f }.also { col.addView(it) }
        graphsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(graphsBox)

        val logBtns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        logBtns.addView(Button(this).apply { text = "Очистить лог"; setOnClickListener { logView.text = ""; graphsBox.removeAllViews(); sessionRecs.clear(); curGraph = null; curSub = null } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        logBtns.addView(Button(this).apply {
            text = "Копировать лог"
            setOnClickListener {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("UNTA", logView.text))
                Toast.makeText(this@MainActivity, "Лог скопирован", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        logBtns.addView(Button(this).apply {
            text = "Поделиться…"
            setOnClickListener {
                if (sessionRecs.isEmpty()) Toast.makeText(this@MainActivity, "Сначала сделай замер", Toast.LENGTH_SHORT).show()
                else ShareBuilder.show(this@MainActivity, sessionRecs.toList())
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(logBtns)

        logView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f; setTextIsSelectable(true); setPadding(0, dp(4), 0, 0) }
        col.addView(logView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        scroll = ScrollView(this).apply { addView(col) }
        buildHistoryUi()
        tabMeasure = Button(this).apply { text = "ЗАМЕР"; setOnClickListener { showTab(0) } }
        tabHistory = Button(this).apply { text = "ИСТОРИЯ"; setOnClickListener { showTab(1) } }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabs.addView(tabMeasure, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tabs.addView(tabHistory, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val frame = FrameLayout(this)
        frame.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(historyRoot, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(tabs)
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        showTab(0)

        val need = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager())
            log("Нет доступа ко всем файлам: результаты пойдут в папку приложения. Выдай доступ в Настройках.")

        run.setOnClickListener {
            if (running) { Engine.cancel.set(true); run.isEnabled = false; log("Останавливаю..."); return@setOnClickListener }
            val srv = Servers.all[spin.selectedItemPosition]
            val th = threads.text.toString().toIntOrNull()?.coerceIn(1, 32) ?: 4
            val sc = secs.text.toString().toIntOrNull()?.coerceIn(3, 120) ?: 10
            prefs.edit().putBoolean("direct", direct.isChecked).putInt("server", spin.selectedItemPosition)
                .putInt("threads", th).putInt("secs", sc).apply()
            val entries = Prefs.proxies(this).filter { it.enabled }
            val specs = ArrayList<ProxySpec>()
            for (e in entries) {
                val s = e.toSpec()
                if (s == null) log("Пропущен прокси с неверным адресом: «${e.addr}»") else specs.add(s)
            }
            if (!direct.isChecked && specs.isEmpty()) { log("Выбери «напрямую» или включи прокси в Настройках."); return@setOnClickListener }
            Engine.cancel.set(false); running = true; run.text = "ОСТАНОВИТЬ"; checkBtn.isEnabled = false
            if (logView.text.isNotEmpty()) log("\n----------------------------------------")
            thread {
                try { measure(srv, th, sc, direct.isChecked, specs) }
                catch (e: Throwable) { log("ОШИБКА: ${e.javaClass.simpleName}: ${e.message}") }
                ui.post { running = false; run.isEnabled = true; run.text = "ИЗМЕРИТЬ"; checkBtn.isEnabled = true }
            }
        }

        checkBtn.setOnClickListener {
            if (running) return@setOnClickListener
            val specs = ArrayList<ProxySpec>()
            for (e in Prefs.proxies(this).filter { it.enabled }) {
                val sp = e.toSpec()
                if (sp == null) log("Пропущен прокси с неверным адресом: «${e.addr}»") else specs.add(sp)
            }
            if (specs.isEmpty()) { log("Нет включённых прокси. Добавь их в Настройках."); return@setOnClickListener }
            running = true; run.isEnabled = false; checkBtn.isEnabled = false
            if (logView.text.isNotEmpty()) log("\n----------------------------------------")
            thread {
                try { proxyCheck(specs) }
                catch (e: Throwable) { log("ОШИБКА: ${e.javaClass.simpleName}: ${e.message}") }
                ui.post { running = false; run.isEnabled = true; checkBtn.isEnabled = true }
            }
        }
    }

    private fun showTab(i: Int) {
        tab = i
        scroll.visibility = if (i == 0) View.VISIBLE else View.GONE
        historyRoot.visibility = if (i == 1) View.VISIBLE else View.GONE
        tabMeasure.alpha = if (i == 0) 1f else 0.5f
        tabHistory.alpha = if (i == 1) 1f else 0.5f
        if (i == 1) loadHistory()
    }

    private fun buildHistoryUi() {
        historyRoot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), 0) }
        histSearch = EditText(this).apply { hint = "Поиск: точка, оператор, сервер, прокси"; setSingleLine(); textSize = 14f }
        histSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { applyFilter() }
        })
        histInfo = TextView(this).apply { textSize = 12f; setPadding(0, dp(2), 0, dp(4)) }
        histAdapter = object : BaseAdapter() {
            override fun getCount() = histShown.size
            override fun getItem(position: Int) = histShown[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val row = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8))
                    addView(TextView(this@MainActivity).apply { textSize = 15f; typeface = Typeface.DEFAULT_BOLD })
                    addView(TextView(this@MainActivity).apply { textSize = 13f; alpha = 0.75f })
                }
                val it = histShown[position]
                (row.getChildAt(0) as TextView).text = it.title
                (row.getChildAt(1) as TextView).text = it.subtitle
                return row
            }
        }
        val list = ListView(this).apply {
            adapter = histAdapter
            setOnItemClickListener { _, _, pos, _ ->
                startActivity(Intent(this@MainActivity, DetailActivity::class.java).putExtra("path", histShown[pos].file.absolutePath))
            }
        }
        historyRoot.addView(histSearch)
        historyRoot.addView(histInfo)
        historyRoot.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun applyFilter() {
        val q = histSearch.text.toString().trim().lowercase()
        histShown.clear()
        histShown.addAll(if (q.isEmpty()) histAll else histAll.filter { it.search.contains(q) })
        histAdapter.notifyDataSetChanged()
        histInfo.text = "Записей: ${histShown.size} из ${histAll.size}"
    }

    private fun loadHistory() {
        histInfo.text = "Загрузка..."
        val base = Report.baseDir(this, Prefs.path(this))
        thread {
            val items = History.load(base)
            ui.post { histAll = items; applyFilter() }
        }
    }

    override fun onResume() {
        super.onResume()
        if (tab == 1) loadHistory()
        val all = Prefs.proxies(this)
        summary.text = "Прокси включено: ${all.count { it.enabled }} из ${all.size}\nПапка: …/${Prefs.path(this).trimEnd('/').split('/').takeLast(2).joinToString("/")}/tests\n(нажми сюда или «Настройки» вверху)"
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Настройки").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) { startActivity(Intent(this, SettingsActivity::class.java)); return true }
        return super.onOptionsItemSelected(item)
    }

    /** Для каждого нового замера свой график ниже предыдущего, с заголовком: что за замер. */
    private fun addGraphCard(title: String, secs: Int, phases: Int) {
        val t = TextView(this).apply { text = title; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(14), 0, 0) }
        val sub = TextView(this).apply { text = "идёт замер..."; textSize = 12f; alpha = 0.75f }
        val g = SpeedGraph(this).apply { reset(secs, phases) }
        graphsBox.addView(t)
        graphsBox.addView(sub)
        graphsBox.addView(g, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        curGraph = g; curSub = sub
    }

    private fun proxyCheck(specs: List<ProxySpec>) {
        val whenMs = System.currentTimeMillis()
        val base = Report.baseDir(this, Prefs.path(this))
        val info = NetInfo.collect(this)
        ui.post { status.text = "Быстрая проверка прокси"; bigSpeed.text = "—" }
        log("Сеть: ${Report.pointName(info)} (${info.optString("transport")})")
        log("Быстрая проверка ${specs.size} прокси (параллельно, без замера скорости)\n")
        setBar(0.0, "")
        val results = Engine.checkProxies(specs) { n, total, r ->
            log("  [$n/$total] " + ProxyReport.line(r))
            setBar(n.toDouble() / total, "($n из $total)")
        }
        val ok = results.count { it.ok }
        ui.post { bigSpeed.text = "$ok из ${results.size} работают"; status.text = "Готово" }
        log("\nИтог: работает $ok из ${results.size}")
        val (json, txt) = ProxyReport.build(info, results, whenMs)
        val files = Report.save(base, false, ProxyReport.name(whenMs, info), json, txt)
        log("файл: ${files[0].name}")
    }

    private fun setBar(v: Double, text: String) = ui.post {
        bar.progress = (v.coerceIn(0.0, 1.0) * 1000).toInt()
        barText.text = "${(v.coerceIn(0.0, 1.0) * 100).toInt()}%  $text"
    }

    private fun f2(v: Double?) = v?.let { "%.2f".format(it) } ?: "—"

    private fun measure(srv: Server, th: Int, sc: Int, direct: Boolean, specs: List<ProxySpec>) {
        val base = Report.baseDir(this, Prefs.path(this))
        val info = NetInfo.collect(this)
        log("Сеть: ${Report.pointName(info)} (${info.optString("transport")})")
        log("Сервер: ${srv.label}, потоков $th, по $sc с на этап\n")
        val jobs = ArrayList<ProxySpec?>()
        if (direct) jobs.add(null)
        jobs.addAll(specs)
        val n = jobs.size
        val hasUl = srv.ul != null
        for ((i, p) in jobs.withIndex()) {
            if (Engine.cancel.get()) { log("Остановлено."); break }
            val whenMs = System.currentTimeMillis()
            val title = if (p == null) "Напрямую" else "Прокси " + p.label() + if (p.comment.isNotEmpty()) " (${p.comment})" else ""
            log("== [${i + 1}/$n] $title")
            ui.post { addGraphCard("[${i + 1}/$n] $title → ${srv.label}", sc, if (hasUl) 2 else 1); bigSpeed.text = "—" }
            fun leg(ph: String, f: Double) = when (ph) {
                "check" -> 0.03
                "download" -> 0.05 + (if (hasUl) 0.45 else 0.95) * f
                else -> 0.5 + 0.5 * f
            }
            val pr = Engine.Progress(
                phase = { ph ->
                    val txt = when (ph) { "check" -> "проверка сервера"; "download" -> "скачивание"; else -> "отдача" }
                    ui.post { status.text = "[${i + 1}/$n] $title: $txt" }
                    setBar((i + leg(ph, 0.0)) / n, "(${i + 1} из $n)")
                },
                sample = { ph, mbps, frac ->
                    ui.post {
                        curGraph?.add(ph, mbps, frac)
                        bigSpeed.text = (if (ph == "upload") "↑ " else "↓ ") + "%.1f Мбит/с".format(mbps)
                    }
                    setBar((i + leg(ph, frac)) / n, "(${i + 1} из $n)")
                }
            )
            val r = Engine.runLeg(title, p, srv, th, sc, ::log, pr)
            if (Engine.cancel.get()) { log("  остановлено, результат не сохранён"); break }
            val ex = r.exit
            val geoStr = if (ex != null && ex.optString("clientIp").isNotEmpty()) HistoryFmt.exitLine(ex) else ""
            if (!r.reachable) log("  ✗ ${r.error}")
            else {
                if (geoStr.isNotEmpty()) log("  внешний IP и гео по IP: $geoStr")
                ui.post { bigSpeed.text = "↓ ${f2(r.dlMbps)}   ↑ ${f2(r.ulMbps)}  Мбит/с" }
            }
            val subTxt = (if (r.reachable) "↓ ${f2(r.dlMbps)}  ↑ ${f2(r.ulMbps)} Мбит/с, отклик ${r.pingMs} мс" else "✗ ${r.error}") +
                (if (geoStr.isNotEmpty()) "\nIP: $geoStr" else "")
            ui.post { curSub?.text = subTxt }
            setBar((i + 1.0) / n, "(${i + 1} из $n)")
            val (json, txt) = Report.build(this, info, listOf(r), srv, th, sc, false, whenMs)
            val files = Report.save(base, false, Report.fileName(whenMs, info, r, srv), json, txt)
            ui.post { sessionRecs.add(json) }
            log("  файл: ${files[0].name}\n")
        }
        ui.post { status.text = if (Engine.cancel.get()) "Остановлено" else "Готово" }
        log("Папка: ${File(base, "tests").absolutePath}")
    }

    private fun log(s: String) = ui.post {
        logView.append(s + "\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
