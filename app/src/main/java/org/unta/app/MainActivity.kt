package org.unta.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
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
    private lateinit var status: TextView
    private lateinit var bigSpeed: TextView
    private lateinit var bar: ProgressBar
    private lateinit var barText: TextView
    private lateinit var graph: SpeedGraph
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

        status = TextView(this).apply { text = "Готов к измерению"; textSize = 14f; setPadding(0, dp(10), 0, 0) }.also { col.addView(it) }
        bigSpeed = TextView(this).apply { text = "—"; textSize = 30f; typeface = Typeface.DEFAULT_BOLD }.also { col.addView(it) }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }.also { col.addView(it) }
        barText = TextView(this).apply { text = "0%"; textSize = 12f }.also { col.addView(it) }
        graph = SpeedGraph(this)
        col.addView(graph, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        val logBtns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        logBtns.addView(Button(this).apply { text = "Очистить лог"; setOnClickListener { logView.text = "" } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        logBtns.addView(Button(this).apply {
            text = "Копировать лог"
            setOnClickListener {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("UNTA", logView.text))
                Toast.makeText(this@MainActivity, "Лог скопирован", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(logBtns)

        logView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f; setTextIsSelectable(true); setPadding(0, dp(4), 0, 0) }
        col.addView(logView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        scroll = ScrollView(this).apply { addView(col) }
        setContentView(scroll)

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
            Engine.cancel.set(false); running = true; run.text = "ОСТАНОВИТЬ"
            if (logView.text.isNotEmpty()) log("\n----------------------------------------")
            thread {
                try { measure(srv, th, sc, direct.isChecked, specs) }
                catch (e: Throwable) { log("ОШИБКА: ${e.javaClass.simpleName}: ${e.message}") }
                ui.post { running = false; run.isEnabled = true; run.text = "ИЗМЕРИТЬ" }
            }
        }
    }

    override fun onResume() {
        super.onResume()
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
            ui.post { graph.reset(sc, if (hasUl) 2 else 1); bigSpeed.text = "—" }
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
                        graph.add(ph, mbps, frac)
                        bigSpeed.text = (if (ph == "upload") "↑ " else "↓ ") + "%.1f Мбит/с".format(mbps)
                    }
                    setBar((i + leg(ph, frac)) / n, "(${i + 1} из $n)")
                }
            )
            val r = Engine.runLeg(title, p, srv, th, sc, ::log, pr)
            if (Engine.cancel.get()) { log("  остановлено, результат не сохранён"); break }
            if (!r.reachable) log("  ✗ ${r.error}")
            else {
                val e = r.exit
                if (e != null && e.optString("clientIp").isNotEmpty())
                    log("  внешний IP: ${e.optString("clientIp")} ${listOf(e.optString("city"), e.optString("country")).filter { it.isNotEmpty() }.joinToString(", ")}")
                ui.post { bigSpeed.text = "↓ ${f2(r.dlMbps)}   ↑ ${f2(r.ulMbps)}  Мбит/с" }
            }
            setBar((i + 1.0) / n, "(${i + 1} из $n)")
            val (json, txt) = Report.build(this, info, listOf(r), srv, th, sc, false, whenMs)
            val files = Report.save(base, false, Report.fileName(whenMs, info, r, srv), json, txt)
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
