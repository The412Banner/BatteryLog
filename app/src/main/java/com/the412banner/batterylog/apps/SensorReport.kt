package com.the412banner.batterylog.apps

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.the412banner.batterylog.BatteryLogApp
import com.the412banner.batterylog.BuildConfig
import com.the412banner.batterylog.RootHelper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Plain-text dump of what this device exposes (thermal zones, Android's thermal list, GPU and battery files) and
 * what BatteryLog makes of it, so readings that are missing or wrong on a phone can be fixed from a user's report.
 */
object SensorReport {

    /** Builds the report and writes it to the cache folder (for sharing). Blocking: call off the UI thread. */
    fun build(ctx: Context): File {
        val script = ctx.assets.open("sensor_report.sh").bufferedReader().use { it.readText() }
        val (access, raw) = runScript(ctx, script)
        val text = buildString {
            appendLine("BatteryLog sensor report")
            appendLine("app ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.GIT_SHA})")
            appendLine("time ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss zzz", Locale.US).format(Date())}")
            appendLine("access $access")
            appendLine()
            appendLine("== what BatteryLog reads")
            append(interpret(raw))
            appendLine()
            appendLine("== BatteryManager (raw)")
            append(battery(ctx))
            appendLine()
            append(raw.trimEnd())
            appendLine()
        }
        val f = File(ctx.cacheDir, "batterylog-sensor-report.txt")
        f.writeText(text)
        return f
    }

    /** Same order as per-app monitoring: root, then Shizuku (only Shizuku with the setting); else the app's own shell. */
    private fun runScript(ctx: Context, script: String): Pair<String, String> {
        val onlyShizuku = BatteryLogApp.get(ctx).settings.current().perAppShizuku
        if (!onlyShizuku && RootHelper.rootCached()) {
            val r = RootHelper.run(script, 40)
            if (r.out.isNotBlank()) return "root" to r.out
        }
        if (ShizukuAccess.refresh() == ShizukuAccess.State.Ready) {
            // Own Shizuku service so the monitor's shell keeps running.
            val sh = ShizukuShell(ctx, "report")
            try { sh.run(script)?.let { return "Shizuku" to it } } finally { sh.close() }
        }
        return "app only (no root or Shizuku)" to RootHelper.runAsApp(script, 40).out
    }

    private fun section(raw: String, title: String): List<String> {
        val lines = raw.lines()
        val i = lines.indexOfFirst { it.startsWith("== $title") }
        if (i < 0) return emptyList()
        return lines.drop(i + 1).takeWhile { !it.startsWith("== ") }
    }

    /** Which zone each group's value comes from (hottest of the group), then the HAL values used as fallback. */
    internal fun interpret(raw: String): String = buildString {
        val best = HashMap<String, Pair<Float, String>>()
        for (line in section(raw, "thermal zones")) {
            val parts = line.split(" | ")
            if (parts.size < 3) continue
            val group = AppParse.thermalGroup(parts[1].trim()) ?: continue
            val c = AppParse.milliC(parts[2])
            if (c.isNaN()) continue
            val cur = best[group]
            if (cur == null || c > cur.first) best[group] = c to "${parts[0].trim()} (${parts[1].trim()})"
        }
        val hal = AppParse.halTemps(section(raw, "thermalservice").joinToString("\n"))
        for (g in listOf("cpu", "gpu", "skin", "ddr", "battery")) {
            val z = best[g]
            val h = hal[g]
            val v = when {
                z != null -> "%.1f °C from %s".format(Locale.US, z.first, z.second)
                h != null -> "%.1f °C from Android's thermal list".format(Locale.US, h)
                else -> "none"
            }
            appendLine("$g: $v")
        }
        val kgsl = section(raw, "kgsl-3d0").associate { it.substringBefore(":") to it.substringAfter(": ", "") }
        val busyPct = kgsl["gpu_busy_percentage"]?.trim()?.removeSuffix("%")?.trim()?.toFloatOrNull()
        val busyRaw = AppParse.kgslGpuBusy(kgsl["gpubusy"])
        appendLine("gpu busy: " + when {
            busyPct != null -> "%.0f %% (gpu_busy_percentage)".format(Locale.US, busyPct)
            busyRaw != null -> "%.1f %% (gpubusy)".format(Locale.US, busyRaw)
            else -> "none"
        })
        val clk = kgsl["gpuclk"]?.trim()?.toLongOrNull()
        appendLine("gpu clock: " + (clk?.let { "${it / 1_000_000} MHz (gpuclk)" } ?: "none"))
    }

    private fun battery(ctx: Context): String = buildString {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val s = runCatching { ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val counter = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val cap = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        appendLine("units: " + if (com.the412banner.batterylog.BatteryUnits.isMilli(counter, cap)) "mA / mAh (BatteryLog converts)" else "µA / µAh")
        appendLine("CURRENT_NOW=${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)}")
        appendLine("CURRENT_AVERAGE=${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)}")
        appendLine("CHARGE_COUNTER=${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)}")
        appendLine("CAPACITY=${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}")
        appendLine("ENERGY_COUNTER=${bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)}")
        appendLine("voltage=${s?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)}")
        appendLine("temperature=${s?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)}")
        appendLine("status=${s?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)} plugged=${s?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)}")
    }
}
