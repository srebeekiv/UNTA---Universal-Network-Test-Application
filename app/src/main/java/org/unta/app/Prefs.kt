package org.unta.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Строка списка прокси в настройках. type: auto | http | socks4 | socks5 */
data class ProxyEntry(
    var enabled: Boolean = true, var type: String = "auto", var addr: String = "",
    var authOn: Boolean = false, var user: String = "", var pass: String = "", var name: String = ""
) {
    fun toSpec(): ProxySpec? {
        val p = ProxySpec.parse(addr.trim()) ?: return null
        val scheme = when (type) { "http" -> "http"; "socks4" -> "socks4"; "socks5" -> "socks5h"; else -> p.scheme }
        val useAuth = authOn && user.isNotEmpty()
        return p.copy(
            scheme = scheme,
            user = if (useAuth) user else p.user,
            pass = if (useAuth) pass else p.pass,
            comment = name.trim().ifEmpty { p.comment }
        )
    }

    fun toJson(): JSONObject = JSONObject().put("enabled", enabled).put("type", type).put("addr", addr)
        .put("authOn", authOn).put("user", user).put("pass", pass).put("name", name)

    companion object {
        fun fromJson(o: JSONObject) = ProxyEntry(
            o.optBoolean("enabled", true), o.optString("type", "auto"), o.optString("addr"),
            o.optBoolean("authOn"), o.optString("user"), o.optString("pass"), o.optString("name")
        )
    }
}

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("unta", Context.MODE_PRIVATE)

    fun path(c: Context): String = sp(c).getString("path", "/storage/emulated/0/UNTA")!!
    fun setPath(c: Context, v: String) { sp(c).edit().putString("path", v.trim()).apply() }

    fun proxies(c: Context): MutableList<ProxyEntry> {
        val s = sp(c)
        val js = s.getString("proxies_v2", null)
        if (js != null) {
            val a = JSONArray(js)
            return MutableList(a.length()) { ProxyEntry.fromJson(a.getJSONObject(it)) }
        }
        // миграция со старого текстового списка
        val old = s.getString("proxies", "") ?: ""
        return old.lines().mapNotNull { ProxySpec.parse(it) }.map {
            val t = when {
                it.scheme == null -> "auto"
                it.scheme.startsWith("socks4") -> "socks4"
                it.scheme.startsWith("socks5") -> "socks5"
                else -> "http"
            }
            ProxyEntry(true, t, "${it.host}:${it.port}", it.user != null, it.user ?: "", it.pass ?: "", it.comment)
        }.toMutableList()
    }

    fun saveProxies(c: Context, list: List<ProxyEntry>) {
        val a = JSONArray(); list.forEach { a.put(it.toJson()) }
        sp(c).edit().putString("proxies_v2", a.toString()).apply()
    }
}
