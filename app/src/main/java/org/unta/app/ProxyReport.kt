package org.unta.app

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ProxyReport {
    fun name(whenMs: Long, info: JSONObject): String =
        SimpleDateFormat("yyyy.MM.dd-HH.mm.ss", Locale.US).format(Date(whenMs)) + "_" + Report.clean(Report.netTag(info)) + "_proxycheck"

    fun line(r: ProxyCheck): String {
        val n = if (r.name.isNotBlank()) r.name + " " else ""
        return if (r.ok)
            "✓ ${n}${masked(r.label)}: до прокси ${r.tcpMs} мс, через прокси ${r.reqMs} мс (повторно ${r.warmMs} мс), выход ${r.ip} ${r.loc}/${r.colo}"
        else "✗ ${n}${masked(r.label)}: ${r.error}"
    }

    private fun masked(s: String) = s.replace(Regex("//([^:@/]+):[^@/]*@"), "//$1:***@")

    fun build(info: JSONObject, results: List<ProxyCheck>, whenMs: Long): Pair<JSONObject, String> {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date(whenMs))
        val arr = JSONArray()
        for (r in results) arr.put(JSONObject().put("name", r.name).put("proxy", masked(r.label)).put("type", r.kind).put("ok", r.ok)
            .put("tcp_to_proxy_ms", r.tcpMs).put("request_ms", r.reqMs).put("repeat_ms", r.warmMs)
            .put("exit_ip", r.ip).put("country", r.loc).put("colo", r.colo).put("error", r.error))
        val j = JSONObject().put("app", "UNTA").put("kind", "proxycheck").put("time_iso", iso).put("device", Build.MODEL)
            .put("network", info).put("ok", results.count { it.ok }).put("total", results.size).put("proxies", arr)
        val t = StringBuilder()
        t.append("UNTA: быстрая проверка прокси  |  $iso\n")
        t.append("Устройство: ${Build.MODEL}  |  сеть: ${Report.pointName(info)} (${info.optString("transport")})\n")
        t.append("Работает ${results.count { it.ok }} из ${results.size}\n\n")
        results.forEach { t.append(line(it)).append('\n') }
        return j to t.toString()
    }
}
