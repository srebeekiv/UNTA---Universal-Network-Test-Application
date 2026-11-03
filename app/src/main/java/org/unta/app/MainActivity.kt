package org.unta.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("unta", MODE_PRIVATE)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)) }
        fun label(t: String) = TextView(this).apply { text = t; textSize = 13f; setPadding(0, dp(8), 0, 0) }.also { col.addView(it) }
        fun edit(v: String, multi: Boolean = false, num: Boolean = false) = EditText(this).apply {
            setText(v); textSize = 14f
            if (multi) { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 3; typeface = Typeface.MONOSPACE }
            if (num) inputType = InputType.TYPE_CLASS_NUMBER
        }.also { col.addView(it) }

        label("Папка для результатов (её можно синхронизировать Syncthing)")
        val path = edit(prefs.getString("path", "/storage/emulated/0/UNTA")!!)
        label("Прокси, по одному в строке: [схема://][логин:пароль@]хост:порт  # комментарий")
        val proxies = edit(prefs.getString("proxies", "")!!, multi = true)
        val direct = CheckBox(this).apply { text = "Напрямую (без прокси)"; isChecked = prefs.getBoolean("direct", true) }.also { col.addView(it) }
        label("Сервер")
        val spin = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, Servers.all.map { it.label })
            setSelection(prefs.getInt("server", 0))
        }.also { col.addView(it) }
        label("Потоков")
        val threads = edit(prefs.getInt("threads", 4).toString(), num = true)
        label("Секунд на каждый этап (скачивание, отдача)")
        val secs = edit(prefs.getInt("secs", 10).toString(), num = true)

        val files = Button(this).apply {
            text = "Доступ ко всем файлам (для папки результатов)"
            setOnClickListener {
                try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
                catch (e: Exception) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            }
        }.also { col.addView(it) }
        val run = Button(this).apply { text = "ИЗМЕРИТЬ" }.also { col.addView(it) }

        logView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f; setTextIsSelectable(true); setPadding(0, dp(8), 0, 0) }
        col.addView(logView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        scroll = ScrollView(this).apply { addView(col) }
        setContentView(scroll)

        val need = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
        if (!Environment.isExternalStorageManager()) log("Нет доступа ко всем файлам: результаты пойдут в папку приложения. Нажми кнопку выше.")

        run.setOnClickListener {
            val srv = Servers.all[spin.selectedItemPosition]
            val th = threads.text.toString().toIntOrNull()?.coerceIn(1, 32) ?: 4
            val sc = secs.text.toString().toIntOrNull()?.coerceIn(3, 120) ?: 10
            val plist = ProxySpec.parseList(proxies.text.toString())
            prefs.edit().putString("path", path.text.toString()).putString("proxies", proxies.text.toString())
                .putBoolean("direct", direct.isChecked).putInt("server", spin.selectedItemPosition)
                .putInt("threads", th).putInt("secs", sc).apply()
            if (!direct.isChecked && plist.isEmpty()) { log("Выбери «напрямую» или добавь прокси."); return@setOnClickListener }
            run.isEnabled = false; logView.text = ""
            val base = Report.baseDir(this, path.text.toString())
            thread {
                try { measure(base, srv, th, sc, direct.isChecked, plist) }
                catch (e: Throwable) { log("ОШИБКА: ${e.javaClass.simpleName}: ${e.message}") }
                ui.post { run.isEnabled = true }
            }
        }
    }

    private fun measure(base: java.io.File, srv: Server, th: Int, sc: Int, direct: Boolean, plist: List<ProxySpec>) {
        val whenMs = System.currentTimeMillis()
        val info = NetInfo.collect(this)
        log("Сеть: ${Report.pointName(info)} (${info.optString("transport")})")
        log("Сервер: ${srv.label}, потоков $th, по $sc с на этап\n")
        val legs = ArrayList<LegResult>()
        if (direct) {
            log("== Напрямую")
            legs += Engine.runLeg("Напрямую", null, srv, th, sc, ::log)
        }
        for (p in plist) {
            val t = "Прокси " + p.label() + if (p.comment.isNotEmpty()) " (${p.comment})" else ""
            log("== $t")
            legs += Engine.runLeg(t, p, srv, th, sc, ::log)
        }
        val mode = (if (direct) "direct" else "") + (if (direct && plist.isNotEmpty()) "+" else "") + (if (plist.isNotEmpty()) "proxy${plist.size}" else "")
        val (json, txt) = Report.build(this, info, legs, srv, th, sc, false, whenMs)
        val saved = Report.save(base, false, whenMs, Report.pointName(info), mode, json, txt)
        log("\nСохранено:\n" + saved.joinToString("\n") { it.absolutePath })
    }

    private fun log(s: String) = ui.post {
        logView.append(s + "\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
