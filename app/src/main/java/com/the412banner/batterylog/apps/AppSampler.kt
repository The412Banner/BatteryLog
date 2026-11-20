package com.the412banner.batterylog.apps

import android.content.Context
import com.the412banner.batterylog.BatteryInfo

/** Per-app share of one sample interval. */
data class AppTick(val pkg: String, val uid: Int, val cpuMs: Long, val rssKb: Long, val gpuKb: Long)

/** One device-wide reading plus the per-app ticks since the previous one. */
data class DeviceTick(
    val time: Long,
    val dtMs: Long,
    val watts: Double,          // negative = drawing from the battery
    val level: Int,
    val charging: Boolean,
    val cpuPct: Float,
    val gpuPct: Float,
    val gpuMhz: Float,
    val cpuC: Float,
    val gpuC: Float,
    val skinC: Float,
    val ddrC: Float,
    val battC: Float,
    val apps: Map<String, AppTick>,
)

/** Collects one sample through a root shell: processes, CPU, GPU, temperatures; battery from BatteryManager. */
class AppSampler(private val ctx: Context) {
    private val shell = RootShell()
    private var prevCpu = HashMap<Int, Long>()        // pid -> cpu ms
    private var prevStat: Pair<Long, Long>? = null
    private var prevTime = 0L
    private var zoneGroups: Map<String, String>? = null // thermal_zoneN -> group

    private val SCRIPT = """
        echo @PS; ps -A -o PID,UID,RSS,TIME+,NAME
        echo @STAT; head -1 /proc/stat
        echo @GPUBUSY; cat /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage 2>/dev/null
        echo @GPUCLK; cat /sys/class/kgsl/kgsl-3d0/gpuclk 2>/dev/null
        echo @GPUMEM; grep -H . /sys/class/kgsl/kgsl/proc/*/kernel 2>/dev/null
        echo @TEMP; grep -H . /sys/class/thermal/thermal_zone*/temp 2>/dev/null
        echo @DONE
    """.trimIndent()

    fun close() = shell.close()

    /** Null when root is not available. */
    fun sample(): DeviceTick? {
        if (zoneGroups == null) {
            val types = shell.run("grep -H . /sys/class/thermal/thermal_zone*/type 2>/dev/null") ?: return null
            zoneGroups = AppParse.grepH(types).mapNotNull { (zone, type) -> AppParse.thermalGroup(type)?.let { zone to it } }.toMap()
        }
        val out = shell.run(SCRIPT) ?: return null
        val sec = sections(out)
        val now = System.currentTimeMillis()
        val dt = if (prevTime == 0L) 0L else now - prevTime
        prevTime = now

        // Device CPU load.
        val stat = sec["STAT"]?.lineSequence()?.firstOrNull()?.let { AppParse.procStat(it) }
        val cpuPct = if (stat != null && prevStat != null) {
            val busy = stat.first - prevStat!!.first
            val total = stat.second - prevStat!!.second
            if (total > 0) busy * 100f / total else Float.NaN
        } else Float.NaN
        prevStat = stat

        // GPU.
        val gpuPct = sec["GPUBUSY"]?.trim()?.removeSuffix("%")?.trim()?.toFloatOrNull() ?: Float.NaN
        val gpuMhz = sec["GPUCLK"]?.trim()?.toLongOrNull()?.let { it / 1_000_000f } ?: Float.NaN
        val gpuMem = AppParse.grepH(sec["GPUMEM"].orEmpty()).mapNotNull { (pid, v) ->
            pid.toIntOrNull()?.let { p -> v.toLongOrNull()?.let { p to it / 1024 } }
        }.toMap()

        // Temperatures by group (hottest sensor of each group).
        val temps = HashMap<String, Float>()
        val groups = zoneGroups.orEmpty()
        for ((zone, raw) in AppParse.grepH(sec["TEMP"].orEmpty())) {
            val g = groups[zone] ?: continue
            val c = AppParse.milliC(raw)
            if (!c.isNaN()) temps[g] = maxOf(temps[g] ?: c, c)
        }

        // Per-app CPU (delta), memory and GPU memory, grouped by package.
        val procs = AppParse.ps(sec["PS"].orEmpty())
        val nextCpu = HashMap<Int, Long>(procs.size)
        val acc = HashMap<String, LongArray>() // cpuMs, rssKb, gpuKb, uid
        for (p in procs) {
            nextCpu[p.pid] = p.cpuMs
            if (!p.isApp) continue
            val prev = prevCpu[p.pid]
            val d = if (prev != null && p.cpuMs >= prev) p.cpuMs - prev else 0L
            val a = acc.getOrPut(p.pkg) { longArrayOf(0, 0, 0, p.uid.toLong()) }
            a[0] += d
            a[1] += p.rssKb
            a[2] += gpuMem[p.pid] ?: 0L
        }
        prevCpu = nextCpu
        val apps = acc.mapValues { (pkg, a) -> AppTick(pkg, a[3].toInt(), a[0], a[1], a[2]) }

        val b = BatteryInfo.read(ctx)
        return DeviceTick(
            time = now, dtMs = dt, watts = b.watts, level = b.capacityPct, charging = b.onCharger,
            cpuPct = cpuPct, gpuPct = gpuPct, gpuMhz = gpuMhz,
            cpuC = temps["cpu"] ?: Float.NaN, gpuC = temps["gpu"] ?: Float.NaN, skinC = temps["skin"] ?: Float.NaN,
            ddrC = temps["ddr"] ?: Float.NaN,
            battC = b.tempC.toFloat().takeIf { !it.isNaN() } ?: (temps["battery"] ?: Float.NaN),
            apps = apps,
        )
    }

    /** Android's own estimate per app since the last full charge (mAh), via root. */
    fun androidEstimates(): Map<Int, Double> =
        shell.run("dumpsys batterystats --checkin 2>/dev/null | grep ',pwi,uid,'")?.let { AppParse.batteryStatsUid(it) } ?: emptyMap()

    private fun sections(out: String): Map<String, String> {
        val m = HashMap<String, String>()
        var key: String? = null
        val sb = StringBuilder()
        for (line in out.lineSequence()) {
            if (line.startsWith("@")) {
                key?.let { m[it] = sb.toString() }
                sb.setLength(0)
                key = line.substring(1).trim()
            } else sb.append(line).append('\n')
        }
        key?.let { m[it] = sb.toString() }
        return m
    }
}
