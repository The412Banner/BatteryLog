package com.the412banner.batterylog.apps

import android.content.Context
import com.the412banner.batterylog.BatteryInfo
import com.the412banner.batterylog.BatteryLogApp
import com.the412banner.batterylog.RootHelper

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

/**
 * Collects one sample through a root shell, or Shizuku when there is no root: processes, CPU, GPU,
 * temperatures; battery from BatteryManager.
 */
class AppSampler(private val ctx: Context) {
    private var shell: PrivShell? = null
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

    /** "root" or "Shizuku" while a shell is in use, else null. */
    val access: String? get() = shell?.label

    fun close() { shell?.close(); shell = null }

    private var pickedForShizuku: Boolean? = null

    /** Root first (rooted devices keep working as before), then Shizuku; only Shizuku when the setting asks for it. */
    private fun shell(): PrivShell? {
        val onlyShizuku = BatteryLogApp.get(ctx).settings.current().perAppShizuku
        if (pickedForShizuku != null && pickedForShizuku != onlyShizuku) close()
        shell?.let { return it }
        val shizukuReady = ShizukuAccess.refresh() == ShizukuAccess.State.Ready
        val s: PrivShell? = when {
            onlyShizuku -> if (shizukuReady) ShizukuShell(ctx) else null
            RootHelper.rootCached() -> RootShell()
            shizukuReady -> ShizukuShell(ctx)
            else -> null
        }
        shell = s
        pickedForShizuku = onlyShizuku
        zoneGroups = null
        return s
    }

    /** Runs [script] in the current shell; drops the shell on failure so the next call picks again. */
    private fun run(script: String): String? {
        val sh = shell() ?: return null
        val out = sh.run(script)
        if (out == null) close()
        return out
    }

    private var fpsPkg = ""
    private var fpsLayer: String? = null
    private var fpsPicked = 0L

    /** Frames per second of [pkg]'s window (SurfaceFlinger frame times); NaN when unknown. */
    fun fps(pkg: String): Float {
        if (pkg.isEmpty()) return Float.NaN
        val now = System.currentTimeMillis()
        if (pkg != fpsPkg || fpsLayer == null) {
            fpsPkg = pkg
            fpsLayer = run("dumpsys SurfaceFlinger --list 2>/dev/null")?.let { AppParse.sfLayerFor(it, pkg) }
            fpsPicked = now
        }
        val layer = fpsLayer ?: return Float.NaN
        val q = "'" + layer.replace("'", "'\\''") + "'"
        val f = run("dumpsys SurfaceFlinger --latency $q 2>/dev/null")?.let { AppParse.sfFps(it, System.nanoTime()) }
        // Layer gone, or still nothing after a while (the game may have opened its own surface): look again.
        if (f == null || (f <= 0f && now - fpsPicked > 10_000)) fpsLayer = null
        return f ?: Float.NaN
    }

    /** Lets BatteryLog see which app is on screen (usage access), granted through the shell. */
    fun grantUsageAccess(pkg: String) { run("appops set $pkg GET_USAGE_STATS allow") }

    /** Null when neither root nor Shizuku is available. */
    fun sample(): DeviceTick? {
        if (zoneGroups == null) {
            val types = run("grep -H . /sys/class/thermal/thermal_zone*/type 2>/dev/null") ?: return null
            zoneGroups = AppParse.grepH(types).mapNotNull { (zone, type) -> AppParse.thermalGroup(type)?.let { zone to it } }.toMap()
        }
        val out = run(SCRIPT) ?: return null
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
        // Without root many phones (Samsung among them) hide the sensor files; Android's own list still works.
        if (temps["cpu"] == null || temps["gpu"] == null || temps["skin"] == null) {
            run("dumpsys thermalservice 2>/dev/null")?.let { dump ->
                for ((g, c) in AppParse.halTemps(dump)) if (temps[g] == null) temps[g] = c
            }
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

    /** Android's own estimate per app since the last full charge (mAh), via root or Shizuku. */
    fun androidEstimates(): Map<Int, Double> =
        run("dumpsys batterystats --checkin 2>/dev/null | grep ',pwi,uid,'")?.let { AppParse.batteryStatsUid(it) } ?: emptyMap()

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
