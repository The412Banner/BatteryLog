package com.the412banner.batterylog.apps

import android.content.Context
import com.the412banner.batterylog.Storage
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Per-app recordings, one pair of CSV files per day next to the battery logs:
 * timeline-<day>.csv = one device row per sample (+ the app on screen), apps-<day>.csv = per-app minute totals.
 */
object AppStore {
    const val TIMELINE_HEADER =
        "time_ms,fg,watts,level,charging,cpu_pct,gpu_pct,gpu_mhz,cpu_c,gpu_c,skin_c,ddr_c,batt_c,fg_cpu_pct,fg_rss_kb,fg_gpu_kb"
    const val APPS_HEADER = "minute_ms,pkg,uid,cpu_ms,rss_avg_kb,rss_max_kb,gpu_max_kb,fg_ms,fg_energy_mj,samples"

    private val dayF = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val zone get() = ZoneId.systemDefault()

    fun dir(ctx: Context): File = File(Storage.logDir(ctx).parentFile ?: ctx.filesDir, "apps").apply { mkdirs() }

    private fun day(ms: Long): String = dayF.format(Instant.ofEpochMilli(ms).atZone(zone))

    private fun append(f: File, header: String, rows: List<String>) {
        if (rows.isEmpty()) return
        val fresh = !f.exists()
        f.appendText(buildString {
            if (fresh) append(header).append('\n')
            rows.forEach { append(it).append('\n') }
        })
    }

    fun f(v: Float): String = if (v.isNaN()) "" else "%.2f".format(java.util.Locale.US, v)
    fun d(v: Double): String = if (v.isNaN()) "" else "%.3f".format(java.util.Locale.US, v)

    fun appendTimeline(ctx: Context, t: TRow) {
        append(File(dir(ctx), "timeline-${day(t.time)}.csv"), TIMELINE_HEADER, listOf(t.csv()))
    }

    fun appendMinutes(ctx: Context, rows: List<MRow>) {
        rows.groupBy { day(it.minute) }.forEach { (day, list) ->
            append(File(dir(ctx), "apps-$day.csv"), APPS_HEADER, list.map { it.csv() })
        }
    }

    fun prune(ctx: Context, keepDays: Int) {
        if (keepDays <= 0) return
        val cutoff = LocalDate.now(zone).minusDays(keepDays.toLong())
        dir(ctx).listFiles()?.forEach { f ->
            val d = f.name.substringAfter('-').removeSuffix(".csv")
            val date = runCatching { LocalDate.parse(d, dayF) }.getOrNull() ?: return@forEach
            if (date.isBefore(cutoff)) f.delete()
        }
    }

    private fun filesFor(ctx: Context, prefix: String, from: Long, to: Long): List<File> {
        val first = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
        val last = Instant.ofEpochMilli(to).atZone(zone).toLocalDate()
        val out = ArrayList<File>()
        var d = first
        while (!d.isAfter(last)) {
            File(dir(ctx), "$prefix-${dayF.format(d)}.csv").takeIf { it.exists() }?.let(out::add)
            d = d.plusDays(1)
        }
        return out
    }

    fun readTimeline(ctx: Context, from: Long, to: Long): List<TRow> =
        filesFor(ctx, "timeline", from, to).flatMap { f -> f.useLines { ls -> ls.drop(1).mapNotNull { TRow.parse(it) }.filter { it.time in from..to }.toList() } }

    fun readMinutes(ctx: Context, from: Long, to: Long, pkg: String? = null): List<MRow> =
        filesFor(ctx, "apps", from, to).flatMap { f ->
            f.useLines { ls -> ls.drop(1).mapNotNull { MRow.parse(it) }.filter { it.minute in from..to && (pkg == null || it.pkg == pkg) }.toList() }
        }

    fun hasData(ctx: Context): Boolean = dir(ctx).listFiles()?.any { it.name.startsWith("timeline-") } == true

    /** Totals per app over [from, to]. */
    fun summaries(ctx: Context, from: Long, to: Long): List<AppSummary> {
        val byPkg = HashMap<String, AppSummary.Builder>()
        for (m in readMinutes(ctx, from, to)) byPkg.getOrPut(m.pkg) { AppSummary.Builder(m.pkg, m.uid) }.addMinute(m)
        for (t in readTimeline(ctx, from, to)) if (t.fg.isNotEmpty()) byPkg.getOrPut(t.fg) { AppSummary.Builder(t.fg, -1) }.addFg(t)
        return byPkg.values.map { it.build() }
    }
}

