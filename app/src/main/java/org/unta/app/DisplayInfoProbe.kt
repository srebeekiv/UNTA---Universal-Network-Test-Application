package org.unta.app

import android.content.Context
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Как Android сам называет текущую сеть в строке состояния (LTE_CA, LTE_ADVANCED_PRO, NR_NSA...). Android 12+. */
@android.annotation.TargetApi(31)
object DisplayInfoProbe {
    fun overrideType(ctx: Context): Int? {
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        val latch = CountDownLatch(1)
        var res: Int? = null
        val cb = object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
            override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
                res = telephonyDisplayInfo.overrideNetworkType
                latch.countDown()
            }
        }
        val ex = Executors.newSingleThreadExecutor()
        try {
            tm.registerTelephonyCallback(ex, cb)
            latch.await(1500, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
        } finally {
            try { tm.unregisterTelephonyCallback(cb) } catch (_: Exception) {}
            ex.shutdown()
        }
        return res
    }
}
