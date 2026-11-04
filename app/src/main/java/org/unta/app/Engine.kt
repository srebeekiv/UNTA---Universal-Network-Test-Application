package org.unta.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.IOException
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** dl: {BYTES} или {MB} подставляются в URL, иначе это статический файл (качаем через Range).
 *  mode отдачи: multipart | put | post-raw. ul == null: сервер отдачу не поддерживает. */
data class Server(val code: String, val label: String, val short: String, val dl: String, val ul: String?, val mode: String)

object Servers {
    val all = listOf(
        Server("cf", "Cloudflare-Anycast", "Cloudflare", "https://speed.cloudflare.com/__down?bytes={BYTES}", "https://speed.cloudflare.com/__up", "multipart"),
        Server("tele2", "Tele2-Anycast-Europe", "Tele2", "http://speedtest.tele2.net/1GB.zip", "http://speedtest.tele2.net/upload.php", "put"),
        Server("librespeed", "LibreSpeed-Official", "LibreSpeed", "https://librespeed.org/backend/garbage.php?ckSize={MB}", "https://librespeed.org/backend/empty.php", "post-raw"),
        Server("tele2-de", "DE Frankfurt-Tele2", "Tele2-DE", "http://fra36-speedtest-1.tele2.net/1GB.zip", "http://fra36-speedtest-1.tele2.net/upload.php", "put"),
        Server("tele2-nl", "NL Amsterdam-Tele2", "Tele2-NL", "http://ams-speedtest-1.tele2.net/1GB.zip", "http://ams-speedtest-1.tele2.net/upload.php", "put"),
        Server("tele2-se", "SE Stockholm-Tele2", "Tele2-SE", "http://hgd-speedtest-1.tele2.net/1GB.zip", "http://hgd-speedtest-1.tele2.net/upload.php", "put"),
        Server("tele2-lt", "LT Vilnius-Tele2", "Tele2-LT", "http://vln038-speedtest-2.tele2.net/1GB.zip", "http://vln038-speedtest-2.tele2.net/upload.php", "put"),
        Server("de-hetzner", "DE Falkenstein-Hetzner (только скачивание)", "Hetzner-DE", "https://fsn1-speed.hetzner.com/100MB.bin", null, ""),
        Server("fi-hetzner", "FI Helsinki-Hetzner (только скачивание)", "Hetzner-FI", "https://hel1-speed.hetzner.com/100MB.bin", null, ""),
        Server("fr-gra", "FR Gravelines-OVH (только скачивание)", "OVH-FR", "https://lil2-gra.speedtest.network.ovh.net/files/1Gb.dat", null, ""),
    )
    fun byCode(c: String) = all.firstOrNull { it.code == c } ?: all[0]
}

data class LegResult(
    val title: String, val proxy: String?, val reachable: Boolean, val error: String?, val pingMs: Long?,
    val dlMbps: Double?, val ulMbps: Double?, val dlBytes: Long, val ulBytes: Long,
    val dlBad: Int, val ulBad: Int, val exit: JSONObject?,
    val kind: String = "direct", val pname: String = "", val hostPort: String = ""
)

object Engine {
    private const val UA = "Mozilla/5.0 (Linux; Android) UNTA/1.0"
    private const val DL_CHUNK = 25_000_000L
    private const val UL_CHUNK = 10_000_000L

    private fun dlRequest(s: Server, bytes: Long): Request {
        val rb = Request.Builder().header("User-Agent", UA)
        val url = when {
            "{BYTES}" in s.dl -> s.dl.replace("{BYTES}", bytes.toString())
            "{MB}" in s.dl -> s.dl.replace("{MB}", maxOf(1L, (bytes + 999_999) / 1_000_000).toString())
            else -> { rb.header("Range", "bytes=0-${bytes - 1}"); s.dl }
        }
        return rb.url(url).build()
    }

