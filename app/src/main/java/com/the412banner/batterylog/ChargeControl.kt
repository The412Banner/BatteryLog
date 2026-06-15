package com.the412banner.batterylog

import java.io.File

/**
 * Kernel charge-control interface (Qualcomm power_supply nodes). The app can
 * READ these directly (they're world-readable) but WRITES require root.
 *
 *  - charge_control_limit / _max : charge-current throttle level. 0 = unrestricted
 *    (full speed); higher = lower current (slower, cooler charging).
 *  - charge_control_start/end_threshold : stop-charging-at-% for longevity.
 *
 * NB: enforcement is up to the charger firmware — some devices expose the nodes
 * but ignore them. Every write is read back so the UI can confirm it landed.
 */
object ChargeControl {

    private const val SYS = "/sys/class/power_supply/battery"

    data class State(
        val supported: Boolean,
        val speedLevel: Int?,
        val speedMax: Int?,
        val startThreshold: Int?,
        val endThreshold: Int?
    )

    fun read(): State {
        val max = readInt("charge_control_limit_max")
        val lvl = readInt("charge_control_limit")
        val start = readInt("charge_control_start_threshold")
        val end = readInt("charge_control_end_threshold")
        return State(
            supported = max != null || end != null,
            speedLevel = lvl, speedMax = max,
            startThreshold = start, endThreshold = end
        )
    }

    /** Set the charge-current throttle level (0..max). Returns true if read-back matches. */
    fun setSpeed(level: Int): Boolean = writeVerify("charge_control_limit", level)

    /**
     * Set the stop-charging threshold (%). Also nudges the start threshold a few
     * points below so the charger has hysteresis. endPct=100 effectively = off.
     * Returns true if the end threshold read back as requested.
     */
    fun setLimit(endPct: Int): Boolean {
        val start = (endPct - 5).coerceIn(0, 100)
        writeVerify("charge_control_start_threshold", start)
        return writeVerify("charge_control_end_threshold", endPct)
    }

    private fun readInt(n: String): Int? {
        runCatching { File("$SYS/$n").readText().trim().toIntOrNull() }.getOrNull()?.let { return it }
        if (!RootHelper.hasRoot()) return null
        return RootHelper.run("cat $SYS/$n 2>/dev/null").out.trim().toIntOrNull()
    }

    private fun writeVerify(node: String, value: Int): Boolean {
        if (!RootHelper.hasRoot()) return false
        RootHelper.run("echo $value > $SYS/$node 2>/dev/null")
        return readInt(node) == value
    }
}
