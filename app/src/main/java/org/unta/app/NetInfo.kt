package org.unta.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.telephony.CellIdentityNr
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import android.location.LocationManager
import org.json.JSONArray
import org.json.JSONObject

/** Сбор сведений о сети, на которой идёт измерение. Всё, что недоступно (нет разрешения), просто пропускается. */
object NetInfo {
    fun collect(ctx: Context): JSONObject {
        val o = JSONObject()
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val net = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(net)
        val transport = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
        o.put("transport", transport)
        try {
            val ips = JSONArray()
            cm.getLinkProperties(net)?.linkAddresses?.forEach { ips.put(it.address.hostAddress) }
            o.put("local_ips", ips)
        } catch (_: Exception) {}
        if (transport == "wifi") o.put("wifi", wifi(ctx))
        if (transport == "mobile" || mobileAvailable(ctx)) o.put("mobile", mobile(ctx))
        location(ctx)?.let { o.put("location", it) }
        return o
    }

    private fun mobileAvailable(ctx: Context) = try {
        ctx.getSystemService(TelephonyManager::class.java).simState == TelephonyManager.SIM_STATE_READY
    } catch (_: Exception) { false }

    @Suppress("DEPRECATION")
    private fun wifi(ctx: Context): JSONObject {
        val o = JSONObject()
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wi = wm.connectionInfo
            o.put("ssid", wi.ssid.trim('"'))
            o.put("bssid", wi.bssid)
            o.put("rssi_dbm", wi.rssi)
            if (wi.frequency > 0) {
                o.put("frequency_mhz", wi.frequency)
                o.put("channel", channel(wi.frequency))
            }
            o.put("link_speed_mbps", wi.linkSpeed)
            o.put("tx_link_speed_mbps", wi.txLinkSpeedMbps)
            o.put("rx_link_speed_mbps", wi.rxLinkSpeedMbps)
            if (android.os.Build.VERSION.SDK_INT >= 30) o.put("standard", when (wi.wifiStandard) {
                1 -> "legacy"; 4 -> "802.11n (Wi-Fi 4)"; 5 -> "802.11ac (Wi-Fi 5)"
                6 -> "802.11ax (Wi-Fi 6)"; 7 -> "802.11be (Wi-Fi 7)"; else -> "unknown"
            })
            try {
                wm.scanResults.firstOrNull { it.BSSID.equals(wi.bssid, true) }?.let {
                    o.put("width_mhz", when (it.channelWidth) {
                        0 -> 20; 1 -> 40; 2 -> 80; 3 -> 160; 4 -> "80+80"; 5 -> 320; else -> 0
                    })
                }
            } catch (_: Exception) {}
        } catch (e: Exception) { o.put("error", e.message ?: e.javaClass.simpleName) }
        return o
    }

    private fun channel(f: Int) = when {
        f in 2412..2472 -> (f - 2407) / 5
        f == 2484 -> 14
        f in 5000..5900 -> (f - 5000) / 5
        f >= 5955 -> (f - 5950) / 5
        else -> 0
    }

    private fun mobile(ctx: Context): JSONObject {
        val o = JSONObject()
        try {
            val tm = ctx.getSystemService(TelephonyManager::class.java)
            o.put("operator", tm.networkOperatorName)
            o.put("mcc_mnc", tm.networkOperator)
            try {
                val t = tm.dataNetworkType
                val ov = if (android.os.Build.VERSION.SDK_INT >= 31) (DisplayInfoProbe.overrideType(ctx) ?: 0) else 0
                o.put("network_type", techName(t)); o.put("network_type_code", t)
                if (ov != 0) o.put("display_override", overrideName(ov))
                o.put("generation", generation(t, ov))
            } catch (_: SecurityException) {}
            tm.signalStrength?.cellSignalStrengths?.forEach { cs ->
                if (cs.dbm == Int.MAX_VALUE || cs.dbm == 0) return@forEach
                when (cs) {
                    is CellSignalStrengthLte -> {
                        o.put("signal_dbm", cs.dbm); o.put("level", cs.level)
                        o.put("rsrp", cs.rsrp); o.put("rsrq", cs.rsrq); o.put("rssnr", cs.rssnr)
                    }
                    is CellSignalStrengthNr -> {
                        o.put("nr_ss_rsrp", cs.ssRsrp); o.put("nr_ss_rsrq", cs.ssRsrq); o.put("nr_ss_sinr", cs.ssSinr)
                        if (!o.has("signal_dbm")) { o.put("signal_dbm", cs.dbm); o.put("level", cs.level) }
                    }
                    is CellSignalStrengthWcdma -> {
                        if (!o.has("signal_dbm")) { o.put("signal_dbm", cs.dbm); o.put("level", cs.level) }
                        o.put("rscp", cs.dbm)  // у WCDMA уровень сигнала в дБм это и есть RSCP
                        if (cs.ecNo != Int.MAX_VALUE) o.put("ecno", cs.ecNo)
                    }
                    is CellSignalStrengthGsm -> {
                        if (!o.has("signal_dbm")) { o.put("signal_dbm", cs.dbm); o.put("level", cs.level) }
                        if (cs.timingAdvance != Int.MAX_VALUE) o.put("ta", cs.timingAdvance)
                    }
                    else -> if (!o.has("signal_dbm")) { o.put("signal_dbm", cs.dbm); o.put("level", cs.level) }
                }
            }
            cell(tm)?.let { o.put("cell", it) }
        } catch (e: Exception) { o.put("error", e.message ?: e.javaClass.simpleName) }
        return o
    }

    /** Технические имена типов сети Android (TelephonyManager.NETWORK_TYPE_*). */
    private fun techName(t: Int) = when (t) {
        1 -> "GPRS"; 2 -> "EDGE"; 3 -> "UMTS"; 4 -> "CDMA"; 5 -> "EVDO_0"; 6 -> "EVDO_A"; 7 -> "1xRTT"
        8 -> "HSDPA"; 9 -> "HSUPA"; 10 -> "HSPA"; 11 -> "iDEN"; 12 -> "EVDO_B"; 13 -> "LTE"; 14 -> "eHRPD"
        15 -> "HSPA+"; 16 -> "GSM"; 17 -> "TD-SCDMA"; 18 -> "IWLAN"; 19 -> "LTE_CA"; 20 -> "NR"; 0 -> "UNKNOWN"
        else -> "TYPE_$t"
    }

    fun techNote(name: String) = when (name) {
        "GSM" -> "GSM (2G)"
        "GPRS" -> "GPRS (2.5G, пакетные данные поверх GSM)"
        "EDGE" -> "EDGE / EGPRS (2.75G, значок E)"
        "UMTS" -> "UMTS / WCDMA (3G)"
        "HSDPA" -> "HSDPA (3.5G, значок H)"
        "HSUPA" -> "HSUPA (3.5G)"
        "HSPA" -> "HSPA (3.5G, значок H)"
        "HSPA+" -> "HSPA+ (3.75G, значок H+)"
        "TD-SCDMA" -> "TD-SCDMA (3G)"
        "LTE" -> "LTE (4G)"
        "LTE_CA" -> "LTE-Advanced, агрегация несущих (4G+)"
        "NR" -> "5G NR (автономный режим)"
        "IWLAN" -> "IWLAN (звонки и данные через Wi-Fi)"
        else -> name
    }

    /** Поколение «как называют операторы». Половинки (2.5G, 3.5G, 4.5G) это маркетинг, а не стандарты. */
    private fun generation(t: Int, override: Int): String {
        val base = when (t) {
            16, 4, 11 -> "2G"; 1, 7 -> "2.5G"; 2 -> "2.75G"
            3, 5, 6, 17 -> "3G"; 8, 9, 10, 12, 14 -> "3.5G"; 15 -> "3.75G"
            13 -> "4G"; 19 -> "4G+"; 20 -> "5G"; 18 -> "Wi-Fi Calling"; else -> "mobile"
        }
        return when (override) { 1 -> if (t == 13) "4G+" else base; 2 -> "4.5G"; 3 -> "5G"; 4, 5 -> "5G+"; else -> base }
    }

    private fun overrideName(o: Int) = when (o) {
        1 -> "LTE_CA"; 2 -> "LTE_ADVANCED_PRO"; 3 -> "NR_NSA"; 4 -> "NR_NSA_MMWAVE"; 5 -> "NR_ADVANCED"; else -> ""
    }

    fun overrideNote(n: String) = when (n) {
        "LTE_CA" -> "LTE_CA (LTE-Advanced, агрегация несущих)"
        "LTE_ADVANCED_PRO" -> "LTE_ADVANCED_PRO (LTE-Advanced Pro, в рекламе 4.5G или 5Ge)"
        "NR_NSA" -> "NR_NSA (5G поверх сети LTE)"
        "NR_NSA_MMWAVE" -> "NR_NSA_MMWAVE (5G mmWave поверх LTE)"
        "NR_ADVANCED" -> "NR_ADVANCED (5G Advanced / 5G+)"
        else -> n
    }

    fun genRu(g: String) = g.replace('.', ',')

    /** Ячейка, к которой подключён телефон: канал, PCI, диапазон. Нужна геолокация. */
    private fun cell(tm: TelephonyManager): JSONObject? = try {
        val ci = tm.allCellInfo?.firstOrNull { it.isRegistered }
        fun JSONObject.i(k: String, v: Int) { if (v != Int.MAX_VALUE && v != -1) put(k, v) }
        when (ci) {
            is CellInfoLte -> {
                val id = ci.cellIdentity
                JSONObject().put("tech", "LTE").also {
                    it.i("EARFCN", id.earfcn); it.i("PCI", id.pci); it.i("TAC", id.tac); it.i("CI", id.ci)
                    if (android.os.Build.VERSION.SDK_INT >= 30 && id.bands.isNotEmpty()) it.put("диапазоны", id.bands.joinToString(","))
                }
            }
            is CellInfoNr -> {
                val id = ci.cellIdentity as CellIdentityNr
                JSONObject().put("tech", "NR").also {
                    it.i("NRARFCN", id.nrarfcn); it.i("PCI", id.pci); it.i("TAC", id.tac)
                    if (android.os.Build.VERSION.SDK_INT >= 30 && id.bands.isNotEmpty()) it.put("диапазоны", id.bands.joinToString(","))
                }
            }
            is CellInfoWcdma -> {
                val id = ci.cellIdentity
                JSONObject().put("tech", "WCDMA").also { it.i("UARFCN", id.uarfcn); it.i("PSC", id.psc); it.i("LAC", id.lac); it.i("CID", id.cid) }
            }
            is CellInfoGsm -> {
                val id = ci.cellIdentity
                JSONObject().put("tech", "GSM").also { it.i("ARFCN", id.arfcn); it.i("BSIC", id.bsic); it.i("LAC", id.lac); it.i("CID", id.cid) }
            }
            else -> null
        }
    } catch (_: Exception) { null }

    private fun location(ctx: Context): JSONObject? = try {
        val lm = ctx.getSystemService(LocationManager::class.java)
        val best = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { try { lm.getLastKnownLocation(it) } catch (_: Exception) { null } }
            .maxByOrNull { it.time }
        best?.let {
            JSONObject().put("lat", it.latitude).put("lon", it.longitude)
                .put("accuracy_m", it.accuracy.toDouble()).put("age_s", (System.currentTimeMillis() - it.time) / 1000)
        }
    } catch (_: Exception) { null }
}