    /** Быстрая проверка «сервер жив через этот путь»: качаем ~1 КБ. Возвращает время в мс. */
    fun precheck(c: OkHttpClient, s: Server): Long {
        val t0 = System.nanoTime()
        c.newCall(dlRequest(s, 1000)).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val buf = ByteArray(1024)
            val n = r.body!!.byteStream().read(buf)
            if (n < 0) throw IOException("пустой ответ")
        }
        return (System.nanoTime() - t0) / 1_000_000
    }

    /** Внешний IP и гео: пробуем несколько сервисов по очереди, т.к. ответ Cloudflare /meta бывает без нужных полей. */
    fun exitInfo(c: OkHttpClient): JSONObject {
        val dbg = StringBuilder()
        for (u in listOf("https://speed.cloudflare.com/meta", "https://ipinfo.io/json", "http://ip-api.com/json")) {
            try {
                val body = c.newCall(Request.Builder().header("User-Agent", UA).header("Accept", "application/json").url(u).build())
                    .execute().use { r ->
                        if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                        r.body!!.string()
                    }
                val j = JSONObject(body)
                val ip = listOf("clientIp", "ip", "query").map { j.optString(it) }.firstOrNull { it.isNotEmpty() }
                if (ip == null) { dbg.append("$u: нет поля IP: ${body.take(120)}; "); continue }
                val o = JSONObject().put("clientIp", ip).put("src", java.net.URI(u).host)
                for (k in listOf("city", "country", "region", "colo", "asn")) { val v = j.optString(k); if (v.isNotEmpty()) o.put(k, v) }
                val org = listOf("asOrganization", "org", "isp").map { j.optString(it) }.firstOrNull { it.isNotEmpty() }
                if (org != null) o.put("asOrganization", org)
                val loc = j.optString("loc")
                if (loc.contains(",")) { o.put("latitude", loc.substringBefore(",")); o.put("longitude", loc.substringAfter(",")) }
                for ((k, t) in listOf("latitude" to "latitude", "longitude" to "longitude", "lat" to "latitude", "lon" to "longitude"))
                    if (j.has(k) && !o.has(t)) o.put(t, j.get(k))
                return o
            } catch (e: Exception) { dbg.append("$u: ${e.message ?: e.javaClass.simpleName}; ") }
        }
        return JSONObject().put("error", dbg.toString())
    }

    /** Остановка по кнопке. */
    val cancel = java.util.concurrent.atomic.AtomicBoolean(false)

    /** phase: "check" | "download" | "upload". sample: (фаза, текущая скорость Мбит/с, доля пройденного времени этапа 0..1). */
    class Progress(val phase: (String) -> Unit, val sample: (String, Double, Double) -> Unit)

    private fun monitor(ts: List<Thread>, total: AtomicLong, t0: Long, secs: Int, name: String, pr: Progress?) {
        val hist = ArrayDeque<Pair<Long, Long>>()
        hist.addLast(t0 to 0L)
        while (ts.any { it.isAlive }) {
            Thread.sleep(250)
            val now = System.nanoTime(); val b = total.get()
            hist.addLast(now to b)
            while (hist.size > 2 && now - hist.first().first > 1_500_000_000L) hist.removeFirst()
            val (ta, ba) = hist.first()
            val dt = (now - ta) / 1e9
            val mbps = if (dt > 0) (b - ba) * 8 / dt / 1e6 else 0.0
            pr?.sample?.invoke(name, mbps, minOf(1.0, (now - t0) / (secs * 1e9)))
        }
        ts.forEach { it.join() }
    }

    class Measured(val mbps: Double?, val bytes: Long, val bad: Int)

    fun download(c: OkHttpClient, s: Server, threads: Int, secs: Int, pr: Progress? = null): Measured {
        val total = AtomicLong(); val bad = AtomicInteger()
        val t0 = System.nanoTime(); val deadline = t0 + secs * 1_000_000_000L
        val ts = (1..threads).map {
            thread {
                val buf = ByteArray(65536)
                while (System.nanoTime() < deadline && !cancel.get()) {
                    try {
                        c.newCall(dlRequest(s, DL_CHUNK)).execute().use { r ->
                            if (!r.isSuccessful) { bad.incrementAndGet(); Thread.sleep(300); return@use }
                            val ins = r.body!!.byteStream()
                            while (true) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                total.addAndGet(n.toLong())
                                if (System.nanoTime() >= deadline || cancel.get()) break
                            }
                        }
                    } catch (e: Exception) {
                        if (System.nanoTime() < deadline) { bad.incrementAndGet(); Thread.sleep(300) }
                    }
                }
            }
        }
        monitor(ts, total, t0, secs, "download", pr)
        val sec = (System.nanoTime() - t0) / 1e9
        val b = total.get()
        return Measured(if (b > 0) b * 8 / sec / 1e6 else null, b, bad.get())
    }

    private class GenBody(val size: Long, val counter: AtomicLong, val deadline: Long) : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaType()
        override fun contentLength() = size
        override fun writeTo(sink: BufferedSink) {
            val chunk = ByteArray(65536).also { Random(7).nextBytes(it) }
            var left = size
            while (left > 0) {
                if (System.nanoTime() >= deadline || cancel.get()) throw IOException("deadline")
                val n = minOf(left, chunk.size.toLong()).toInt()
                sink.write(chunk, 0, n); sink.flush()
                counter.addAndGet(n.toLong()); left -= n
            }
        }
    }

    fun upload(c: OkHttpClient, s: Server, threads: Int, secs: Int, pr: Progress? = null): Measured {
        val ulUrl = s.ul ?: return Measured(null, 0, 0)
        val total = AtomicLong(); val bad = AtomicInteger()
        val t0 = System.nanoTime(); val deadline = t0 + secs * 1_000_000_000L
        val ts = (1..threads).map {
            thread {
                while (System.nanoTime() < deadline && !cancel.get()) {
                    try {
                        val gen = GenBody(UL_CHUNK, total, deadline)
                        val rb = Request.Builder().header("User-Agent", UA).url(ulUrl)
                        when (s.mode) {
                            "put" -> rb.put(gen)
                            "multipart" -> rb.post(MultipartBody.Builder().setType(MultipartBody.FORM)
                                .addFormDataPart("file", "speedtest.bin", gen).build())
                            else -> rb.post(gen)
                        }
                        c.newCall(rb.build()).execute().use { r -> if (!r.isSuccessful) bad.incrementAndGet() }
                    } catch (e: Exception) {
                        if (System.nanoTime() < deadline) { bad.incrementAndGet(); Thread.sleep(300) }
                    }
                }
            }
        }
        monitor(ts, total, t0, secs, "upload", pr)
        val sec = (System.nanoTime() - t0) / 1e9
        val b = total.get()
        return Measured(if (b > 0) b * 8 / sec / 1e6 else null, b, bad.get())
    }

    /** Одна «нога»: напрямую (p == null) или через один прокси. */
    fun runLeg(title: String, p0: ProxySpec?, s: Server, threads: Int, secs: Int, log: (String) -> Unit, pr: Progress? = null): LegResult {
        val h = arrayOfNulls<ProxySpec>(1); h[0] = p0
        val r = legInner(title, p0, s, threads, secs, log, h, pr)
        val p = h[0]
        val kind = when {
            p == null -> "direct"
            p.scheme == null -> "auto"
            p.scheme.startsWith("socks5") -> "socks5"
            p.scheme.startsWith("socks4") -> "socks4"
            else -> "HTTP"
        }
        return r.copy(kind = kind, pname = p0?.comment ?: "", hostPort = p?.let { "${it.host}_${it.port}" } ?: "")
    }

    private fun legInner(title: String, p0: ProxySpec?, s: Server, threads: Int, secs: Int, log: (String) -> Unit, holder: Array<ProxySpec?>, pr: Progress?): LegResult {
        pr?.phase?.invoke("check")
        var p = p0
        var client: OkHttpClient
        var ping: Long? = null
        try {
            if (p != null && p.scheme == null) {
                log("  определяю протокол прокси...")
                var found: ProxySpec? = null
                for (sc in listOf("socks5h", "http", "socks4")) {
                    try {
                        val cand = p.withScheme(sc)
                        ping = precheck(Net.client(cand, 10, 10), s)
                        found = cand; break
                    } catch (e: Exception) { /* пробуем следующий */ }
                }
                if (found == null) return LegResult(title, p0?.raw, false, "не отвечает ни по socks5, ни по http, ни по socks4", null, null, null, 0, 0, 0, 0, null)
                p = found; holder[0] = p
                log("  протокол: ${p.scheme}")
            }
            client = Net.client(p)
            if (ping == null) ping = precheck(Net.client(p, 10, 10), s)
        } catch (e: Exception) {
            return LegResult(title, p?.raw, false, "сервер недоступен: ${e.message ?: e.javaClass.simpleName}", null, null, null, 0, 0, 0, 0, null)
        }
        log("  связь есть, отклик ${ping} мс")
        val exit = exitInfo(client)
        pr?.phase?.invoke("download")
        val dl = download(client, s, threads, secs, pr)
        log("  скачивание: " + (dl.mbps?.let { "%.2f Мбит/с".format(it) } ?: "не удалось"))
        val ul = if (s.ul == null) null else { pr?.phase?.invoke("upload"); upload(client, s, threads, secs, pr) }
        if (s.ul == null) log("  отдача: сервер не поддерживает") else log("  отдача: " + (ul?.mbps?.let { "%.2f Мбит/с".format(it) } ?: "не удалось"))
        return LegResult(title, p?.raw, true, null, ping, dl.mbps, ul?.mbps, dl.bytes, ul?.bytes ?: 0, dl.bad, ul?.bad ?: 0, exit)
    }
}
