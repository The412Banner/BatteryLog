package com.the412banner.batterylog.apps

/** Pure parsing helpers for the per-app sampler (unit-tested, no Android types). */
object AppParse {
    data class Proc(val pid: Int, val uid: Int, val rssKb: Long, val cpuMs: Long, val name: String) {
        /** App processes are named after their package; ":service" suffixes belong to the same app. */
        val pkg: String get() = name.substringBefore(':')
        val isApp: Boolean get() = uid >= 10000 && name.contains('.') && !name.startsWith("[")
    }

    /** `ps -A -o PID,UID,RSS,TIME+,NAME` -> processes. TIME+ is [[DD-]HH:]MM:SS.hh */
    fun ps(text: String): List<Proc> {
        val out = ArrayList<Proc>()
        for (line in text.lineSequence()) {
            val p = line.trim().split(Regex("\\s+"), limit = 5)
            if (p.size < 5) continue
            val pid = p[0].toIntOrNull() ?: continue
            val uid = p[1].toIntOrNull() ?: continue
            val rss = p[2].toLongOrNull() ?: 0L
            val cpu = cpuTimeMs(p[3]) ?: continue
            out += Proc(pid, uid, rss, cpu, p[4].trim())
        }
        return out
    }

    fun cpuTimeMs(s: String): Long? {
        var days = 0L
        var rest = s
        if (rest.contains('-')) {
            days = rest.substringBefore('-').toLongOrNull() ?: return null
            rest = rest.substringAfter('-')
        }
        val parts = rest.split(':')
        val secPart = parts.last()
        val sec = secPart.substringBefore('.').toLongOrNull() ?: return null
        val frac = secPart.substringAfter('.', "0").padEnd(2, '0').take(2).toLongOrNull() ?: 0L
        val min = if (parts.size >= 2) parts[parts.size - 2].toLongOrNull() ?: return null else 0L
        val hr = if (parts.size >= 3) parts[parts.size - 3].toLongOrNull() ?: return null else 0L
        return (((days * 24 + hr) * 60 + min) * 60 + sec) * 1000 + frac * 10
    }

    /** `grep -H .` over many files: lines "path/<id>/<file>:value" -> id -> value. */
    fun grepH(text: String): Map<String, String> {
        val m = HashMap<String, String>()
        for (line in text.lineSequence()) {
            val i = line.indexOf(':')
            if (i <= 0) continue
            val path = line.substring(0, i)
            val id = path.substringBeforeLast('/').substringAfterLast('/')
            m[id] = line.substring(i + 1).trim()
        }
        return m
    }

    /** First line of /proc/stat -> (busy, total) jiffies. */
    fun procStat(line: String): Pair<Long, Long>? {
        val f = line.trim().split(Regex("\\s+"))
        if (f.isEmpty() || f[0] != "cpu" || f.size < 5) return null
        val v = f.drop(1).mapNotNull { it.toLongOrNull() }
        if (v.size < 4) return null
        val idle = v[3] + v.getOrElse(4) { 0 }
        val total = v.sum()
        return (total - idle) to total
    }

    /** "battery" / Android batterystats checkin "9,<uid>,l,pwi,uid,<mAh>,..." -> uid -> mAh. */
    fun batteryStatsUid(text: String): Map<Int, Double> {
        val m = HashMap<Int, Double>()
        for (line in text.lineSequence()) {
            val f = line.split(',')
            if (f.size >= 6 && f[2] == "l" && f[3] == "pwi" && f[4] == "uid") {
                val uid = f[1].toIntOrNull() ?: continue
                val mah = f[5].toDoubleOrNull() ?: continue
                m[uid] = (m[uid] ?: 0.0) + mah
            }
        }
        return m
    }

    /** Thermal zone type -> group used in the app (cpu, gpu, skin, ddr, battery) or null. */
    fun thermalGroup(type: String): String? {
        val t = type.lowercase()
        return when {
            t.startsWith("cpu") || t.startsWith("cpuss") || t.contains("cpu-") -> "cpu"
            t.startsWith("gpu") || t.contains("gpuss") -> "gpu"
            t.contains("skin") || t.contains("quiet") || t == "xo-therm" || t.contains("sys-therm") -> "skin"
            t == "ddr" || t.startsWith("ddr") -> "ddr"
            t == "battery" || t.startsWith("batt") -> "battery"
            else -> null
        }
    }

    /**
     * `dumpsys thermalservice` -> group -> hottest °C, from the HAL's current readings
     * (android.os.Temperature types: 0 CPU, 1 GPU, 2 battery, 3 skin). Used when the sensor files can't be read.
     */
    fun halTemps(dump: String): Map<String, Float> {
        val lines = dump.lines()
        fun section(title: String): List<String> {
            val i = lines.indexOfFirst { it.trim().startsWith(title) }
            if (i < 0) return emptyList()
            return lines.drop(i + 1).takeWhile { it.trim().startsWith("Temperature{") }
        }
        val rows = section("Current temperatures from HAL").ifEmpty { section("Cached temperatures") }
        val re = Regex("""mValue=([-0-9.]+),\s*mType=(\d+)""")
        val m = HashMap<String, Float>()
        for (r in rows) {
            val g = re.find(r) ?: continue
            val v = g.groupValues[1].toFloatOrNull() ?: continue
            val group = when (g.groupValues[2].toIntOrNull()) { 0 -> "cpu"; 1 -> "gpu"; 2 -> "battery"; 3 -> "skin"; else -> null } ?: continue
            if (v.isNaN() || v !in -40f..150f) continue
            m[group] = maxOf(m[group] ?: v, v)
        }
        return m
    }

    /** Thermal readings in millidegrees (or degrees on some kernels) -> °C, NaN when not plausible. */
    fun milliC(raw: String?): Float {
        val v = raw?.trim()?.toFloatOrNull() ?: return Float.NaN
        val c = if (kotlin.math.abs(v) > 1000f) v / 1000f else v
        return if (c in -40f..150f) c else Float.NaN
    }
}
