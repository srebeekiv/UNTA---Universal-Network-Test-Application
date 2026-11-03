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
data class Server(val code: String, val label: String, val dl: String, val ul: String?, val mode: String)

object Servers {
    val all = listOf(
        Server("cf", "Cloudflare-Anycast", "https://speed.cloudflare.com/__down?bytes={BYTES}", "https://speed.cloudflare.com/__up", "multipart"),
        Server("tele2", "Tele2-Anycast-Europe", "http://speedtest.tele2.net/1GB.zip", "http://speedtest.tele2.net/upload.php", "put"),
        Server("librespeed", "LibreSpeed-Official", "https://librespeed.org/backend/garbage.php?ckSize={MB}", "https://librespeed.org/backend/empty.php", "post-raw"),
    )
    fun byCode(c: String) = all.firstOrNull { it.code == c } ?: all[0]
}

data class LegResult(
    val title: String, val proxy: String?, val reachable: Boolean, val error: String?, val pingMs: Long?,
    val dlMbps: Double?, val ulMbps: Double?, val dlBytes: Long, val ulBytes: Long,
    val dlBad: Int, val ulBad: Int, val exit: JSONObject?
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

    fun exitInfo(c: OkHttpClient): JSONObject? = try {
        c.newCall(Request.Builder().header("User-Agent", UA).url("https://speed.cloudflare.com/meta").build())
            .execute().use { r ->
                val j = JSONObject(r.body!!.string())
                val o = JSONObject()
                for (k in listOf("clientIp", "country", "region", "city", "asn", "asOrganization", "colo", "latitude", "longitude"))
                    if (j.has(k)) o.put(k, j.get(k))
                o
            }
    } catch (e: Exception) { null }

    class Measured(val mbps: Double?, val bytes: Long, val bad: Int)

    fun download(c: OkHttpClient, s: Server, threads: Int, secs: Int): Measured {
        val total = AtomicLong(); val bad = AtomicInteger()
        val t0 = System.nanoTime(); val deadline = t0 + secs * 1_000_000_000L
        val ts = (1..threads).map {
            thread {
                val buf = ByteArray(65536)
                while (System.nanoTime() < deadline) {
                    try {
                        c.newCall(dlRequest(s, DL_CHUNK)).execute().use { r ->
                            if (!r.isSuccessful) { bad.incrementAndGet(); Thread.sleep(300); return@use }
                            val ins = r.body!!.byteStream()
                            while (true) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                total.addAndGet(n.toLong())
                                if (System.nanoTime() >= deadline) break
                            }
                        }
                    } catch (e: Exception) {
                        if (System.nanoTime() < deadline) { bad.incrementAndGet(); Thread.sleep(300) }
                    }
                }
            }
        }
        ts.forEach { it.join() }
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
                if (System.nanoTime() >= deadline) throw IOException("deadline")
                val n = minOf(left, chunk.size.toLong()).toInt()
                sink.write(chunk, 0, n); sink.flush()
                counter.addAndGet(n.toLong()); left -= n
            }
        }
    }

    fun upload(c: OkHttpClient, s: Server, threads: Int, secs: Int): Measured {
        val ulUrl = s.ul ?: return Measured(null, 0, 0)
        val total = AtomicLong(); val bad = AtomicInteger()
        val t0 = System.nanoTime(); val deadline = t0 + secs * 1_000_000_000L
        val ts = (1..threads).map {
            thread {
                while (System.nanoTime() < deadline) {
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
        ts.forEach { it.join() }
        val sec = (System.nanoTime() - t0) / 1e9
        val b = total.get()
        return Measured(if (b > 0) b * 8 / sec / 1e6 else null, b, bad.get())
    }

    /** Одна «нога»: напрямую (p == null) или через один прокси. */
    fun runLeg(title: String, p0: ProxySpec?, s: Server, threads: Int, secs: Int, log: (String) -> Unit): LegResult {
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
                        ping = precheck(Net.client(cand, 5, 7), s)
                        found = cand; break
                    } catch (e: Exception) { /* пробуем следующий */ }
                }
                if (found == null) return LegResult(title, p0?.raw, false, "не отвечает ни по socks5, ни по http, ни по socks4", null, null, null, 0, 0, 0, 0, null)
                p = found
                log("  протокол: ${p.scheme}")
            }
            client = Net.client(p)
            if (ping == null) ping = precheck(Net.client(p, 5, 7), s)
        } catch (e: Exception) {
            return LegResult(title, p?.raw, false, "сервер недоступен: ${e.message ?: e.javaClass.simpleName}", null, null, null, 0, 0, 0, 0, null)
        }
        log("  связь есть, отклик ${ping} мс")
        val exit = exitInfo(client)
        val dl = download(client, s, threads, secs)
        log("  скачивание: " + (dl.mbps?.let { "%.2f Мбит/с".format(it) } ?: "не удалось"))
        val ul = if (s.ul == null) null else upload(client, s, threads, secs)
        if (s.ul == null) log("  отдача: сервер не поддерживает") else log("  отдача: " + (ul?.mbps?.let { "%.2f Мбит/с".format(it) } ?: "не удалось"))
        return LegResult(title, p?.raw, true, null, ping, dl.mbps, ul?.mbps, dl.bytes, ul?.bytes ?: 0, dl.bad, ul?.bad ?: 0, exit)
    }
}
