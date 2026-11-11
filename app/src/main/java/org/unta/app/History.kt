package org.unta.app

import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HistoryItem(val file: File, val whenSec: Long, val title: String, val subtitle: String, val search: String)

object History {
    /** Читает все json из <папка>/tests/json и tests/scheduled/json, свежие сверху. */
    fun load(base: File): List<HistoryItem> {
        val out = ArrayList<HistoryItem>()
        for (d in listOf(File(base, "tests/json"), File(base, "tests/scheduled/json"))) {
            d.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.forEach { f ->
                try { out.add(item(f, JSONObject(f.readText()))) } catch (_: Exception) {}
            }
        }
        return out.sortedByDescending { it.whenSec }
    }

    private fun f1(v: Double) = "%.1f".format(v)

    private fun item(f: File, j: JSONObject): HistoryItem {
        val whenSec = j.optLong("time_epoch", f.lastModified() / 1000)
        val net = j.optJSONObject("network")
        val title = HistoryFmt.dateShort(whenSec) + "   " + HistoryFmt.netHeader(net)
        val sub: String
        var extra = ""
        if (j.optString("kind") == "proxycheck") {
            sub = "Проверка прокси: работает ${j.optInt("ok")} из ${j.optInt("total")}"
            val arr = j.optJSONArray("proxies")
            if (arr != null) for (i in 0 until arr.length()) extra += " " + arr.getJSONObject(i).optString("name") + " " + arr.getJSONObject(i).optString("proxy")
        } else {
            val leg = j.optJSONArray("legs")?.optJSONObject(0)
            val server = j.optJSONObject("server")?.optString("label") ?: ""
            val path = leg?.optString("title") ?: ""
            val res = when {
                leg == null -> ""
                !leg.optBoolean("reachable", true) -> "ошибка"
                else -> "↓ ${if (leg.isNull("download_mbps")) "—" else f1(leg.optDouble("download_mbps"))}  ↑ ${if (leg.isNull("upload_mbps")) "—" else f1(leg.optDouble("upload_mbps"))} Мбит/с"
            }
            sub = listOf(server, path, res).filter { it.isNotEmpty() }.joinToString(" · ")
            extra = " " + (leg?.optString("proxy") ?: "")
        }
        val w = net?.optJSONObject("wifi"); val m = net?.optJSONObject("mobile")
        val search = (title + " " + sub + extra + " " + (w?.optString("ssid") ?: "") + " " + (m?.optString("operator") ?: "") + " " + j.optString("device")).lowercase()
        return HistoryItem(f, whenSec, title, sub, search)
    }
}

object HistoryFmt {
    fun dateShort(sec: Long): String = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(sec * 1000))
    fun dateFull(sec: Long): String = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date(sec * 1000))

    /** «WiFi - имя точки», «4G - оператор», «2,5G - оператор». */
    fun netHeader(info: JSONObject?): String {
        if (info == null) return "—"
        return when (val tr = info.optString("transport")) {
            "wifi" -> {
                val ssid = info.optJSONObject("wifi")?.optString("ssid") ?: ""
                "WiFi - " + (if (ssid.isEmpty() || ssid.startsWith("<")) "имя скрыто (нужна геолокация)" else ssid)
            }
            "mobile" -> {
                val m = info.optJSONObject("mobile")
                val g = NetInfo.genRu((m?.optString("generation") ?: "").ifEmpty { "моб. сеть" })
                val op = (m?.optString("operator") ?: "").ifEmpty { "оператор не определён" }
                "$g - $op"
            }
            "ethernet" -> "Ethernet"
            "vpn" -> "VPN"
            "none" -> "нет сети"
            else -> tr.ifEmpty { "—" }
        }
    }

    private fun n(j: JSONObject, k: String) = if (j.isNull(k) || !j.has(k)) "—" else "%.2f".format(j.optDouble(k))

    fun detail(j: JSONObject): String {
        val net = j.optJSONObject("network") ?: JSONObject()
        val sec = j.optLong("time_epoch", 0)
        val t = StringBuilder()
        t.append(netHeader(net)).append('\n')
        t.append("Дата: ${dateFull(sec)}\n")
        t.append("Устройство: ${j.optString("device")}\n")
        if (j.optString("kind") == "proxycheck") {
            t.append("\nБЫСТРАЯ ПРОВЕРКА ПРОКСИ: работает ${j.optInt("ok")} из ${j.optInt("total")}\n")
            val arr = j.optJSONArray("proxies")
            if (arr != null) for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                val nm = p.optString("name").let { if (it.isNotEmpty()) "$it " else "" }
                if (p.optBoolean("ok"))
                    t.append("  ✓ $nm${p.optString("proxy")}: до прокси ${p.optLong("tcp_to_proxy_ms")} мс, через прокси ${p.optLong("request_ms")} мс (повторно ${p.optLong("repeat_ms")} мс), выход ${p.optString("exit_ip")} ${p.optString("country")}/${p.optString("colo")}\n")
                else t.append("  ✗ $nm${p.optString("proxy")}: ${p.optString("error")}\n")
            }
        } else {
            val leg = j.optJSONArray("legs")?.optJSONObject(0)
            t.append("\nКУДА ИЗМЕРЯЛИ\n")
            t.append("  сервер: ${j.optJSONObject("server")?.optString("label") ?: "—"}\n")
            t.append("  путь: ${leg?.optString("title") ?: "—"}\n")
            val e = leg?.optJSONObject("exit")
            if (e != null && e.optString("clientIp").isNotEmpty())
                t.append("  внешний IP: ${e.optString("clientIp")}  ${listOf(e.optString("city"), e.optString("country"), e.optString("asOrganization")).filter { it.isNotEmpty() }.joinToString(", ")}\n")
            if (leg != null) {
                t.append("\nРЕЗУЛЬТАТ\n")
                if (!leg.optBoolean("reachable", true)) t.append("  НЕ УДАЛОСЬ: ${leg.optString("error")}\n")
                else {
                    t.append("  скачивание: ${n(leg, "download_mbps")} Мбит/с (${leg.optLong("download_bytes") / 1_000_000} МБ, ошибок ${leg.optInt("download_errors")})\n")
                    t.append("  отдача:     ${n(leg, "upload_mbps")} Мбит/с (${leg.optLong("upload_bytes") / 1_000_000} МБ, ошибок ${leg.optInt("upload_errors")})\n")
                    t.append("  отклик HTTP: ${leg.optLong("ping_ms")} мс")
                    leg.optJSONObject("latency")?.let { t.append("  (DNS ${it.optLong("dns_ms")}, TCP ${it.optLong("connect_ms")}, TLS ${it.optLong("tls_ms")}, первый запрос ${it.optLong("first_ms")} мс)") }
                    t.append('\n')
                }
            }
            t.append("\nПАРАМЕТРЫ\n  потоков: ${j.optInt("threads")}, секунд на этап: ${j.optInt("seconds_per_phase")}\n")
        }
        t.append("\nСЕТЬ И СИГНАЛ\n").append(Report.networkText(net))
        return t.toString()
    }
}
