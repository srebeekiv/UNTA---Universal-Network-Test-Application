package org.unta.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

class SettingsActivity : Activity() {
    private lateinit var pathEdit: EditText
    private lateinit var list: LinearLayout
    private lateinit var entries: MutableList<ProxyEntry>
    private val types = listOf("Auto", "HTTP(s)", "socks4", "socks5")
    private val codes = listOf("auto", "http", "socks4", "socks5")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun EditText.onChange(f: (String) -> Unit) = addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { f(s?.toString() ?: "") }
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Настройки"
        actionBar?.setDisplayHomeAsUpEnabled(true)
        entries = Prefs.proxies(this)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(24)) }
        fun label(t: String, size: Float = 13f) = TextView(this).apply { text = t; textSize = size; setPadding(0, dp(12), 0, dp(2)) }.also { col.addView(it) }

        label("Папка результатов", 16f)
        label("Внутри создаётся tests/ (txt) и tests/json/ (json). Эту папку можно отдать Syncthing.")
        pathEdit = EditText(this).apply { setText(Prefs.path(this@SettingsActivity)); textSize = 14f; setSingleLine() }.also { col.addView(it) }
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(Button(this).apply {
            text = "Выбрать папку…"
            setOnClickListener { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 11) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btns.addView(Button(this).apply {
            text = "Доступ ко всем файлам"
            setOnClickListener {
                try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
                catch (e: Exception) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(btns)

        label("Прокси", 16f)
        label("Галочка справа включает или выключает прокси для замеров. Тип Auto определяется автоматически.")
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
        entries.forEach { addRow(it) }
        col.addView(Button(this).apply {
            text = "+ Добавить прокси"
            setOnClickListener {
                val e = ProxyEntry(); entries.add(e); addRow(e)
            }
        })
        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun addRow(e: ProxyEntry) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setStroke(dp(1), 0x66888888) }
        }
        val type = Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, types)
            setSelection(codes.indexOf(e.type).coerceAtLeast(0))
        }
        val addr = EditText(this).apply {
            hint = "адрес:порт"; setText(e.addr); setSingleLine(); textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val en = CheckBox(this).apply { isChecked = e.enabled }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(type, LinearLayout.LayoutParams(dp(115), ViewGroup.LayoutParams.WRAP_CONTENT))
        top.addView(addr, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(en)
        card.addView(top)

        val name = EditText(this).apply { hint = "Имя прокси (необязательно, пойдёт в имя файла)"; setText(e.name); setSingleLine(); textSize = 14f }
        card.addView(name)

        val authCb = CheckBox(this).apply { text = "Авторизация"; isChecked = e.authOn }
        val del = Button(this).apply { text = "Удалить" }
        val authRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        authRow.addView(authCb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        authRow.addView(del)
        card.addView(authRow)

        val user = EditText(this).apply { hint = "Логин"; setText(e.user); setSingleLine(); textSize = 14f; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS }
        val pass = EditText(this).apply { hint = "Пароль"; setText(e.pass); setSingleLine(); textSize = 14f; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD }
        val cred = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cred.addView(user, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        cred.addView(pass, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(cred)

        // выключенный прокси: все его поля неактивны и бледные; без галочки «Авторизация» неактивны логин и пароль
        fun refresh() {
            val on = en.isChecked
            listOf<View>(type, addr, name, authCb).forEach { it.isEnabled = on; it.alpha = if (on) 1f else 0.4f }
            val a = on && authCb.isChecked
            listOf<View>(user, pass).forEach { it.isEnabled = a; it.alpha = if (a) 1f else 0.35f }
        }
        refresh()

        en.setOnCheckedChangeListener { _, c -> e.enabled = c; refresh() }
        authCb.setOnCheckedChangeListener { _, c -> e.authOn = c; refresh() }
        addr.onChange { e.addr = it }
        name.onChange { e.name = it }
        user.onChange { e.user = it }
        pass.onChange { e.pass = it }
        type.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { e.type = codes[pos] }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        del.setOnClickListener {
            AlertDialog.Builder(this).setMessage("Удалить этот прокси из списка?")
                .setPositiveButton("Удалить") { _, _ -> list.removeView(card); entries.remove(e) }
                .setNegativeButton("Отмена", null).show()
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(10)
        list.addView(card, lp)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 11 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                val id = DocumentsContract.getTreeDocumentId(uri)
                val vol = id.substringBefore(":"); val rel = id.substringAfter(":", "")
                val root = if (vol == "primary") "/storage/emulated/0" else "/storage/$vol"
                pathEdit.setText(if (rel.isEmpty()) root else "$root/$rel")
            } catch (_: Exception) {}
        }
    }

    override fun onPause() {
        super.onPause()
        Prefs.setPath(this, pathEdit.text.toString())
        Prefs.saveProxies(this, entries)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
