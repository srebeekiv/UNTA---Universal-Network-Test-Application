package org.unta.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File

/** Подробности одного замера из истории. */
class DetailActivity : Activity() {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        val path = intent.getStringExtra("path")
        val j = try { JSONObject(File(path ?: "").readText()) } catch (e: Exception) { null }
        if (j == null) { Toast.makeText(this, "Не удалось прочитать файл", Toast.LENGTH_LONG).show(); finish(); return }
        title = HistoryFmt.netHeader(j.optJSONObject("network"))
        val text = HistoryFmt.detail(j) + "\nФайл: $path\n"

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            this.text = "Копировать"
            setOnClickListener {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("UNTA", text))
                Toast.makeText(this@DetailActivity, "Скопировано", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val loc = j.optJSONObject("network")?.optJSONObject("location")
        if (loc != null) row.addView(Button(this).apply {
            this.text = "Открыть на карте"
            setOnClickListener {
                val lat = loc.optDouble("lat"); val lon = loc.optDouble("lon")
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon(UNTA)"))) }
                catch (e: ActivityNotFoundException) { Toast.makeText(this@DetailActivity, "Нет приложения для карт", Toast.LENGTH_LONG).show() }
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row)
        col.addView(TextView(this).apply {
            this.text = text; textSize = 13f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true); setPadding(0, dp(8), 0, 0)
        })
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
