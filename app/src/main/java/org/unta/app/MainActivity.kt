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

        logView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f; setTextIsSelectable(true); setPadding(0, dp(8), 0, 0) }
        col.addView(logView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        scroll = ScrollView(this).apply { addView(col) }
        setContentView(scroll)

        val need = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager())
            log("Нет доступа ко всем файлам: результаты пойдут в папку приложения. Выдай доступ в Настройках.")

        run.setOnClickListener {
            val srv = Servers.all[spin.selectedItemPosition]
            val th = threads.text.toString().toIntOrNull()?.coerceIn(1, 32) ?: 4
            val sc = secs.text.toString().toIntOrNull()?.coerceIn(3, 120) ?: 10
            prefs.edit().putBoolean("direct", direct.isChecked).putInt("server", spin.selectedItemPosition)
                .putInt("threads", th).putInt("secs", sc).apply()
            val entries = Prefs.proxies(this).filter { it.enabled }
            val specs = ArrayList<ProxySpec>()
            logView.text = ""
            for (e in entries) {
                val s = e.toSpec()
                if (s == null) log("Пропущен прокси с неверным адресом: «${e.addr}»") else specs.add(s)
            }
            if (!direct.isChecked && specs.isEmpty()) { log("Выбери «напрямую» или включи прокси в Настройках."); return@setOnClickListener }
            run.isEnabled = false
            thread {
                try { measure(srv, th, sc, direct.isChecked, specs) }
                catch (e: Throwable) { log("ОШИБКА: ${e.javaClass.simpleName}: ${e.message}") }
                ui.post { run.isEnabled = true }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val all = Prefs.proxies(this)
        summary.text = "Прокси включено: ${all.count { it.enabled }} из ${all.size}\nПапка: ${Prefs.path(this)}/tests\n(нажми сюда или «Настройки» вверху)"
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Настройки").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) { startActivity(Intent(this, SettingsActivity::class.java)); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun measure(srv: Server, th: Int, sc: Int, direct: Boolean, specs: List<ProxySpec>) {
        val base = Report.baseDir(this, Prefs.path(this))
        val info = NetInfo.collect(this)
        log("Сеть: ${Report.pointName(info)} (${info.optString("transport")})")
        log("Сервер: ${srv.label}, потоков $th, по $sc с на этап\n")
        val jobs = ArrayList<ProxySpec?>()
        if (direct) jobs.add(null)
        jobs.addAll(specs)
        for (p in jobs) {
            val whenMs = System.currentTimeMillis()
            val title = if (p == null) "Напрямую" else "Прокси " + p.label() + if (p.comment.isNotEmpty()) " (${p.comment})" else ""
            log("== $title")
            val r = Engine.runLeg(title, p, srv, th, sc, ::log)
            val (json, txt) = Report.build(this, info, listOf(r), srv, th, sc, false, whenMs)
            val files = Report.save(base, false, Report.fileName(whenMs, info, r, srv), json, txt)
            log("  файл: ${files[0].name}\n")
        }
        log("Папка: ${File(base, "tests").absolutePath}")
    }

    private fun log(s: String) = ui.post {
        logView.append(s + "\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
