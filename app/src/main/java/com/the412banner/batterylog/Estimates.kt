package com.the412banner.batterylog

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.io.File
import kotlin.math.abs

/**
 * Runtime / charge-time estimates. Two sources, blended:
 *  - INSTANT: the fuel gauge's own time_to_empty_avg / time_to_full_* (live,
 *    no history needed) read from sysfs (root fallback).
 *  - LEARNED: average %/hr from this app's recorded discharge & charge runs —
 *    these reflect *your* real usage and improve after a few cycles.
 */
object Estimates {

    private const val SYS = "/sys/class/power_supply/battery"

    data class Result(
        val charging: Boolean,
        val capacity: Int,
        val toEmptyMin: Int?,        // instant (fuel gauge)
        val toFullMin: Int?,         // instant (fuel gauge)
        val dischPctPerH: Double?,   // learned
        val chgPctPerH: Double?,     // learned
        val dischRuns: Int,
        val chgRuns: Int
    ) {
        /** Learned full-discharge runtime (100->0), minutes. */
        val learnedFullRuntimeMin: Int? get() =
            dischPctPerH?.takeIf { it > 0 }?.let { (100.0 / it * 60).toInt() }
        /** Learned remaining time at the current level, minutes. */
        val learnedRemainingMin: Int? get() =
            dischPctPerH?.takeIf { it > 0 }?.let { (capacity / it * 60).toInt() }
        /** Learned time to reach 100% from the current level, minutes. */
        val learnedToFullMin: Int? get() =
            chgPctPerH?.takeIf { it > 0 }?.let { ((100 - capacity) / it * 60).toInt() }
    }

    // ---- learned-average cache (parsing logs every tick would be wasteful) ----
    private var cacheAt = 0L
    private var cachedDisch: Double? = null
    private var cachedChg: Double? = null
    private var cachedDischN = 0
    private var cachedChgN = 0

    fun compute(ctx: Context): Result {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val sticky = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val statusInt = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = statusInt == BatteryManager.BATTERY_STATUS_CHARGING ||
                       statusInt == BatteryManager.BATTERY_STATUS_FULL
        val cap = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)

        val (toEmpty, toFull) = readTimes()
        refreshLearned(ctx)

        return Result(
            charging, cap, toEmpty, toFull,
            cachedDisch, cachedChg, cachedDischN, cachedChgN
        )
    }

    /** Fuel-gauge instant estimates, seconds -> minutes. Sysfs first, one root batch as fallback. */
    private fun readTimes(): Pair<Int?, Int?> {
        var empty = readNodeDirect("time_to_empty_avg")
        var full = readNodeDirect("time_to_full_now")?.takeIf { it > 0 }
            ?: readNodeDirect("time_to_full_avg")

        if ((empty == null || full == null) && RootHelper.hasRoot()) {
            val out = RootHelper.run(
                "for n in time_to_empty_avg time_to_full_now time_to_full_avg; do " +
                "echo \"\$n=\$(cat $SYS/\$n 2>/dev/null)\"; done"
            ).out
            fun grab(n: String) = Regex("$n=(-?\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull()
            empty = empty ?: grab("time_to_empty_avg")
            full = full ?: (grab("time_to_full_now")?.takeIf { it > 0 } ?: grab("time_to_full_avg"))
        }
        return secToMin(empty) to secToMin(full)
    }

    private fun readNodeDirect(n: String): Int? =
        runCatching { File("$SYS/$n").readText().trim().toIntOrNull() }.getOrNull()

    // Reject sentinels (-1, 0) and absurd values (> 48 h).
    private fun secToMin(s: Int?): Int? = s?.takeIf { it in 1..(48 * 3600) }?.div(60)

    private fun refreshLearned(ctx: Context) {
        if (cacheAt != 0L && System.currentTimeMillis() - cacheAt < 30_000) return
        val d = ArrayList<Double>()
        val c = ArrayList<Double>()
        LogStore.logs(ctx).take(25).forEach { f ->
            val rows = LogStore.parse(f)
            if (rows.size < 2) return@forEach
            val a = rows.first(); val b = rows.last()
            val durH = (b.epoch - a.epoch) / 3_600_000.0
            val dCap = b.cap - a.cap
            if (durH < 0.05) return@forEach          // < 3 min — too short to trust
            val rate = abs(dCap) / durH
            if (abs(dCap) < 5 || rate <= 0 || rate > 200) return@forEach
            if (dCap < 0) d += rate else if (dCap > 0) c += rate
        }
        cachedDisch = d.takeIf { it.isNotEmpty() }?.average()
        cachedChg = c.takeIf { it.isNotEmpty() }?.average()
        cachedDischN = d.size
        cachedChgN = c.size
        cacheAt = System.currentTimeMillis()
    }

    /** "2h 28m" / "47m". */
    fun fmtMin(min: Int?): String {
        if (min == null || min <= 0) return "—"
        val h = min / 60; val m = min % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
}
