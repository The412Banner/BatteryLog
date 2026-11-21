package com.the412banner.batterylog

/**
 * Android defines BatteryManager current in µA and the charge counter in µAh, but some phones (Honor, for
 * example) report mA and mAh. A charge counter that works out to a full battery under 100 mAh can only be mAh.
 */
object BatteryUnits {
    fun isMilli(chargeCounter: Int, capacityPct: Int): Boolean =
        chargeCounter > 0 && capacityPct in 1..100 && chargeCounter.toLong() * 100 / capacityPct < 100_000

    /** Current in µA whatever the device reports. */
    fun currentUa(raw: Int, milli: Boolean): Int =
        if (milli && raw != Int.MIN_VALUE) (raw.toLong() * 1000).coerceIn(Int.MIN_VALUE + 1L, Int.MAX_VALUE.toLong()).toInt() else raw

    /** Charge counter in µAh whatever the device reports. */
    fun chargeUah(raw: Int, milli: Boolean): Int =
        if (milli && raw > 0) (raw.toLong() * 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else raw
}
