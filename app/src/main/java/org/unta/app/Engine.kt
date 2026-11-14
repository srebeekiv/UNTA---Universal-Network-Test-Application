package org.unta.app

import okhttp3.Call
import okhttp3.EventListener
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
    private fun t2(code: String, label: String, short: String, host: String) =
        Server(code, label, short, "http://$host/1GB.zip", "http://$host/upload.php", "put")
    private fun dlOnly(code: String, label: String, short: String, url: String) =
        Server(code, "$label (только скачивание)", short, url, null, "")

    // Порядок: сначала проверенные рабочие. Убраны мёртвые узлы Tele2 (проверено с телефона, ПК и через прокси в Нидерландах 2026-11-09).
    val all = listOf(
        Server("cf", "Cloudflare-Anycast", "Cloudflare", "https://speed.cloudflare.com/__down?bytes={BYTES}", "https://speed.cloudflare.com/__up", "multipart"),
        Server("librespeed", "LibreSpeed (авто-выбор сервера)", "LibreSpeed", "https://librespeed.org/backend/garbage.php?ckSize={MB}", "https://librespeed.org/backend/empty.php", "post-raw"),
        t2("tele2-lt", "LT Vilnius-Tele2", "Tele2-LT", "vln038-speedtest-2.tele2.net"),
        t2("tele2-lv", "LV Riga-Tele2 (доступен не из всех сетей)", "Tele2-LV", "bks4-speedtest-1.tele2.net"),
        dlOnly("de-hetzner", "DE Falkenstein-Hetzner", "Hetzner-DE", "https://fsn1-speed.hetzner.com/100MB.bin"),
        dlOnly("fi-hetzner", "FI Helsinki-Hetzner", "Hetzner-FI", "https://hel1-speed.hetzner.com/100MB.bin"),
        dlOnly("us-hetzner", "US Ashburn-Hetzner", "Hetzner-US", "https://ash-speed.hetzner.com/100MB.bin"),
        dlOnly("sg-hetzner", "SG Singapore-Hetzner", "Hetzner-SG", "https://sin-speed.hetzner.com/100MB.bin"),
        dlOnly("fr-gra", "FR Gravelines-OVH", "OVH-Gravelines", "https://lil2-gra.speedtest.network.ovh.net/files/1Gb.dat"),
        dlOnly("fr-rbx", "FR Roubaix-OVH", "OVH-Roubaix", "https://lil1-rbx.speedtest.network.ovh.net/files/1Gb.dat"),
        dlOnly("uk-eri", "GB Erith-OVH", "OVH-Erith", "https://lon1-eri.speedtest.network.ovh.net/files/1Gb.dat"),
        dlOnly("ca-bhs", "CA Beauharnois-OVH", "OVH-Beauharnois", "https://ymq1-bhs.speedtest.network.ovh.net/files/1Gb.dat"),
        dlOnly("us-hil", "US Hillsboro-OVH", "OVH-Hillsboro", "https://pdx1-hil.speedtest.network.ovh.net/files/1Gb.dat"),
        dlOnly("au-syd", "AU Sydney-OVH", "OVH-Sydney", "https://syd1-sy2.speedtest.network.ovh.net/files/1Gb.dat"),
        dlOnly("in-bom", "IN Mumbai-OVH", "OVH-Mumbai", "https://bom1-ynm1.speedtest.network.ovh.net/files/1Gb.dat"),
    )
    fun byCode(c: String) = all.firstOrNull { it.code == c } ?: all[0]
}

data class ProxyCheck(
    val label: String, val name: String, val kind: String, val ok: Boolean,
    val tcpMs: Long, val reqMs: Long, val warmMs: Long,
    val ip: String, val loc: String, val colo: String, val error: String
)

