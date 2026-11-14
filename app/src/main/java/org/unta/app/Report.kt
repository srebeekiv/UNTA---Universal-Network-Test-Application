package org.unta.app

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Report {
    fun device(): String = clean(Build.MODEL ?: "android")

    fun clean(s: String) = s.replace(Regex("[^\\p{L}\\p{N}._-]+"), "_").trim('_').ifEmpty { "x" }

    fun pointName(info: JSONObject): String {
        info.optJSONObject("wifi")?.optString("ssid")?.takeIf { it.isNotEmpty() && !it.startsWith("<") }?.let { return it }
        info.optJSONObject("mobile")?.optString("operator")?.takeIf { it.isNotEmpty() }?.let { return it }
        return info.optString("transport", "net")
    }

    fun build(ctx: Context, info: JSONObject, legs: List<LegResult>, server: Server, threads: Int, secs: Int, scheduled: Boolean, whenMs: Long): Pair<JSONObject, String> {
        val ver = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (_: Exception) { "?" }
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date(whenMs))
        val j = JSONObject()
            .put("app", "UNTA").put("app_version", ver).put("time_iso", iso).put("time_epoch", whenMs / 1000)
            .put("device", Build.MODEL).put("scheduled", scheduled)
            .put("server", JSONObject().put("code", server.code).put("label", legs.firstOrNull()?.server?.ifEmpty { null } ?: server.label))
            .put("threads", threads).put("seconds_per_phase", secs).put("network", info)
        val arr = JSONArray()
        for (l in legs) {
            val o = JSONObject().put("title", l.title).put("proxy", masked(l.proxy)).put("reachable", l.reachable)
                .put("error", l.error).put("ping_ms", l.pingMs)
                .put("download_mbps", l.dlMbps?.let { Math.round(it * 100) / 100.0 })
                .put("upload_mbps", l.ulMbps?.let { Math.round(it * 100) / 100.0 })
                .put("download_bytes", l.dlBytes).put("upload_bytes", l.ulBytes)
                .put("download_errors", l.dlBad).put("upload_errors", l.ulBad)
            l.exit?.let { o.put("exit", it) }
            l.latency?.let { o.put("latency", it) }
            o.put("kind", l.kind).put("proxy_name", l.pname)
            l.graph?.let { o.put("graph", it) }
            arr.put(o)
        }
        j.put("legs", arr)

        val t = StringBuilder()
        t.append("UNTA $ver  |  $iso").append(if (scheduled) "  |  по расписанию" else "").append('\n')
        t.append("Устройство: ${Build.MODEL}\n")
        t.append("Сервер: ${legs.firstOrNull()?.server?.ifEmpty { null } ?: server.label}  |  потоков: $threads  |  секунд на этап: $secs\n")
        t.append("Точка: ${pointName(info)}  |  канал связи: ${info.optString("transport")}\n")
        t.append(networkText(info))
        t.append("\n")
        for (l in legs) {
            t.append("== ${l.title}\n")
            if (!l.reachable) { t.append("   НЕ УДАЛОСЬ: ${l.error}\n\n"); continue }
            t.append("   отклик HTTP: ${l.pingMs} мс")
            l.latency?.let { t.append("  (DNS ${it.optLong("dns_ms")}, TCP ${it.optLong("connect_ms")}, TLS ${it.optLong("tls_ms")}, первый запрос ${it.optLong("first_ms")} мс)") }
            t.append("\n")
            t.append("   скачивание: ${l.dlMbps?.let { "%.2f".format(it) } ?: "-"} Мбит/с (${l.dlBytes / 1_000_000} МБ, ошибок ${l.dlBad})\n")
            t.append("   отдача:     ${l.ulMbps?.let { "%.2f".format(it) } ?: "-"} Мбит/с (${l.ulBytes / 1_000_000} МБ, ошибок ${l.ulBad})\n")
            val e = l.exit
            if (e != null && e.optString("clientIp").isNotEmpty())
                t.append("   выходной IP: ${e.optString("clientIp")}  ${listOf(e.optString("city"), e.optString("country"), e.optString("asOrganization")).filter { it.isNotEmpty() }.joinToString(", ")}  (${e.optString("src")})\n")
            else t.append("   выходной IP: не удалось определить ${e?.optString("error") ?: ""}\n")
            t.append('\n')
        }
        return j to t.toString()
    }

    /** Блок «сеть и сигнал»: используется и в txt-файле, и на экране подробностей истории. */
    fun networkText(info: JSONObject): String {
        val t = StringBuilder()
        info.optJSONArray("local_ips")?.let { t.append("Локальные IP: ${(0 until it.length()).joinToString(", ") { i -> it.getString(i) }}\n") }
        info.optJSONObject("wifi")?.let { w ->
            t.append("Wi-Fi: ${w.optString("ssid")}  BSSID ${w.optString("bssid")}\n")
            t.append("  сигнал ${w.optInt("rssi_dbm")} дБм, канал ${w.optInt("channel")} (${w.optInt("frequency_mhz")} МГц)")
            if (w.has("width_mhz")) t.append(", ширина ${w.get("width_mhz")} МГц")
            t.append("\n  ${w.optString("standard")}, скорость соединения ${w.optInt("link_speed_mbps")} Мбит/с (tx ${w.optInt("tx_link_speed_mbps")}, rx ${w.optInt("rx_link_speed_mbps")})\n")
        }
        info.optJSONObject("mobile")?.let { m ->
            t.append("Моб. сеть: оператор ${m.optString("operator")} (MCC/MNC ${m.optString("mcc_mnc")})\n")
            val tech = m.optString("network_type")
            if (tech.isNotEmpty()) t.append("  поколение: ${NetInfo.genRu(m.optString("generation"))}  |  технический тип: ${NetInfo.techNote(tech)}\n")
            val ov = m.optString("display_override")
            if (ov.isNotEmpty()) t.append("  Android показывает в строке состояния: ${NetInfo.overrideNote(ov)}\n")
            if (m.has("signal_dbm")) {
                t.append("  сигнал ${m.optInt("signal_dbm")} дБм, уровень ${m.optInt("level")}/4")
                if (m.has("rsrp")) t.append(", RSRP ${m.optInt("rsrp")}, RSRQ ${m.optInt("rsrq")}, SINR ${m.optInt("rssnr")}")
                if (m.has("nr_ss_rsrp")) t.append(", 5G SS-RSRP ${m.optInt("nr_ss_rsrp")}, SS-RSRQ ${m.optInt("nr_ss_rsrq")}, SS-SINR ${m.optInt("nr_ss_sinr")}")
                if (m.has("rscp")) t.append(", RSCP ${m.optInt("rscp")}")
                if (m.has("ecno")) t.append(", Ec/No ${m.optInt("ecno")}")
                if (m.has("ta")) t.append(", TA ${m.optInt("ta")}")
                t.append('\n')
            }
            m.optJSONObject("cell")?.let { c ->
                val parts = ArrayList<String>()
                for (k in c.keys()) parts.add("$k ${c.get(k)}")
                t.append("  ячейка: ${parts.joinToString(", ")}\n")
            }
        }
        info.optJSONObject("location")?.let {
            t.append("Координаты: ${it.optDouble("lat")}, ${it.optDouble("lon")} (точность ${it.optDouble("accuracy_m").toInt()} м)\n")
            t.append("  https://www.openstreetmap.org/?mlat=${it.optDouble("lat")}&mlon=${it.optDouble("lon")}#map=17/${it.optDouble("lat")}/${it.optDouble("lon")}\n")
        }
        return t.toString()
    }

    /** Пароль в сохранённые файлы не пишем. */
    private fun masked(p: String?) = p?.replace(Regex("//([^:@/]+):[^@/]*@"), "//$1:***@")

    fun baseDir(ctx: Context, pref: String): File {
        try {
            val f = File(pref); f.mkdirs()
            if (f.isDirectory && f.canWrite()) return f
        } catch (_: Exception) {}
        return ctx.getExternalFilesDir(null) ?: ctx.filesDir
    }

    fun netTag(info: JSONObject): String = when (info.optString("transport")) {
        "wifi" -> info.optJSONObject("wifi")?.optString("ssid")?.takeIf { it.isNotEmpty() && !it.startsWith("<") } ?: "WiFi"
        "mobile" -> info.optJSONObject("mobile")?.optString("generation")?.ifEmpty { null } ?: "mobile"
        else -> info.optString("transport", "net")
    }

    /** Имя файла: ДАТА_СЕТЬ_(direct | тип_имяПрокси)-Сервер. Сеть: имя Wi-Fi или поколение (5G/4G/3G/2G). */
    fun fileName(whenMs: Long, info: JSONObject, leg: LegResult, s: Server): String {
        val ts = SimpleDateFormat("yyyy.MM.dd-HH.mm.ss", Locale.US).format(Date(whenMs))
        val net = when (info.optString("transport")) {
            "wifi" -> info.optJSONObject("wifi")?.optString("ssid")?.takeIf { it.isNotEmpty() && !it.startsWith("<") } ?: "WiFi"
            "mobile" -> info.optJSONObject("mobile")?.optString("generation")?.ifEmpty { null } ?: "mobile"
            else -> info.optString("transport", "net")
        }
        val mid = if (leg.kind == "direct") "direct"
        else leg.kind + "_" + clean(if (leg.pname.isNotBlank()) leg.pname else leg.hostPort)
        return "${ts}_${clean(net.replace("+", "plus"))}_${mid}-${clean(s.short)}"
    }

    /** <папка>/tests/<имя>.txt и <папка>/tests/json/<имя>.json (запланированные: tests/scheduled/...) */
    fun save(base: File, scheduled: Boolean, name: String, json: JSONObject, txt: String): List<File> {
        val dir = File(base, if (scheduled) "tests/scheduled" else "tests")
        val fj = File(dir, "json/$name.json"); val ft = File(dir, "$name.txt")
        fj.parentFile!!.mkdirs()
        fj.writeText(json.toString(2)); ft.writeText(txt)
        return listOf(ft, fj)
    }
}
