package com.the412banner.batterylog

import java.io.File

/**
 * Whole-life battery facts maintained by the kernel fuel-gauge — these persist
 * since the battery was MANUFACTURED, independent of when this app was installed.
 *
 * Read straight from /sys/class/power_supply/battery. Most nodes here are
 * world-readable (that's how [BatteryInfo] already shows SoH without root); if
 * SELinux blocks the app from a node we fall back to a single `su` cat.
 */
object LifetimeStats {

    private const val SYS = "/sys/class/power_supply/battery"

    // Nodes we surface, in display order.
    private val NODES = listOf(
        "cycle_count", "charge_full", "charge_full_design", "charge_counter",
        "voltage_now", "voltage_ocv", "voltage_max", "health", "status",
        "technology", "model_name", "temp"
    )

    data class Stats(
        val values: Map<String, String>,   // raw node -> value
        val usedRoot: Boolean
    ) {
        private fun int(n: String) = values[n]?.toIntOrNull()
        val cycleCount get() = int("cycle_count")
        val fullMah get() = int("charge_full")?.let { it / 1000 }
        val designMah get() = int("charge_full_design")?.let { it / 1000 }
        val sohPct: Double? get() {
            val f = fullMah; val d = designMah
            return if (f != null && d != null && d > 0) f.toDouble() / d * 100.0 else null
        }
        val lostMah: Int? get() {
            val f = fullMah; val d = designMah
            return if (f != null && d != null) d - f else null
        }
        val ocvMv get() = int("voltage_ocv")?.let { it / 1000 }
        val vmaxMv get() = int("voltage_max")?.let { it / 1000 }
        val tempC: Double? get() = int("temp")?.let { it / 10.0 }
        val technology get() = values["technology"]
        val model get() = values["model_name"]
        val anyData get() = values.isNotEmpty()
    }

    fun read(): Stats {
        val direct = HashMap<String, String>()
        NODES.forEach { n ->
            runCatching { File("$SYS/$n").readText().trim() }
                .getOrNull()?.takeIf { it.isNotEmpty() }?.let { direct[n] = it }
        }
        val missing = NODES.filter { it !in direct }
        if (missing.isEmpty() || !RootHelper.hasRoot()) return Stats(direct, usedRoot = false)

        // Fill the gaps in one root shell: "node\tvalue" per line.
        val script = missing.joinToString("\n") { n ->
            "v=$(cat $SYS/$n 2>/dev/null); [ -n \"\$v\" ] && echo \"$n\t\$v\""
        }
        val res = RootHelper.run(script)
        var usedRoot = false
        if (res.ok) {
            res.out.lineSequence().forEach { ln ->
                val p = ln.split("\t", limit = 2)
                if (p.size == 2 && p[0] in NODES && p[1].isNotBlank()) {
                    direct[p[0]] = p[1].trim(); usedRoot = true
                }
            }
        }
        return Stats(direct, usedRoot)
    }
}