data class LegResult(
    val title: String, val proxy: String?, val reachable: Boolean, val error: String?, val pingMs: Long?,
    val dlMbps: Double?, val ulMbps: Double?, val dlBytes: Long, val ulBytes: Long,
    val dlBad: Int, val ulBad: Int, val exit: JSONObject?,
    val kind: String = "direct", val pname: String = "", val hostPort: String = "", val server: String = "", val latency: JSONObject? = null,
    val graph: JSONObject? = null
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
        for (u in listOf("https://ipinfo.io/json", "http://ip-api.com/json", "https://speed.cloudflare.com/cdn-cgi/trace")) {
            try {
                val body = c.newCall(Request.Builder().header("User-Agent", UA).header("Accept", "application/json").url(u).build())
                    .execute().use { r ->
                        if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                        r.body!!.string()
                    }
                val j = if (body.trimStart().startsWith("{")) JSONObject(body) else JSONObject().also { o ->
                    body.lines().forEach { ln -> val kv = ln.split("=", limit = 2); if (kv.size == 2) o.put(kv[0], kv[1]) }
                }
                val ip = listOf("clientIp", "ip", "query").map { j.optString(it) }.firstOrNull { it.isNotEmpty() }
                if (ip == null) { dbg.append("$u: нет поля IP: ${body.take(120)}; "); continue }
                val o = JSONObject().put("clientIp", ip).put("src", java.net.URI(u).host)
                for (k in listOf("city", "country", "region", "colo", "asn")) { val v = j.optString(k); if (v.isNotEmpty()) o.put(k, v) }
                val org = listOf("asOrganization", "org", "isp").map { j.optString(it) }.firstOrNull { it.isNotEmpty() }
                if (org != null) o.put("asOrganization", org)
                val loc = j.optString("loc")
                if (!o.has("country") && loc.length == 2) o.put("country", loc)
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

    /** Раз в 250 мс считает скорость по окну 2 с и сглаживает её (EMA) для графика.
     *  Возвращает [байт к моменту окончания разгона, наносекунд от старта до этого момента]. */
    private fun monitor(ts: List<Thread>, total: AtomicLong, t0: Long, secs: Int, name: String, pr: Progress?): LongArray {
        val hist = ArrayDeque<Pair<Long, Long>>()
        hist.addLast(t0 to 0L)
        val skipAt = minOf(2.0, secs * 0.2)
        val sk = LongArray(2)
        var ema = -1.0
        while (ts.any { it.isAlive }) {
            Thread.sleep(250)
            val now = System.nanoTime(); val b = total.get()
            hist.addLast(now to b)
            while (hist.size > 2 && now - hist.first().first > 2_000_000_000L) hist.removeFirst()
            val (ta, ba) = hist.first()
            val dt = (now - ta) / 1e9
            val raw = if (dt > 0) (b - ba) * 8 / dt / 1e6 else 0.0
            ema = if (ema < 0) raw else ema * 0.6 + raw * 0.4
            if (sk[1] == 0L && (now - t0) / 1e9 >= skipAt) { sk[0] = b; sk[1] = now - t0 }
            pr?.sample?.invoke(name, ema, minOf(1.0, (now - t0) / (secs * 1e9)))
        }
        ts.forEach { it.join() }
        return sk
    }

    /** Итоговая скорость без разгона TCP (первые ~20% времени, но не больше 2 с). */
    private fun finish(total: AtomicLong, t0: Long, secs: Int, sk: LongArray, bad: Int): Measured {
        val sec = minOf((System.nanoTime() - t0) / 1e9, secs.toDouble())
        val b = total.get()
        if (b <= 0 || sec <= 0) return Measured(null, b, bad)
        val skipSec = sk[1] / 1e9
        val mbps = if (sk[1] > 0 && sec - skipSec >= 1.0) (b - sk[0]) * 8 / (sec - skipSec) / 1e6 else b * 8 / sec / 1e6
        return Measured(mbps, b, bad)
    }

    private class LibreNode(val name: String, val base: String, val dl: String, val ul: String, val ping: String)

    /** Официальный список публичных серверов LibreSpeed (сам librespeed.org замеров не отдаёт). */
    private fun libreNodes(c: OkHttpClient): List<LibreNode> {
        val out = ArrayList<LibreNode>()
        try {
            val body = c.newCall(Request.Builder().header("User-Agent", UA).url("https://librespeed.org/backend-servers/servers.json").build())
                .execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    r.body!!.string()
                }
            val a = org.json.JSONArray(body)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                var base = o.optString("server")
                if (base.isEmpty()) continue
                if (base.startsWith("//")) base = "https:$base"
                if (!base.endsWith("/")) base += "/"
                out.add(LibreNode(o.optString("name", base), base, o.optString("dlURL", "garbage.php"), o.optString("ulURL", "empty.php"), o.optString("pingURL", "empty.php")))
            }
        } catch (e: Exception) { /* возьмём запасные */ }
        if (out.isEmpty()) {
            for ((n, b) in listOf("Frankfurt (raiun.de)" to "https://librespeed.raiun.de/", "New York (sbg.net.au)" to "https://nyc.speedtest.sbg.net.au/", "Amsterdam (host.speedtest.net)" to "https://ams.host.speedtest.net/"))
                out.add(LibreNode(n, b, "garbage.php", "empty.php", "empty.php"))
        }
        return out
    }

    /** Выбираем самый быстрый по отклику из 8 случайных серверов списка (через тот же прокси, что и замер). */
    fun resolveLibre(c: OkHttpClient, log: (String) -> Unit): Server {
        val nodes = libreNodes(c)
        val cand = (nodes.filter { it.base.startsWith("https://") }.ifEmpty { nodes }).shuffled().take(8)
        val results = java.util.Collections.synchronizedList(ArrayList<Pair<LibreNode, Long>>())
        val quick = c.newBuilder().connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS).readTimeout(4, java.util.concurrent.TimeUnit.SECONDS).build()
        val ts = cand.map { n ->
            thread {
                try {
                    val t0 = System.nanoTime()
                    quick.newCall(Request.Builder().header("User-Agent", UA).url(n.base + n.ping).build()).execute().use { if (!it.isSuccessful) throw IOException("HTTP ${it.code}") }
                    results.add(n to (System.nanoTime() - t0) / 1_000_000)
                } catch (_: Exception) {}
            }
        }
        ts.forEach { it.join() }
        val best = results.minByOrNull { it.second }?.first
            ?: throw IOException("ни один из ${cand.size} проверенных серверов LibreSpeed не ответил")
        log("  LibreSpeed: ${best.name} (${best.base})")
        return Server("librespeed", "LibreSpeed: ${best.name}", "LibreSpeed", best.base + best.dl + "?ckSize={MB}", best.base + best.ul, "post-raw")
    }

    private class Timing : EventListener() {
        var dns = -1L; var connect = -1L; var tls = -1L
        val waits = ArrayList<Long>()
        private var dnsS = 0L; private var conS = 0L; private var tlsS = 0L; private var reqEnd = 0L
        private fun since(t: Long) = (System.nanoTime() - t) / 1_000_000
        override fun dnsStart(call: Call, domainName: String) { dnsS = System.nanoTime() }
        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<java.net.InetAddress>) { if (dns < 0) dns = since(dnsS) }
        override fun connectStart(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) { conS = System.nanoTime() }
        override fun secureConnectStart(call: Call) { tlsS = System.nanoTime() }
        override fun secureConnectEnd(call: Call, handshake: okhttp3.Handshake?) { if (tls < 0) tls = since(tlsS) }
        override fun connectEnd(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy, protocol: okhttp3.Protocol?) {
            if (connect < 0) connect = since(conS) - maxOf(tls, 0L)
        }
        override fun requestHeadersEnd(call: Call, request: Request) { reqEnd = System.nanoTime() }
        override fun responseHeadersStart(call: Call) { waits.add(since(reqEnd)) }
    }

    /** DNS / TCP (у прокси вместе с рукопожатием SOCKS/CONNECT) / TLS по первому запросу и отклик HTTP: лучшее из 4 запросов. */
    fun latency(c: OkHttpClient, s: Server): JSONObject? = try {
        val t = Timing()
        val c2 = c.newBuilder().eventListener(t).build()
        repeat(4) {
            c2.newCall(dlRequest(s, 1000)).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                r.body!!.byteStream().read(ByteArray(1024))
            }
        }
        JSONObject().put("dns_ms", t.dns).put("connect_ms", t.connect).put("tls_ms", t.tls)
            .put("first_ms", t.waits.firstOrNull() ?: -1).put("rtt_ms", t.waits.minOrNull() ?: -1)
    } catch (e: Exception) { null }

    /** Быстрая проверка одного прокси: 1) принимает ли он TCP, 2) выходит ли через него интернет (Cloudflare trace). Скорость не меряем. */
    fun checkProxy(p: ProxySpec): ProxyCheck {
        val t0 = System.nanoTime()
        try {
            java.net.Socket().use { it.connect(java.net.InetSocketAddress(p.host, p.port), 5000) }
        } catch (e: Exception) {
            return ProxyCheck(p.label(), p.comment, p.scheme ?: "auto", false, -1, -1, -1, "", "", "",
                "прокси не принимает соединение: ${e.message ?: e.javaClass.simpleName}")
        }
        val tcp = (System.nanoTime() - t0) / 1_000_000
        val cands = if (p.scheme == null) listOf("socks5h", "http", "socks4") else listOf(p.scheme)
        var lastErr = ""
        for (sc in cands) {
            try {
                val cand = p.withScheme(sc)
                val c = Net.client(cand, 5, 8)
                val req = Request.Builder().header("User-Agent", UA).url("https://speed.cloudflare.com/cdn-cgi/trace").build()
                val t1 = System.nanoTime()
                val body = c.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    r.body!!.string()
                }
                val first = (System.nanoTime() - t1) / 1_000_000
                val t2 = System.nanoTime()
                c.newCall(req).execute().use { r -> r.body!!.string() }
                val warm = (System.nanoTime() - t2) / 1_000_000
                val kv = HashMap<String, String>()
                body.lines().forEach { ln -> val a = ln.split("=", limit = 2); if (a.size == 2) kv[a[0]] = a[1] }
                val kind = when { sc.startsWith("socks5") -> "socks5"; sc.startsWith("socks4") -> "socks4"; else -> "HTTP" }
                return ProxyCheck(cand.label().replace(Regex("^[a-z0-9]+://"), "$kind://"), p.comment, kind, true, tcp, first, warm,
                    kv["ip"] ?: "", kv["loc"] ?: "", kv["colo"] ?: "", "")
            } catch (e: Exception) { lastErr = "${sc}: ${e.message ?: e.javaClass.simpleName}" }
        }
        return ProxyCheck(p.label(), p.comment, p.scheme ?: "auto", false, tcp, -1, -1, "", "", "",
            "порт открыт (${tcp} мс), но интернета через прокси нет. Последняя ошибка: $lastErr")
    }

    /** Все прокси параллельно (до 8 одновременно). onDone вызывается по мере готовности. */
    fun checkProxies(list: List<ProxySpec>, onDone: (Int, Int, ProxyCheck) -> Unit): List<ProxyCheck> {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(8, maxOf(1, list.size)))
        val results = arrayOfNulls<ProxyCheck>(list.size)
        val done = AtomicInteger()
        val fs = list.mapIndexed { i, p ->
            pool.submit(Runnable {
                val r = checkProxy(p)
                results[i] = r
                onDone(done.incrementAndGet(), list.size, r)
            })
        }
        fs.forEach { try { it.get() } catch (_: Exception) {} }
        pool.shutdown()
        return results.filterNotNull()
    }

    class Measured(val mbps: Double?, val bytes: Long, val bad: Int)

    fun download(c: OkHttpClient, s: Server, threads: Int, secs: Int, pr: Progress? = null): Measured {
        val total = AtomicLong(); val bad = AtomicInteger()
        val t0 = System.nanoTime(); val deadline = t0 + secs * 1_000_000_000L
        val ts = (1..threads).map { idx ->
            thread {
                Thread.sleep(150L * (idx - 1))
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
        val sk = monitor(ts, total, t0, secs, "download", pr)
        return finish(total, t0, secs, sk, bad.get())
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
        val ts = (1..threads).map { idx ->
            thread {
                Thread.sleep(150L * (idx - 1))
                while (System.nanoTime() < deadline && !cancel.get()) {
                    try {
                        val gen = GenBody(UL_CHUNK / 2 + (idx - 1) * 1_500_000L, total, deadline)
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
        val sk = monitor(ts, total, t0, secs, "upload", pr)
        return finish(total, t0, secs, sk, bad.get())
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

    private fun legInner(title: String, p0: ProxySpec?, s0: Server, threads: Int, secs: Int, log: (String) -> Unit, holder: Array<ProxySpec?>, pr0: Progress?): LegResult {
        // копим точки графика, чтобы сохранить их в файл (для истории и «поделиться»)
        val dlPts = ArrayList<DoubleArray>(); val ulPts = ArrayList<DoubleArray>()
        val pr = Progress(
            phase = { ph -> pr0?.phase?.invoke(ph) },
            sample = { ph, m, f ->
                (if (ph == "upload") ulPts else dlPts).add(doubleArrayOf(f * secs, m))
                pr0?.sample?.invoke(ph, m, f)
            }
        )
        pr.phase("check")
        var s = s0
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
                        ping = precheck(Net.client(cand, 10, 10), if (s.code == "librespeed") Servers.all[0] else s)
                        found = cand; break
                    } catch (e: Exception) { /* пробуем следующий */ }
                }
                if (found == null) return LegResult(title, p0?.raw, false, "не отвечает ни по socks5, ни по http, ни по socks4", null, null, null, 0, 0, 0, 0, null)
                p = found; holder[0] = p
                log("  протокол: ${p.scheme}")
            }
            client = Net.client(p)
            if (s0.code == "librespeed") { s = resolveLibre(client, log); ping = null }
            if (ping == null) ping = precheck(Net.client(p, 10, 10), s)
        } catch (e: Exception) {
            return LegResult(title, p?.raw, false, "сервер недоступен: ${e.message ?: e.javaClass.simpleName}", null, null, null, 0, 0, 0, 0, null)
        }
        val lat = latency(client, s)
        val rtt = lat?.optLong("rtt_ms", -1) ?: -1L
        if (rtt >= 0) ping = rtt
        if (lat != null) log("  связь есть: DNS ${lat.optLong("dns_ms")}, TCP ${lat.optLong("connect_ms")}, TLS ${lat.optLong("tls_ms")} мс; отклик HTTP $ping мс")
        else log("  связь есть, отклик ${ping} мс")
        val exit = exitInfo(client)
        pr?.phase?.invoke("download")
        val dl = download(client, s, threads, secs, pr)
        log("  скачивание: " + (dl.mbps?.let { "%.2f Мбит/с".format(it) } ?: "не удалось"))
        val ul = if (s.ul == null) null else { pr?.phase?.invoke("upload"); upload(client, s, threads, secs, pr) }
        if (s.ul == null) log("  отдача: сервер не поддерживает") else log("  отдача: " + (ul?.mbps?.let { "%.2f Мбит/с".format(it) } ?: "не удалось"))
        fun arr(l: List<DoubleArray>) = org.json.JSONArray().also { a ->
            l.forEach { a.put(org.json.JSONArray().put(Math.round(it[0] * 10) / 10.0).put(Math.round(it[1] * 10) / 10.0)) }
        }
        val graph = JSONObject().put("secs", secs).put("phases", if (s.ul != null) 2 else 1).put("dl", arr(dlPts)).put("ul", arr(ulPts))
        return LegResult(title, p?.raw, true, null, ping, dl.mbps, ul?.mbps, dl.bytes, ul?.bytes ?: 0, dl.bad, ul?.bad ?: 0, exit, server = s.label, latency = lat, graph = graph)
    }
}
