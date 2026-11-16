package com.the412banner.batterylog

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/** Live battery snapshot from BatteryManager + the sticky ACTION_BATTERY_CHANGED intent. No root needed. */
data class BatteryInfo(
    val time: Long = System.currentTimeMillis(),
    val capacityPct: Int = -1,
    val statusCode: Int = -1,
    val pluggedCode: Int = -1,
    val healthCode: Int = -1,
    val technology: String = "",
    val voltageMv: Int = -1,
    val tempDeci: Int = Int.MIN_VALUE,
    /** Instant current in mA; negative = drawing from the battery. NaN when the device doesn't report it. */
    val currentMa: Double = Double.NaN,
    val avgCurrentMa: Double = Double.NaN,
    val chargeNowMah: Int = -1,
) {
    val status: String get() = STATUS[statusCode] ?: "Unknown"
    val plugged: String get() = PLUGGED[pluggedCode] ?: "Unknown"
    val health: String get() = HEALTH[healthCode] ?: "Unknown"

    val onCharger: Boolean get() =
        statusCode == BatteryManager.BATTERY_STATUS_CHARGING || statusCode == BatteryManager.BATTERY_STATUS_FULL ||
            (pluggedCode > 0)

    val isCharging: Boolean get() = statusCode == BatteryManager.BATTERY_STATUS_CHARGING

    val tempC: Double get() = if (tempDeci == Int.MIN_VALUE) Double.NaN else tempDeci / 10.0

    /** Watts; negative = drawing from the battery. */
    val watts: Double get() =
        if (voltageMv <= 0 || currentMa.isNaN()) Double.NaN else voltageMv / 1000.0 * currentMa / 1000.0

    companion object {
        /** Current sign convention: some kernels report discharge as positive. Learned once per process. */
        @Volatile private var flipSign: Boolean? = null

        fun read(ctx: Context): BatteryInfo {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val s = runCatching { ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
            val status = s?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val plugged = s?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
            var cap = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (cap !in 0..100) {
                val level = s?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = s?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                cap = if (level >= 0 && scale > 0) level * 100 / scale else -1
            }
            var now = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                .let { if (it == Int.MIN_VALUE || it == 0) Double.NaN else it / 1000.0 }
            var avg = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
                .let { if (it == Int.MIN_VALUE || it == 0) Double.NaN else it / 1000.0 }
            if (status == BatteryManager.BATTERY_STATUS_DISCHARGING && !now.isNaN() && flipSign == null) {
                flipSign = now > 0
            }
            if (flipSign == true) { now = -now; avg = -avg }
            return BatteryInfo(
                time = System.currentTimeMillis(),
                capacityPct = cap,
                statusCode = status,
                pluggedCode = plugged,
                healthCode = s?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1,
                technology = s?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY).orEmpty(),
                voltageMv = s?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1,
                tempDeci = s?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE,
                currentMa = now,
                avgCurrentMa = avg,
                chargeNowMah = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).let { if (it > 0) it / 1000 else -1 },
            )
        }

        private val STATUS = mapOf(
            BatteryManager.BATTERY_STATUS_CHARGING to "Charging",
            BatteryManager.BATTERY_STATUS_DISCHARGING to "Discharging",
            BatteryManager.BATTERY_STATUS_NOT_CHARGING to "Not charging",
            BatteryManager.BATTERY_STATUS_FULL to "Full",
        )
        private val PLUGGED = mapOf(
            BatteryManager.BATTERY_PLUGGED_AC to "AC charger",
            BatteryManager.BATTERY_PLUGGED_USB to "USB",
            BatteryManager.BATTERY_PLUGGED_WIRELESS to "Wireless",
            BatteryManager.BATTERY_PLUGGED_DOCK to "Dock",
            0 to "Battery",
        )
        private val HEALTH = mapOf(
            BatteryManager.BATTERY_HEALTH_GOOD to "Good",
            BatteryManager.BATTERY_HEALTH_OVERHEAT to "Overheat",
            BatteryManager.BATTERY_HEALTH_DEAD to "Dead",
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE to "Over-voltage",
            BatteryManager.BATTERY_HEALTH_COLD to "Cold",
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE to "Failure",
        )
    }
}
