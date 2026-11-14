package org.unta.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.provider.MediaStore
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Открыть координаты на карте: выбор сервиса. Слои retromap.ru берутся из списка в Настройках. */
object MapLinks {
    private val names = listOf("OpenStreetMap: карта", "Google Maps: карта", "Google Maps: спутник", "Яндекс Карты: карта", "Яндекс Карты: спутник")

    private fun url(i: Int, lat: Double, lon: Double): String {
        val la = "%.6f".format(Locale.US, lat); val lo = "%.6f".format(Locale.US, lon)
        return when (i) {
            0 -> "https://www.openstreetmap.org/?mlat=$la&mlon=$lo#map=17/$la/$lo"
            1 -> "https://www.google.com/maps/search/?api=1&query=$la,$lo"
            2 -> "https://www.google.com/maps/@?api=1&map_action=map&center=$la,$lo&zoom=17&basemap=satellite"
            3 -> "https://yandex.ru/maps/?ll=$lo,$la&z=17&pt=$lo,$la,pm2rdm&l=map"
            else -> "https://yandex.ru/maps/?ll=$lo,$la&z=17&pt=$lo,$la,pm2rdm&l=sat"
        }
    }

    /** Формат ссылки retromap.ru: /КОДСЛОЯ_zМАСШТАБ_широта,долгота */
    private fun retroUrl(code: String, lat: Double, lon: Double, zoom: Int) =
        "https://retromap.ru/${code}_z${zoom}_${"%.6f".format(Locale.US, lat)},${"%.6f".format(Locale.US, lon)}"

    fun choose(ctx: Activity, lat: Double, lon: Double, what: String, zoom: Int = 15) {
        val retro = Prefs.retro(ctx).mapNotNull { r -> r.code()?.let { code -> (r.name.ifBlank { "слой $code" }) to code } }
        val items = names + retro.map { "retromap.ru: ${it.first}" }
        AlertDialog.Builder(ctx).setTitle("Открыть на карте: $what")
            .setItems(items.toTypedArray()) { _, i ->
                val u = if (i < names.size) url(i, lat, lon) else retroUrl(retro[i - names.size].second, lat, lon, zoom)
                try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }
                catch (e: Exception) { Toast.makeText(ctx, "Не удалось открыть браузер", Toast.LENGTH_LONG).show() }
            }.setNegativeButton("Отмена", null).show()
    }
}

/** Картинка с графиками всех замеров сессии, друг под другом, у каждого заголовок. */
object GraphExport {
    private fun pts(a: JSONArray?): List<FloatArray> = (0 until (a?.length() ?: 0)).map {
        val p = a!!.getJSONArray(it); floatArrayOf(p.getDouble(0).toFloat(), p.getDouble(1).toFloat())
    }

    fun render(ctx: Context, recs: List<JSONObject>): Bitmap? {
        class Card(val title: String, val sub: String, val g: JSONObject)
        val cards = ArrayList<Card>()
        recs.forEachIndexed { i, j ->
            val leg = j.optJSONArray("legs")?.optJSONObject(0) ?: return@forEachIndexed
            val g = leg.optJSONObject("graph") ?: return@forEachIndexed
            val title = "[${i + 1}/${recs.size}] ${leg.optString("title")} → ${j.optJSONObject("server")?.optString("label") ?: ""}"
            val e = leg.optJSONObject("exit")
            val sub = listOfNotNull(
                HistoryFmt.netHeader(j.optJSONObject("network")),
                HistoryFmt.resultLine(leg),
                e?.takeIf { it.optString("clientIp").isNotEmpty() }?.let { "IP ${HistoryFmt.exitLine(it)}" }
            ).joinToString("\n")
            cards.add(Card(title, sub, g))
        }
        if (cards.isEmpty()) return null
        val d = ctx.resources.displayMetrics.density
        val w = 1080; val pad = (16 * d).toInt(); val tw = w - 2 * pad
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF111111.toInt(); textSize = 15f * d; typeface = Typeface.DEFAULT_BOLD }
        val sp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF444444.toInt(); textSize = 12f * d }
        val gh = (200 * d).toInt()
        val layouts = cards.map {
            StaticLayout.Builder.obtain(it.title, 0, it.title.length, tp, tw).build() to
                StaticLayout.Builder.obtain(it.sub, 0, it.sub.length, sp, tw).build()
        }
        var h = pad
        layouts.forEach { (a, b) -> h += a.height + b.height + gh + pad * 2 }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(Color.WHITE)
        var y = pad
        cards.forEachIndexed { idx, card ->
            val (a, b) = layouts[idx]
            c.save(); c.translate(pad.toFloat(), y.toFloat()); a.draw(c); c.restore(); y += a.height
            c.save(); c.translate(pad.toFloat(), y.toFloat()); b.draw(c); c.restore(); y += b.height + pad / 2
            val v = SpeedGraph(ctx).apply {
                useExportLook()
                setData(card.g.optInt("secs", 10), card.g.optInt("phases", 2), pts(card.g.optJSONArray("dl")), pts(card.g.optJSONArray("ul")))
            }
            v.measure(View.MeasureSpec.makeMeasureSpec(tw, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(gh, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, tw, gh)
            c.save(); c.translate(pad.toFloat(), y.toFloat()); v.draw(c); c.restore()
            y += gh + pad + pad / 2
        }
        return bmp
    }
}

