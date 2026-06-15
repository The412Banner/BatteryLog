package com.the412banner.batterylog

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.io.File

/**
 * Snapshots the device battery from BatteryManager + the sticky battery intent
 * + world-readable /sys/class/power_supply/battery nodes. No root required.
 */
class BatteryInfo private constructor() {

    var capacityPct = -1
    var status = "—"
    var plugged = "—"
    var health = "—"
    var technology = "—"
    var voltageMv = -1
    var tempC = Double.NaN
    var currentMa = Double.NaN      // instantaneous (negative = discharging)
    var avgCurrentMa = Double.NaN
    var chargeNowMah = -1           // charge_counter
    var chargeFullMah = -1          // learned full capacity
    var chargeFullDesignMah = -1    // design capacity
    var cycleCount = -1
    var sohPct = Double.NaN         // chargeFull / chargeFullDesign

    /** label -> value, for the expanded detail list. */
    fun details(): List<Pair<String, String>> {
        fun v(d: Double, u: String, dec: Int = 0) =
            if (d.isNaN()) "n/a" else "%.${dec}f %s".format(d, u)
        fun i(n: Int, u: String) = if (n < 0) "n/a" else "$n $u"
        val cells = if (voltageMv > 4600) 2 else 1   // Pocket FIT is a 2S pack
        return listOf(
            "Level" to (if (capacityPct < 0) "n/a" else "$capacityPct %"),
            "Status" to status,
            "Power source" to plugged,
            "Health" to health,
            "Technology" to technology,
            "Voltage (pack)" to (if (voltageMv < 0) "n/a" else "%.3f V".format(voltageMv / 1000.0)),
            "Voltage (per cell)" to (if (voltageMv < 0) "n/a" else "%.3f V  (${cells}S)".format(voltageMv / 1000.0 / cells)),
            "Temperature" to v(tempC, "°C", 1),
            "Current (now)" to v(currentMa, "mA", 0),
            "Current (avg)" to v(avgCurrentMa, "mA", 0),
            "Charge now" to i(chargeNowMah, "mAh"),
            "Charge full (learned)" to i(chargeFullMah, "mAh"),
            "Charge full (design)" to i(chargeFullDesignMah, "mAh"),
            "Cycle count" to (if (cycleCount < 0) "n/a" else "$cycleCount"),
            "State of health" to (if (sohPct.isNaN()) "n/a" else "%.1f %%".format(sohPct)),
        )
    }

    companion object {
        private const val SYS = "/sys/class/power_supply/battery"

        private fun sys(node: String): String? =
            runCatching { File("$SYS/$node").readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

        private fun sysInt(node: String): Int? = sys(node)?.toIntOrNull()

        fun read(ctx: Context): BatteryInfo {
            val bi = BatteryInfo()
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val s = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

            bi.capacityPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                .takeIf { it in 0..100 } ?: -1
            bi.status = name(s?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1, STATUS)
            bi.plugged = name(s?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1, PLUGGED)
            bi.health = name(s?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1, HEALTH)
            bi.technology = s?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "—"
            bi.voltageMv = s?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
            (s?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE)
                .let { if (it != Int.MIN_VALUE) bi.tempC = it / 10.0 }

            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                .let { if (it != Int.MIN_VALUE && it != 0) bi.currentMa = it / 1000.0 }
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
                .let { if (it != Int.MIN_VALUE && it != 0) bi.avgCurrentMa = it / 1000.0 }
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                .let { if (it > 0) bi.chargeNowMah = it / 1000 }

            // Learned/design capacity + cycles come from sysfs (µAh).
            sysInt("charge_full")?.let { bi.chargeFullMah = it / 1000 }
            sysInt("charge_full_design")?.let { bi.chargeFullDesignMah = it / 1000 }
            sysInt("cycle_count")?.let { bi.cycleCount = it }
            if (bi.chargeFullMah > 0 && bi.chargeFullDesignMah > 0) {
                bi.sohPct = bi.chargeFullMah.toDouble() / bi.chargeFullDesignMah * 100.0
            }
            return bi
        }

        private val STATUS = mapOf(
            BatteryManager.BATTERY_STATUS_CHARGING to "Charging",
            BatteryManager.BATTERY_STATUS_DISCHARGING to "Discharging",
            BatteryManager.BATTERY_STATUS_NOT_CHARGING to "Not charging",
            BatteryManager.BATTERY_STATUS_FULL to "Full",
        )
        private val PLUGGED = mapOf(
            BatteryManager.BATTERY_PLUGGED_AC to "AC",
            BatteryManager.BATTERY_PLUGGED_USB to "USB",
            BatteryManager.BATTERY_PLUGGED_WIRELESS to "Wireless",
            0 to "Unplugged",
        )
        private val HEALTH = mapOf(
            BatteryManager.BATTERY_HEALTH_GOOD to "Good",
            BatteryManager.BATTERY_HEALTH_OVERHEAT to "Overheat",
            BatteryManager.BATTERY_HEALTH_DEAD to "Dead",
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE to "Over-voltage",
            BatteryManager.BATTERY_HEALTH_COLD to "Cold",
        )
        private fun name(v: Int, m: Map<Int, String>) = m[v] ?: "Unknown"
    }
}
