package com.the412banner.batterylog

import java.io.File

/**
 * Whole-life battery facts kept by the kernel fuel gauge (they persist since the battery was made,
 * independent of when this app was installed). Read from /sys/class/power_supply/battery; if SELinux
 * hides a node from the app, one `su` call fills the gaps (only when root was already granted).
 */
object LifetimeStats {

    private const val SYS = "/sys/class/power_supply/battery"

    private val NODES = listOf(
        "cycle_count", "charge_full", "charge_full_design", "charge_counter",
        "voltage_now", "voltage_ocv", "voltage_max", "health", "status",
        "technology", "model_name", "temp", "manufacturer", "serial_number",
    )

    data class Stats(
        val values: Map<String, String>,
        val usedRoot: Boolean,
    ) {
        private fun int(n: String) = values[n]?.trim()?.toLongOrNull()
        val cycleCount: Int? get() = int("cycle_count")?.toInt()?.takeIf { it >= 0 }
        val fullMah: Int? get() = int("charge_full")?.let { (it / 1000).toInt() }?.takeIf { it > 0 }
        val designMah: Int? get() = int("charge_full_design")?.let { (it / 1000).toInt() }?.takeIf { it > 0 }
        val sohPct: Double? get() {
            val f = fullMah ?: return null
            val d = designMah ?: return null
            return f.toDouble() / d * 100.0
        }
        val lostMah: Int? get() {
            val f = fullMah ?: return null
            val d = designMah ?: return null
            return d - f
        }
        val ocvMv: Int? get() = int("voltage_ocv")?.let { (it / 1000).toInt() }?.takeIf { it > 0 }
        val vmaxMv: Int? get() = int("voltage_max")?.let { (it / 1000).toInt() }?.takeIf { it > 0 }
        val tempC: Double? get() = int("temp")?.let { it / 10.0 }
        val technology: String? get() = values["technology"]
        val model: String? get() = values["model_name"]
        val manufacturer: String? get() = values["manufacturer"]
        val anyData: Boolean get() = values.isNotEmpty()

        /** Cells in series: from the charge voltage (a 2S pack charges to ~8.7 V), else null. */
        val cellsFromVmax: Int? get() = vmaxMv?.let { if (it > 6_000) 2 else 1 }
    }

    @Volatile private var cached: Stats? = null
    @Volatile private var cachedAt = 0L

    /** Cached for 60 s; [fresh] forces a re-read. */
    fun read(fresh: Boolean = false): Stats {
        val c = cached
        if (!fresh && c != null && System.currentTimeMillis() - cachedAt < 60_000) return c
        val direct = HashMap<String, String>()
        NODES.forEach { n ->
            runCatching { File("$SYS/$n").readText().trim() }
                .getOrNull()?.takeIf { it.isNotEmpty() }?.let { direct[n] = it }
        }
        var usedRoot = false
        val missing = NODES.filter { it !in direct }
        if (missing.isNotEmpty() && RootHelper.knownRoot()) {
            val script = missing.joinToString("\n") { n ->
                "v=\$(cat $SYS/$n 2>/dev/null); [ -n \"\$v\" ] && echo \"$n\t\$v\""
            }
            val res = RootHelper.run(script)
            if (res.ok) {
                res.out.lineSequence().forEach { ln ->
                    val p = ln.split("\t", limit = 2)
                    if (p.size == 2 && p[0] in NODES && p[1].isNotBlank()) {
                        direct[p[0]] = p[1].trim(); usedRoot = true
                    }
                }
            }
        }
        return Stats(direct, usedRoot).also { cached = it; cachedAt = System.currentTimeMillis() }
    }

    /** Cells in series for this device: kernel charge voltage first, else the highest logged voltage. */
    fun cells(observedMaxMv: Int = 0): Int =
        read().cellsFromVmax ?: if (observedMaxMv > 4_700) 2 else 1
}
