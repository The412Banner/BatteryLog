package com.the412banner.batterylog

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Reads a CSV produced by [BatteryLogService] and builds a human-readable
 * full-diagnostic report: usable capacity / SoH, voltage sag, current, temps,
 * discharge rate, and an anomalies section (early cutoff / sag / thermal).
 */
object ReportGenerator {

    // AYANEO Pocket FIT 2S pack reference numbers (see memory diag 2026-06-14).
    private const val DESIGN_MAH = 4139.0
    private const val REPORTED_FULL_MAH = 2430.0

    private data class Row(
        val epoch: Long, val uptime: Long, val cap: Int, val status: String,
        val plugged: String, val vMv: Int, val iUa: Int, val chgUah: Int,
        val tempDecideg: Int, val health: String
    )

    fun latestLog(ctx: Context): File? =
        BatteryLogService.logDir(ctx).listFiles { f -> f.name.endsWith(".csv") }
            ?.maxByOrNull { it.lastModified() }

    /** Generates the report for [csv], writes it next to the log, returns the text. */
    fun generate(ctx: Context, csv: File): String {
        val rows = parse(csv)
        val sb = StringBuilder()
        fun line(s: String = "") = sb.append(s).append('\n')

        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        line("==================================================")
        line(" BatteryLog — Full Diagnostic Report")
        line("==================================================")
        line("Generated : $now")
        line("Device    : ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
        line("Log file  : ${csv.name}")
        line("Samples   : ${rows.size}")
        line()

        if (rows.size < 2) {
            line("Not enough samples to analyse (need at least 2).")
            return write(csv, sb.toString())
        }

        val first = rows.first()
        val last = rows.last()
        val durMs = last.epoch - first.epoch
        val durH = durMs / 3_600_000.0
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        line("---- RUN ----")
        line("Start     : ${fmt.format(Date(first.epoch))}  @ ${first.cap}%")
        line("End       : ${fmt.format(Date(last.epoch))}  @ ${last.cap}%")
        line("Duration  : ${fmtDur(durMs)}")
        line()

        // ---- Capacity / State of Health ----
        line("---- CAPACITY / STATE OF HEALTH ----")
        val capDrop = first.cap - last.cap
        val validChg = rows.filter { it.chgUah > 0 }
        if (validChg.size >= 2 && capDrop > 0) {
            val chgStart = validChg.first().chgUah
            val chgEnd = validChg.last().chgUah
            val usedUah = abs(chgStart - chgEnd)
            val usedMah = usedUah / 1000.0
            // Extrapolate the charge consumed over capDrop% to a full 0..100%.
            val fullMah = usedMah / capDrop * 100.0
            line("charge_counter : ${chgStart / 1000} mAh -> ${chgEnd / 1000} mAh")
            line("Consumed       : ${"%.0f".format(usedMah)} mAh over $capDrop% drop")
            line("Extrapolated full (0->100%): ${"%.0f".format(fullMah)} mAh")
            line("  vs design ($DESIGN_MAH mAh)        : SoH ${"%.1f".format(fullMah / DESIGN_MAH * 100)}%")
            line("  vs reported-full ($REPORTED_FULL_MAH mAh) : ${"%.1f".format(fullMah / REPORTED_FULL_MAH * 100)}%")
            line("(Most accurate after a full 100%->0% run.)")
        } else {
            line("charge_counter unavailable or no capacity drop — cannot compute SoH.")
        }
        line()

        // ---- Voltage ----
        val volts = rows.filter { it.vMv > 0 }
        if (volts.isNotEmpty()) {
            val minV = volts.minBy { it.vMv }
            val maxV = volts.maxBy { it.vMv }
            line("---- VOLTAGE ----")
            line("Start : ${"%.3f".format(first.vMv / 1000.0)} V")
            line("End   : ${"%.3f".format(last.vMv / 1000.0)} V")
            line("Min   : ${"%.3f".format(minV.vMv / 1000.0)} V  @ ${minV.cap}%  (${fmt.format(Date(minV.epoch))})")
            line("Max   : ${"%.3f".format(maxV.vMv / 1000.0)} V  @ ${maxV.cap}%")
            line()
        }

        // ---- Current ----
        // Discharge current is reported negative on most devices; report magnitudes.
        val curr = rows.filter { it.iUa != 0 }
        if (curr.isNotEmpty()) {
            val avgMa = curr.map { abs(it.iUa) / 1000.0 }.average()
            val peak = curr.maxBy { abs(it.iUa) }
            line("---- CURRENT (discharge) ----")
            line("Average : ${"%.0f".format(avgMa)} mA")
            line("Peak    : ${"%.0f".format(abs(peak.iUa) / 1000.0)} mA  @ ${peak.cap}%")
            line()
        }

        // ---- Temperature ----
        val temps = rows.filter { it.tempDecideg > 0 }
        if (temps.isNotEmpty()) {
            val minT = temps.minOf { it.tempDecideg } / 10.0
            val maxT = temps.maxOf { it.tempDecideg } / 10.0
            val avgT = temps.map { it.tempDecideg / 10.0 }.average()
            line("---- TEMPERATURE ----")
            line("Min ${"%.1f".format(minT)}°C   Max ${"%.1f".format(maxT)}°C   Avg ${"%.1f".format(avgT)}°C")
            line()
        }

        // ---- Discharge rate ----
        if (capDrop > 0 && durH > 0) {
            val pctPerH = capDrop / durH
            line("---- RATE ----")
            line("Discharge : ${"%.1f".format(pctPerH)} %/hr")
            if (pctPerH > 0) line("Projected 100%->0% runtime: ${"%.1f".format(100.0 / pctPerH)} hr")
            line()
        }

        // ---- Per-10% voltage curve ----
        line("---- VOLTAGE CURVE (per 10%) ----")
        for (bucket in 100 downTo 0 step 10) {
            val r = rows.filter { it.cap == bucket && it.vMv > 0 }.minByOrNull { it.epoch }
            if (r != null) line("  ${"%3d".format(bucket)}% : ${"%.3f".format(r.vMv / 1000.0)} V")
        }
        line()

        // ---- Anomalies ----
        line("---- ANOMALIES ----")
        var flagged = false
        if (last.cap > 3 && last.status != "charging") {
            line("[!] EARLY CUTOFF: monitoring ended at ${last.cap}% (not ~0%). " +
                 "Consistent with a PMIC shutdown,battery cutoff before true empty.")
            flagged = true
        }
        volts.minByOrNull { it.vMv }?.let { mv ->
            // 2S pack: ~6.0V total (~3.0V/cell) is a plausible UVLO floor.
            if (mv.vMv in 1..6200 && mv.cap > 5) {
                line("[!] VOLTAGE SAG: dropped to ${"%.3f".format(mv.vMv / 1000.0)} V at ${mv.cap}% — " +
                     "early sag toward UVLO under load.")
                flagged = true
            }
        }
        if (temps.any { it.tempDecideg / 10.0 >= 48 }) {
            line("[!] HIGH TEMP: battery reached >=48°C during the run.")
            flagged = true
        }
        if (!flagged) line("None detected.")
        line()
        line("Raw CSV: ${csv.absolutePath}")

        return write(csv, sb.toString())
    }

    private fun write(csv: File, text: String): String {
        val out = File(csv.parentFile, csv.nameWithoutExtension + "_report.txt")
        runCatching { out.writeText(text) }
        return text
    }

    private fun parse(csv: File): List<Row> =
        csv.readLines().drop(1).mapNotNull { ln ->
            val p = ln.split(",")
            if (p.size < 12) return@mapNotNull null
            runCatching {
                Row(
                    epoch = p[0].toLong(), uptime = p[2].toLong(), cap = p[3].toInt(),
                    status = p[4], plugged = p[5], vMv = p[6].toInt(), iUa = p[7].toInt(),
                    chgUah = p[8].toInt(), tempDecideg = p[10].toInt(), health = p[11]
                )
            }.getOrNull()
        }

    private fun fmtDur(ms: Long): String {
        val s = ms / 1000
        return "%dh %02dm %02ds".format(s / 3600, (s % 3600) / 60, s % 60)
    }
}
