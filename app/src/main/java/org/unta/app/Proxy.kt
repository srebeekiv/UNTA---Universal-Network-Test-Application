package org.unta.app

import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/** Прокси из списка. Формат строки как у curl -x: [схема://][логин:пароль@]хост:порт  # комментарий */
data class ProxySpec(
    val raw: String, val scheme: String?, val host: String, val port: Int,
    val user: String?, val pass: String?, val comment: String
) {
    fun withScheme(s: String) = copy(scheme = s)
    fun label(): String = "${scheme ?: "?"}://$host:$port" + if (user != null) " (с авторизацией)" else ""

    companion object {
        private val RX = Regex("^(?:([a-zA-Z0-9]+)://)?(?:([^:@/]+):([^@/]*)@)?([^:/\\s]+):(\\d+)$")

        fun parseList(text: String): List<ProxySpec> = text.lines().mapNotNull { parse(it) }

        fun parse(rawLine: String): ProxySpec? {
            val comment = if (rawLine.contains('#')) rawLine.substringAfter('#').trim() else ""
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty() || line.startsWith("@")) return null
            val m = RX.matchEntire(line) ?: return null
            val scheme = m.groupValues[1].lowercase().ifEmpty { null }
            val user = m.groupValues[2].ifEmpty { null }
            val pass = if (user != null) m.groupValues[3] else null
            return ProxySpec(line, scheme, m.groupValues[4], m.groupValues[5].toInt(), user, pass, comment)
        }
    }
}

object Net {
    private val ipv4Dns = Dns { h ->
        Dns.SYSTEM.lookup(h).filter { it is Inet4Address }.ifEmpty { throw UnknownHostException(h) }
    }

    /** Клиент напрямую (p == null) или через прокси. SOCKS (4/4a/5, с логином) реализован сами:
     *  имена хостов уходят на прокси как есть (аналог socks5h у curl). */
    fun client(p: ProxySpec?, connectSec: Long = 8, readSec: Long = 30): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(connectSec, TimeUnit.SECONDS)
            .readTimeout(readSec, TimeUnit.SECONDS)
            .writeTimeout(readSec, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .proxy(Proxy.NO_PROXY)
            .dns(ipv4Dns)
        if (p != null) {
            val s = p.scheme ?: "socks5h"
            if (s.startsWith("socks")) {
                val v4 = s.startsWith("socks4")
                b.dns { h -> listOf(InetAddress.getByAddress(h, byteArrayOf(0, 0, 0, 0))) }
                b.socketFactory(SocksSocketFactory(p.host, p.port, p.user, p.pass, v4))
            } else {
                b.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(p.host, p.port)))
                if (p.user != null) {
                    b.proxyAuthenticator { _, resp ->
                        resp.request.newBuilder()
                            .header("Proxy-Authorization", Credentials.basic(p.user, p.pass ?: ""))
                            .build()
                    }
                }
            }
        }
        return b.build()
    }
}

class SocksSocketFactory(
    private val ph: String, private val pp: Int,
    private val user: String?, private val pass: String?, private val v4: Boolean
) : SocketFactory() {
    override fun createSocket(): Socket = SocksSocket(ph, pp, user, pass, v4)
    override fun createSocket(host: String?, port: Int): Socket = throw UnsupportedOperationException()
    override fun createSocket(host: String?, port: Int, la: InetAddress?, lp: Int): Socket = throw UnsupportedOperationException()
    override fun createSocket(host: InetAddress?, port: Int): Socket = throw UnsupportedOperationException()
    override fun createSocket(a: InetAddress?, port: Int, la: InetAddress?, lp: Int): Socket = throw UnsupportedOperationException()
}

class SocksSocket(
    private val ph: String, private val pp: Int,
    private val user: String?, private val pass: String?, private val v4: Boolean
) : Socket() {
    override fun connect(endpoint: SocketAddress, timeout: Int) {
        val ta = endpoint as InetSocketAddress
        val target = ta.hostString
        super.connect(InetSocketAddress(ph, pp), timeout)
        soTimeout = if (timeout > 0) timeout else 15000
        try {
            if (v4) hello4(target, ta.port) else hello5(target, ta.port)
        } finally {
            soTimeout = 0
        }
    }

    private fun hello5(host: String, port: Int) {
        val out = getOutputStream(); val din = DataInputStream(getInputStream())
        val auth = user != null
        out.write(if (auth) byteArrayOf(5, 2, 0, 2) else byteArrayOf(5, 1, 0)); out.flush()
        if (din.readUnsignedByte() != 5) throw IOException("ответ не похож на SOCKS5")
        when (din.readUnsignedByte()) {
            0 -> {}
            2 -> {
                if (!auth) throw IOException("SOCKS5: прокси требует логин и пароль")
                val u = user!!.toByteArray(); val p = (pass ?: "").toByteArray()
                val a = ByteArrayOutputStream()
                a.write(1); a.write(u.size); a.write(u); a.write(p.size); a.write(p)
                out.write(a.toByteArray()); out.flush()
                din.readUnsignedByte()
                if (din.readUnsignedByte() != 0) throw IOException("SOCKS5: неверный логин или пароль")
            }
            else -> throw IOException("SOCKS5: нет подходящего метода авторизации")
        }
        val rq = ByteArrayOutputStream()
        rq.write(byteArrayOf(5, 1, 0))
        val ip = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").matchEntire(host)
        if (ip != null) {
            rq.write(1); ip.groupValues.drop(1).forEach { rq.write(it.toInt()) }
        } else {
            val h = host.toByteArray(); rq.write(3); rq.write(h.size); rq.write(h)
        }
        rq.write(port shr 8); rq.write(port and 255)
        out.write(rq.toByteArray()); out.flush()
        din.readUnsignedByte()
        val rep = din.readUnsignedByte()
        din.readUnsignedByte()
        val atyp = din.readUnsignedByte()
        val skip = when (atyp) { 1 -> 4; 4 -> 16; 3 -> din.readUnsignedByte(); else -> 0 }
        din.skipBytes(skip + 2)
        if (rep != 0) throw IOException("SOCKS5: прокси отказал в соединении (код $rep)")
    }

    private fun hello4(host: String, port: Int) {
        val out = getOutputStream(); val din = DataInputStream(getInputStream())
        val rq = ByteArrayOutputStream()
        rq.write(4); rq.write(1); rq.write(port shr 8); rq.write(port and 255)
        rq.write(byteArrayOf(0, 0, 0, 1))
        rq.write((user ?: "").toByteArray()); rq.write(0)
        rq.write(host.toByteArray()); rq.write(0)
        out.write(rq.toByteArray()); out.flush()
        din.readUnsignedByte()
        val st = din.readUnsignedByte()
        din.skipBytes(6)
        if (st != 90) throw IOException("SOCKS4: прокси отказал (код $st)")
    }
}
