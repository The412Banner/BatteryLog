package com.the412banner.batterylog

import java.util.Calendar
import java.util.TimeZone

/**
 * Long-run averages from the logs in a window: how long a full charge lasts, how long a full charge takes
 * and how the battery is charged. Charging is timed per 10 % step, because it slows down near full.
 */
object Averages {

    const val STEPS = 10
    private const val MIN_STEP_PCT = 2
    private const val MIN_STEPS = 3

    data class Result(
        val batteryMs: Long = 0,
        val chargeMs: Long = 0,
        val days: Int = 0,
        /** Minutes per 1 % while charging, per 10 % step (index 0 = 0-9 %); null where nothing was measured. */
        val chargeMinPerPct: List<Double?> = List(STEPS) { null },
        val drainPctPerH: Double? = null,
        val usedPct: Int = 0,
        val plugIns: Int = 0,
        val plugInPct: Int? = null,
        val unplugPct: Int? = null,
        val longestBatteryMs: Long = 0,
    ) {
        val measuredSteps: Int get() = chargeMinPerPct.count { it != null }

        /** 100 -> 0 % at the average drain. */
        val fullRuntimeMs: Long? get() = drainPctPerH?.takeIf { it > 0 }?.let { (100 / it * 3_600_000).toLong() }

        /** 0 -> 100 %. */
        val fullChargeMs: Long? get() = chargeMs(0, 100)

        /** 20 -> 80 %. */
        val topUpMs: Long? get() = chargeMs(20, 80)

        val usedPctPerDay: Double? get() = if (days > 0 && usedPct > 0) usedPct.toDouble() / days else null
        val chargesPerDay: Double? get() = if (days > 0) plugIns.toDouble() / days else null

        /**
         * Time to charge from [from] to [to] %, adding the measured speed of every 10 % step on the way.
         * A step without data takes the speed of the nearest measured step. Null when fewer than 3 steps were measured.
         */
        fun chargeMs(from: Int, to: Int): Long? {
            if (to <= from || measuredSteps < MIN_STEPS) return null
            var min = 0.0
            for (pct in from.coerceIn(0, 100) until to.coerceIn(0, 100)) min += stepSpeed(pct / STEPS) ?: return null
            return (min * 60_000).toLong()
        }

        private fun stepSpeed(step: Int): Double? {
            chargeMinPerPct[step]?.let { return it }
            for (d in 1 until STEPS) {
                chargeMinPerPct.getOrNull(step - d)?.let { return it }
                chargeMinPerPct.getOrNull(step + d)?.let { return it }
            }
            return null
        }
    }

    fun of(s: SampleSet, zone: TimeZone = TimeZone.getDefault()): Result {
        if (s.size < 2) return Result()
        val stepMs = LongArray(STEPS)
        val stepPct = IntArray(STEPS)
        var used = 0
        var plugIns = 0; var plugInSum = 0
        var unplugs = 0; var unplugSum = 0
        var longest = 0L; var stretch = 0L
        for (i in 1 until s.size) {
            val dt = s.t[i] - s.t[i - 1]
            if (dt <= 0 || dt > Analysis.GAP_MS) { stretch = 0; continue }
            val wasOn = s.onCharger(i - 1)
            val isOn = s.onCharger(i)
            val dc = s.cap[i] - s.cap[i - 1]
            if (!wasOn && !isOn) {
                if (dc < 0) used -= dc
                stretch += dt
                if (stretch > longest) longest = stretch
            } else {
                stretch = 0
            }
            if (!wasOn && isOn) { plugIns++; plugInSum += s.cap[i - 1] }
            if (wasOn && !isOn) { unplugs++; unplugSum += s.cap[i - 1] }
            val st = s.status[i - 1]
            if (wasOn && isOn && s.cap[i - 1] < 100 && st != SampleSet.ST_FULL && st != SampleSet.ST_NOT_CHARGING) {
                val step = s.cap[i - 1] / STEPS
                stepMs[step] += dt
                if (dc > 0) stepPct[step] += dc
            }
        }
        val rates = Analysis.rates(s)
        return Result(
            batteryMs = (rates.batteryHours * 3_600_000).toLong(),
            chargeMs = (rates.chargeHours * 3_600_000).toLong(),
            days = loggedDays(s, zone),
            chargeMinPerPct = List(STEPS) { k ->
                if (stepPct[k] >= MIN_STEP_PCT) stepMs[k] / 60_000.0 / stepPct[k] else null
            },
            drainPctPerH = rates.drainPctPerH,
            usedPct = used,
            plugIns = plugIns,
            plugInPct = if (plugIns > 0) Math.round(plugInSum.toDouble() / plugIns).toInt() else null,
            unplugPct = if (unplugs > 0) Math.round(unplugSum.toDouble() / unplugs).toInt() else null,
            longestBatteryMs = longest,
        )
    }

    /** Calendar days (in [zone]) that have at least one sample. */
    private fun loggedDays(s: SampleSet, zone: TimeZone): Int {
        val cal = Calendar.getInstance(zone)
        var days = 0
        var last = Long.MIN_VALUE
        for (i in 0 until s.size) {
            cal.timeInMillis = s.t[i]
            val key = cal.get(Calendar.YEAR) * 1000L + cal.get(Calendar.DAY_OF_YEAR)
            if (key != last) { days++; last = key }
        }
        return days
    }
}