/** Что и как отправлять. Результат (скорость, сервер, прямое или через прокси) в тексте есть всегда. */
object ShareBuilder {
    /** Полный текст, который не поместился в подпись под картинкой (у Telegram подпись не длиннее 1024 знаков). */
    var pendingText: String? = null

    fun checkPending(ctx: Activity) {
        val t = pendingText ?: return
        pendingText = null
        AlertDialog.Builder(ctx).setTitle("Полный текст")
            .setMessage("Мессенджеры обрезают подпись под картинкой (Telegram до 1000 знаков), поэтому к картинке ушла краткая сводка. Отправить полный текст отдельным сообщением?")
            .setPositiveButton("Отправить текст") { _, _ ->
                val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t)
                ctx.startActivity(Intent.createChooser(i, "Полный текст"))
            }
            .setNegativeButton("Не нужно", null).show()
    }

    private fun caption(recs: List<JSONObject>, sel: Set<String>): String {
        val sb = StringBuilder("UNTA: замеры скорости интернета\n")
        recs.forEachIndexed { i, j ->
            val leg = j.optJSONArray("legs")?.optJSONObject(0)
            val srv = j.optJSONObject("server")?.optString("label") ?: ""
            val kind = leg?.optString("kind")?.ifEmpty { "direct" } ?: "direct"
            sb.append("${i + 1}) ${if (kind == "direct") "напрямую" else "через прокси ($kind)"} → $srv: ")
            if (leg != null) sb.append(HistoryFmt.resultLine(leg))
            if ("net" in sel) sb.append(" · ").append(HistoryFmt.netHeader(j.optJSONObject("network")))
            sb.append('\n')
        }
        return if (sb.length > 1000) sb.substring(0, 997) + "..." else sb.toString()
    }

    private val keys = listOf("geo_device", "net", "proxy_name", "proxy_addr", "geo_exit", "exit_ip", "graph")
    private val labels = listOf(
        "Гео устройства (где был телефон)", "Имя сети (точка доступа или оператор)", "Имя прокси", "Адрес прокси",
        "Гео прокси / выходного IP", "Выходной IP-адрес", "График (картинкой)"
    )

    fun show(ctx: Activity, recs: List<JSONObject>) {
        val prefs = ctx.getSharedPreferences("unta", Context.MODE_PRIVATE)
        val saved = prefs.getStringSet("share_sel", null) ?: setOf("net", "exit_ip", "graph")
        val checked = BooleanArray(keys.size) { keys[it] in saved }
        AlertDialog.Builder(ctx).setTitle("Чем поделиться")
            .setMultiChoiceItems(labels.toTypedArray(), checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("Поделиться") { _, _ ->
                val sel = keys.filterIndexed { i, _ -> checked[i] }.toSet()
                prefs.edit().putStringSet("share_sel", sel).apply()
                send(ctx, recs, sel)
            }
            .setNegativeButton("Отмена", null).show()
    }

    private fun osm(lat: Double, lon: Double) = "https://www.openstreetmap.org/?mlat=%.5f&mlon=%.5f#map=15/%.5f/%.5f".format(Locale.US, lat, lon, lat, lon)

    fun text(recs: List<JSONObject>, sel: Set<String>): String {
        val sb = StringBuilder()
        sb.append("UNTA: измерение скорости интернета\n")
        sb.append("Измеряется скорость скачивания и отдачи до тестового сервера. По каждому замеру ниже указано, откуда и через что шёл трафик.\n\n")
        recs.forEachIndexed { i, j ->
            val net = j.optJSONObject("network") ?: JSONObject()
            val leg = j.optJSONArray("legs")?.optJSONObject(0)
            val srv = j.optJSONObject("server")?.optString("label") ?: ""
            val kind = leg?.optString("kind")?.ifEmpty { "direct" } ?: "direct"
            val direct = kind == "direct"
            sb.append("=== Замер ${i + 1}: ${if (direct) "напрямую, без прокси" else "через прокси ($kind)"}, сервер $srv ===\n")
            sb.append("Время замера: ${HistoryFmt.dateFull(j.optLong("time_epoch"))}\n")
            if ("net" in sel) sb.append("Сеть, через которую измеряли: ${HistoryFmt.netHeader(net)}\n")
            if (!direct && leg != null) {
                if ("proxy_name" in sel) sb.append("Имя прокси: ${leg.optString("proxy_name").ifEmpty { "(без имени)" }}\n")
                if ("proxy_addr" in sel) sb.append("Адрес прокси: ${leg.optString("proxy")} (тип $kind, пароль скрыт)\n")
            }
            if (leg != null) sb.append("Результат: ${HistoryFmt.resultLine(leg)}\n")
            val e = leg?.optJSONObject("exit")
            if (e != null && e.optString("clientIp").isNotEmpty()) {
                if ("exit_ip" in sel) sb.append("Выходной IP (под каким адресом вас видит интернет): ${e.optString("clientIp")}\n")
                if ("geo_exit" in sel) {
                    val place = listOf(e.optString("city"), e.optString("country")).filter { it.isNotEmpty() }.joinToString(", ")
                    sb.append("Гео ${if (direct) "вашего интернет-подключения" else "прокси"} по выходному IP (приблизительно): $place")
                    HistoryFmt.exitLatLon(e)?.let { sb.append(" (${"%.4f, %.4f".format(Locale.US, it.first, it.second)})\n  ${osm(it.first, it.second)}") }
                    sb.append('\n')
                }
            }
            if ("geo_device" in sel) {
                val loc = net.optJSONObject("location")
                if (loc != null) sb.append("Гео устройства (где находился телефон): ${"%.5f, %.5f".format(Locale.US, loc.optDouble("lat"), loc.optDouble("lon"))} (точность ${loc.optDouble("accuracy_m").toInt()} м)\n  ${osm(loc.optDouble("lat"), loc.optDouble("lon"))}\n")
                else sb.append("Гео устройства: не определено\n")
            }
            sb.append('\n')
        }
        return sb.toString().trimEnd() + "\n"
    }

    private fun saveBitmap(ctx: Context, bmp: Bitmap): Uri? {
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "UNTA_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/UNTA")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return uri
    }

    fun send(ctx: Activity, recs: List<JSONObject>, sel: Set<String>) {
        val full = text(recs, sel)
        val intent = Intent(Intent.ACTION_SEND)
        intent.putExtra(Intent.EXTRA_SUBJECT, "UNTA: замер сети")
        intent.type = "text/plain"
        var body = full
        if ("graph" in sel) {
            val uri = try { GraphExport.render(ctx, recs)?.let { saveBitmap(ctx, it) } } catch (e: Exception) { null }
            if (uri != null) {
                intent.type = "image/png"
                intent.putExtra(Intent.EXTRA_STREAM, uri)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (full.length > 1000) { body = caption(recs, sel); pendingText = full }
            } else Toast.makeText(ctx, "Графика для этих замеров нет (старые записи) или не удалось её сохранить: отправляю текст", Toast.LENGTH_LONG).show()
        }
        intent.putExtra(Intent.EXTRA_TEXT, body)
        ctx.startActivity(Intent.createChooser(intent, "Поделиться замером"))
    }
}
