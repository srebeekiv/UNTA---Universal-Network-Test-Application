package org.unta.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.telephony.CellSignalStrengthLte
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
            o.put("standard", when (wi.wifiStandard) {
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
            try { o.put("network_type", netType(tm.dataNetworkType)) } catch (_: SecurityException) {}
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
                    else -> if (!o.has("signal_dbm")) { o.put("signal_dbm", cs.dbm); o.put("level", cs.level) }
                }
            }
        } catch (e: Exception) { o.put("error", e.message ?: e.javaClass.simpleName) }
        return o
    }

    private fun netType(t: Int) = when (t) {
        20 -> "5G NR"; 13 -> "LTE"; 19 -> "LTE-CA"; 15 -> "HSPA+"; 10 -> "HSPA"; 8 -> "HSDPA"; 9 -> "HSUPA"
        3 -> "UMTS"; 2 -> "EDGE"; 1 -> "GPRS"; 16 -> "GSM"; 0 -> "unknown"; else -> "type $t"
    }

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