/** One device sample. fg = package on screen ("" = none / screen off). */
data class TRow(
    val time: Long, val fg: String, val watts: Double, val level: Int, val charging: Boolean,
    val cpuPct: Float, val gpuPct: Float, val gpuMhz: Float, val cpuC: Float, val gpuC: Float,
    val skinC: Float, val ddrC: Float, val battC: Float, val fgCpuPct: Float, val fgRssKb: Long, val fgGpuKb: Long,
) {
    /** Power drawn from the battery (W, >= 0); NaN while charging or unknown. */
    val drawW: Double get() = if (watts.isNaN() || charging || watts > 0) Double.NaN else -watts

    fun csv(): String = listOf(
        time.toString(), fg, AppStore.d(watts), level.toString(), if (charging) "1" else "0",
        AppStore.f(cpuPct), AppStore.f(gpuPct), AppStore.f(gpuMhz), AppStore.f(cpuC), AppStore.f(gpuC),
        AppStore.f(skinC), AppStore.f(ddrC), AppStore.f(battC), AppStore.f(fgCpuPct), fgRssKb.toString(), fgGpuKb.toString(),
    ).joinToString(",")

    companion object {
        fun parse(line: String): TRow? {
            val p = line.split(',')
            if (p.size < 16) return null
            fun fl(i: Int) = p[i].toFloatOrNull() ?: Float.NaN
            return TRow(
                p[0].toLongOrNull() ?: return null, p[1], p[2].toDoubleOrNull() ?: Double.NaN, p[3].toIntOrNull() ?: -1,
                p[4] == "1", fl(5), fl(6), fl(7), fl(8), fl(9), fl(10), fl(11), fl(12), fl(13),
                p[14].toLongOrNull() ?: 0, p[15].toLongOrNull() ?: 0,
            )
        }
    }
}

/** Per-app totals for one minute. */
data class MRow(
    val minute: Long, val pkg: String, val uid: Int, val cpuMs: Long, val rssAvgKb: Long, val rssMaxKb: Long,
    val gpuMaxKb: Long, val fgMs: Long, val fgEnergyMj: Double, val samples: Int,
) {
    fun csv(): String = "$minute,$pkg,$uid,$cpuMs,$rssAvgKb,$rssMaxKb,$gpuMaxKb,$fgMs,${AppStore.d(fgEnergyMj)},$samples"

    companion object {
        fun parse(line: String): MRow? {
            val p = line.split(',')
            if (p.size < 10) return null
            return MRow(
                p[0].toLongOrNull() ?: return null, p[1], p[2].toIntOrNull() ?: -1, p[3].toLongOrNull() ?: 0,
                p[4].toLongOrNull() ?: 0, p[5].toLongOrNull() ?: 0, p[6].toLongOrNull() ?: 0, p[7].toLongOrNull() ?: 0,
                p[8].toDoubleOrNull() ?: 0.0, p[9].toIntOrNull() ?: 0,
            )
        }
    }
}

/** Min / average / max of one metric. */
data class Stat(val min: Float, val avg: Float, val max: Float, val n: Int) {
    companion object {
        val NONE = Stat(Float.NaN, Float.NaN, Float.NaN, 0)
        fun of(values: Sequence<Float>): Stat {
            var mn = Float.POSITIVE_INFINITY; var mx = Float.NEGATIVE_INFINITY; var sum = 0.0; var n = 0
            for (v in values) if (!v.isNaN()) { mn = minOf(mn, v); mx = maxOf(mx, v); sum += v; n++ }
            return if (n == 0) NONE else Stat(mn, (sum / n).toFloat(), mx, n)
        }
    }
}

data class AppSummary(
    val pkg: String,
    val uid: Int,
    val fgMs: Long,
    val energyMj: Double,
    val cpuMs: Long,
    val rssAvgKb: Long,
    val rssMaxKb: Long,
    val gpuMaxKb: Long,
    val drawW: Stat,
    val cpuC: Stat,
    val gpuC: Stat,
    val skinC: Stat,
    val battC: Stat,
    val gpuPct: Stat,
    val fgCpuPct: Stat,
) {
    val energyWh: Double get() = energyMj / 3_600_000.0

    class Builder(val pkg: String, var uid: Int) {
        var fgMs = 0L; var energyMj = 0.0; var cpuMs = 0L; var rssSum = 0L; var rssN = 0L; var rssMax = 0L; var gpuMax = 0L
        val fg = ArrayList<TRow>()

        fun addMinute(m: MRow) {
            if (uid < 0) uid = m.uid
            fgMs += m.fgMs; energyMj += m.fgEnergyMj; cpuMs += m.cpuMs
            rssSum += m.rssAvgKb * m.samples; rssN += m.samples
            rssMax = maxOf(rssMax, m.rssMaxKb); gpuMax = maxOf(gpuMax, m.gpuMaxKb)
        }

        fun addFg(t: TRow) { fg += t }

        fun build() = AppSummary(
            pkg, uid, fgMs, energyMj, cpuMs, if (rssN > 0) rssSum / rssN else 0, rssMax, gpuMax,
            Stat.of(fg.asSequence().map { it.drawW.toFloat() }),
            Stat.of(fg.asSequence().map { it.cpuC }), Stat.of(fg.asSequence().map { it.gpuC }),
            Stat.of(fg.asSequence().map { it.skinC }), Stat.of(fg.asSequence().map { it.battC }),
            Stat.of(fg.asSequence().map { it.gpuPct }), Stat.of(fg.asSequence().map { it.fgCpuPct }),
        )
    }
}
