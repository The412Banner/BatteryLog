package com.the412banner.batterylog

import android.os.Build

/** Plain-text version of [Analysis.Report] for sharing (also saved next to the CSV as *_report.txt). */
object ReportText {
    fun render(r: Analysis.Report, unit: TempUnit): String = buildString {
        fun line(s: String = "") = append(s).append('\n')
        fun row(k: String, v: String) = line(k.padEnd(24) + v)
        line("BatteryLog report")
        line("=================")
        row("Device", "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
        row("Log", r.info.name)
        row("From", Fmt.dateTime(r.info.start))
        row("To", Fmt.dateTime(r.info.end))
        row("Duration", Fmt.duration(r.durationMs))
        row("Samples", "${r.info.count}")
        row("Level", "${r.startCap} % -> ${r.endCap} %  (used ${r.drained} %, charged ${r.charged} %)")
        line()
        line("Capacity")
        row("  Used on battery", r.usedMah?.let { "%.0f mAh".format(it) } ?: "-")
        row("  Measured full", r.fullMah?.let { "%.0f mAh".format(it) } ?: "-")
        row("  Design", r.designMah?.let { "$it mAh" } ?: "unknown")
        row("  Fuel gauge full", r.kernelFullMah?.let { "$it mAh" } ?: "unknown")
        r.sohPct?.let { row("  Health (this run)", "%.1f %%".format(it)) }
        line()
        line("Voltage (${r.cells}S)")
        row("  Start -> end", "${r.vStart?.let { Fmt.volts(it) } ?: "-"} -> ${r.vEnd?.let { Fmt.volts(it) } ?: "-"}")
        row("  Lowest", r.vMin?.let { "${Fmt.volts(it)} at ${r.vMinAtCap} % (${r.vMinAt?.let(Fmt::time) ?: ""})" } ?: "-")
        row("  Highest", r.vMax?.let { Fmt.volts(it) } ?: "-")
        if (r.curve.isNotEmpty()) {
            line("  Per 10 %:")
            r.curve.forEach { (p, mv) -> line("    %3d %% : %s".format(p, Fmt.volts(mv))) }
        }
        line()
        line("Power / current")
        row("  Average draw", r.avgDrainW?.let { Fmt.watts(it) } ?: "-")
        row("  Peak draw", r.peakDrainW?.let { Fmt.watts(it) } ?: "-")
        row("  Average current", r.avgDrainMa?.let { Fmt.ma(it) } ?: "-")
        row("  Peak current", r.peakDrainMa?.let { "${Fmt.ma(it)} at ${r.peakDrainAtCap} %" } ?: "-")
        r.avgChargeW?.let { row("  Average charging", Fmt.watts(it)) }
        r.peakChargeW?.let { row("  Peak charging", Fmt.watts(it)) }
        line()
        line("Speed")
        row("  Drain", r.drainPctPerH?.let { "%.1f %%/h".format(it) } ?: "-")
        r.drainPctPerH?.takeIf { it > 0 }?.let { row("  Full charge lasts", Fmt.duration((100 / it * 3_600_000).toLong())) }
        row("  Charging", r.chargePctPerH?.let { "%.1f %%/h".format(it) } ?: "-")
        line()
        line("Temperature")
        row("  Low / avg / high", listOf(r.tMin, r.tAvg, r.tMax).joinToString(" / ") { t -> t?.let { Fmt.temp(it.toDouble(), unit) } ?: "-" })
        line()
        line("Findings")
        if (r.findings.isEmpty()) line("  Nothing unusual.")
        r.findings.forEach { f -> line("  [!] ${f.title}: ${f.detail}") }
        line()
        line("Raw CSV: ${r.info.path}")
    }
}
