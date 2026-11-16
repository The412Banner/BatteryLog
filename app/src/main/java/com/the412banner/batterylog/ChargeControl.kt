package com.the412banner.batterylog

import android.system.Os
import java.io.File

/**
 * Kernel charge-control nodes in /sys/class/power_supply/battery. Readable without root; writes need root.
 *
 *  - charge_control_limit / _max: either a throttle LEVEL (0 = full speed, higher = slower; small max like 11)
 *    or a CURRENT in µA (max = full current, e.g. 8400000 on the Pocket FIT Elite). Never write 0 to a
 *    current node: that would mean "charge at 0 A".
 *  - charge_control_start/end_threshold: stop charging at a level for battery longevity.
 *
 * A node without any write bit (mode 0444) has no kernel setter, so the app shows it read-only.
 * Every write is read back to confirm it stuck.
 */
object ChargeControl {

    private const val SYS = "/sys/class/power_supply/battery"

    enum class SpeedKind { None, Level, Current }

    data class State(
        val speedKind: SpeedKind,
        val speedValue: Int?,
        val speedMax: Int?,
        val speedWritable: Boolean,
        val startThreshold: Int?,
        val endThreshold: Int?,
        val limitWritable: Boolean,
    ) {
        val supported: Boolean get() = speedKind != SpeedKind.None || endThreshold != null
        /** Lowest current the UI offers on a current node: 10 % of max, at least 500 mA. */
        val minCurrentUa: Int get() = ((speedMax ?: 0) / 10).coerceAtLeast(500_000)
    }

    fun read(): State {
        val max = readInt("charge_control_limit_max")
        val lvl = readInt("charge_control_limit")
        val start = readInt("charge_control_start_threshold")
        val end = readInt("charge_control_end_threshold")
        val kind = when {
            max == null || lvl == null -> SpeedKind.None
            max > 1_000 -> SpeedKind.Current
            else -> SpeedKind.Level
        }
        return State(
            speedKind = kind,
            speedValue = lvl,
            speedMax = max,
            speedWritable = kind != SpeedKind.None && hasWriteBit("charge_control_limit"),
            startThreshold = start,
            endThreshold = end,
            limitWritable = end != null && hasWriteBit("charge_control_end_threshold"),
        )
    }

    /** Level node: 0..max. Current node: µA, clamped to [State.minCurrentUa]..max. */
    fun setSpeed(state: State, value: Int): Boolean {
        val max = state.speedMax ?: return false
        val v = when (state.speedKind) {
            SpeedKind.Level -> value.coerceIn(0, max)
            SpeedKind.Current -> value.coerceIn(state.minCurrentUa, max)
            SpeedKind.None -> return false
        }
        return writeVerify("charge_control_limit", v)
    }

    /** Stop charging at [endPct] (100 = off). The start threshold goes 5 points lower for hysteresis. */
    fun setLimit(endPct: Int): Boolean {
        val end = endPct.coerceIn(50, 100)
        val start = (end - 5).coerceIn(0, 100)
        // Order matters on some kernels: start must stay below end.
        return if (end >= (readInt("charge_control_end_threshold") ?: 100)) {
            val ok = writeVerify("charge_control_end_threshold", end)
            writeVerify("charge_control_start_threshold", start)
            ok
        } else {
            writeVerify("charge_control_start_threshold", start)
            writeVerify("charge_control_end_threshold", end)
        }
    }

    private fun hasWriteBit(n: String): Boolean =
        runCatching { (Os.stat("$SYS/$n").st_mode and 0b010_010_010) != 0 }.getOrDefault(false)

    private fun readInt(n: String): Int? {
        runCatching { File("$SYS/$n").readText().trim().toIntOrNull() }.getOrNull()?.let { return it }
        if (!RootHelper.knownRoot()) return null
        return RootHelper.run("cat $SYS/$n 2>/dev/null").out.trim().toIntOrNull()
    }

    private fun writeVerify(node: String, value: Int): Boolean {
        if (!RootHelper.rootCached()) return false
        RootHelper.run("echo $value > $SYS/$node 2>/dev/null")
        return readInt(node) == value
    }
}
