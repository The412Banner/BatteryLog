package com.the412banner.batterylog

import java.io.File

/**
 * Logged battery samples in column form (a 3-month window is ~260k rows, so no object per row).
 * Rows are sorted by time. Current is normalised so negative = leaving the battery.
 */
class SampleSet(
    val t: LongArray,
    val cap: ByteArray,
    val mv: IntArray,
    val ua: IntArray,
    val chg: IntArray,
    val temp: ShortArray,
    val status: ByteArray,
    val plug: ByteArray,
    val size: Int,
) {
    val isEmpty: Boolean get() = size == 0
    val first: Long get() = if (size > 0) t[0] else 0L
    val last: Long get() = if (size > 0) t[size - 1] else 0L

    /** On the charger: status says charging/full, or the app saw a charger plugged in. */
    fun onCharger(i: Int): Boolean {
        val s = status[i]
        if (s == ST_CHARGING || s == ST_FULL) return true
        val p = plug[i]
        return p == PL_AC || p == PL_USB || p == PL_WIRELESS
    }

    /** Watts; negative = drawing from the battery. NaN when voltage or current is missing. */
    fun watts(i: Int): Float {
        if (mv[i] <= 0 || ua[i] == 0) return Float.NaN
        return (mv[i].toDouble() * ua[i].toDouble() / 1e9).toFloat()
    }

    fun tempC(i: Int): Float {
        val v = temp[i].toInt()
        return if (v > -400 && v != -1) v / 10f else Float.NaN
    }

    /** First index with t >= time (size when none). */
    fun indexAtOrAfter(time: Long): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (t[mid] < time) lo = mid + 1 else hi = mid
        }
        return lo
    }

    fun slice(from: Long, to: Long): SampleSet {
        val a = indexAtOrAfter(from)
        val b = indexAtOrAfter(to + 1)
        if (a == 0 && b == size) return this
        return copyRange(a, b)
    }

    fun copyRange(a: Int, b: Int): SampleSet {
        val n = (b - a).coerceAtLeast(0)
        return SampleSet(
            t.copyOfRange(a, a + n), cap.copyOfRange(a, a + n), mv.copyOfRange(a, a + n),
            ua.copyOfRange(a, a + n), chg.copyOfRange(a, a + n), temp.copyOfRange(a, a + n),
            status.copyOfRange(a, a + n), plug.copyOfRange(a, a + n), n,
        )
    }

    class Builder(capacity: Int = 1024) {
        private var n = 0
        private var t = LongArray(capacity)
        private var cap = ByteArray(capacity)
        private var mv = IntArray(capacity)
        private var ua = IntArray(capacity)
        private var chg = IntArray(capacity)
        private var temp = ShortArray(capacity)
        private var status = ByteArray(capacity)
        private var plug = ByteArray(capacity)

        val size: Int get() = n
        fun lastTime(): Long = if (n > 0) t[n - 1] else Long.MIN_VALUE

        private fun grow() {
            val c = (t.size * 2).coerceAtLeast(16)
            t = t.copyOf(c); cap = cap.copyOf(c); mv = mv.copyOf(c); ua = ua.copyOf(c)
            chg = chg.copyOf(c); temp = temp.copyOf(c); status = status.copyOf(c); plug = plug.copyOf(c)
        }

        fun add(tt: Long, c: Int, v: Int, i: Int, q: Int, tp: Int, st: Byte, pl: Byte) {
            if (n == t.size) grow()
            t[n] = tt; cap[n] = c.coerceIn(0, 100).toByte(); mv[n] = v; ua[n] = i; chg[n] = q
            temp[n] = tp.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            status[n] = st; plug[n] = pl
            n++
        }

        fun addFrom(s: SampleSet, i: Int) =
            add(s.t[i], s.cap[i].toInt(), s.mv[i], s.ua[i], s.chg[i], s.temp[i].toInt(), s.status[i], s.plug[i])

        fun negateCurrent() {
            for (k in 0 until n) ua[k] = -ua[k]
        }

        fun build(): SampleSet = SampleSet(
            t.copyOf(n), cap.copyOf(n), mv.copyOf(n), ua.copyOf(n), chg.copyOf(n),
            temp.copyOf(n), status.copyOf(n), plug.copyOf(n), n,
        )
    }

    companion object {
        const val ST_UNKNOWN: Byte = 0
        const val ST_CHARGING: Byte = 1
        const val ST_DISCHARGING: Byte = 2
        const val ST_NOT_CHARGING: Byte = 3
        const val ST_FULL: Byte = 4

        const val PL_UNKNOWN: Byte = 0
        const val PL_UNPLUGGED: Byte = 1
        const val PL_AC: Byte = 2
        const val PL_USB: Byte = 3
        const val PL_WIRELESS: Byte = 4
        const val PL_BOOT: Byte = 5

        val EMPTY = Builder(0).build()
    }
}

/**
 * Reads the CSV written by [BatteryLogService] and the Magisk boot sampler:
 * epoch_ms,iso_time,uptime_ms,capacity_pct,status,plugged,voltage_mV,current_uA,
 * charge_counter_uAh,energy_counter_nWh,temp_decideg,health
 * Malformed rows (e.g. a half-written last line after a hard shut-off) are skipped.
 */
object CsvLog {

    data class Parsed(val set: SampleSet, val firstUptimeMs: Long)

    fun parse(file: File): Parsed = runCatching { parse(file.readText()) }.getOrElse { Parsed(SampleSet.EMPTY, -1) }

    fun parse(text: String): Parsed {
        val b = SampleSet.Builder((text.length / 80).coerceAtLeast(16))
        var firstUptime = -1L
        var dischPos = 0
        var dischNeg = 0
        val starts = IntArray(13)
        var pos = 0
        val len = text.length
        var lineNo = 0
        while (pos < len) {
            var end = text.indexOf('\n', pos)
            if (end < 0) end = len
            val lineEnd = if (end > pos && text[end - 1] == '\r') end - 1 else end
            if (lineNo > 0 && lineEnd > pos) {
                // Field start offsets; starts[k + 1] - 1 is the end of field k.
                var fields = 0
                starts[0] = pos
                var k = pos
                while (k < lineEnd && fields < 12) {
                    if (text[k] == ',') { fields++; starts[fields] = k + 1 }
                    k++
                }
                if (fields >= 11) {
                    fun fEnd(f: Int) = if (f + 1 <= fields) starts[f + 1] - 1 else lineEnd
                    val epoch = num(text, starts[0], fEnd(0))
                    val capV = num(text, starts[3], fEnd(3))
                    if (epoch != null && capV != null && epoch > 0) {
                        val up = num(text, starts[2], fEnd(2)) ?: -1L
                        if (firstUptime < 0) firstUptime = up
                        val st = statusCode(text, starts[4], fEnd(4))
                        val pl = plugCode(text, starts[5], fEnd(5))
                        val v = (num(text, starts[6], fEnd(6)) ?: 0L).toInt()
                        val i = (num(text, starts[7], fEnd(7)) ?: 0L).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
                        val q = (num(text, starts[8], fEnd(8)) ?: 0L).toInt()
                        val tp = (num(text, starts[10], fEnd(10)) ?: -1000L).toInt()
                        if (epoch >= b.lastTime()) {
                            b.add(epoch, capV.toInt(), v, i, q, tp, st, pl)
                            if (st == SampleSet.ST_DISCHARGING && i != 0) { if (i > 0) dischPos++ else dischNeg++ }
                        }
                    }
                }
            }
            lineNo++
            pos = end + 1
        }
        // Some kernels report discharge current as positive; flip the whole file so negative = discharging.
        if (dischPos > dischNeg * 2 && dischPos > 3) b.negateCurrent()
        return Parsed(b.build(), firstUptime)
    }

    /** Parses a (possibly signed) integer in text[start, end); null when empty or not a number. */
    private fun num(s: String, start: Int, end: Int): Long? {
        var a = start
        var e = end
        while (a < e && s[a] == ' ') a++
        while (e > a && s[e - 1] == ' ') e--
        if (a >= e) return null
        var neg = false
        if (s[a] == '-') { neg = true; a++ } else if (s[a] == '+') a++
        if (a >= e) return null
        var v = 0L
        for (k in a until e) {
            val c = s[k]
            if (c !in '0'..'9') return null
            v = v * 10 + (c - '0')
        }
        return if (neg) -v else v
    }

    private fun statusCode(s: String, a: Int, e: Int): Byte {
        if (a >= e) return SampleSet.ST_UNKNOWN
        return when (s[a].lowercaseChar()) {
            'c' -> SampleSet.ST_CHARGING
            'd' -> SampleSet.ST_DISCHARGING
            'n' -> SampleSet.ST_NOT_CHARGING
            'f' -> SampleSet.ST_FULL
            else -> SampleSet.ST_UNKNOWN
        }
    }

    private fun plugCode(s: String, a: Int, e: Int): Byte {
        if (a >= e) return SampleSet.PL_UNKNOWN
        val c0 = s[a].lowercaseChar()
        val c1 = if (a + 1 < e) s[a + 1].lowercaseChar() else ' '
        val c2 = if (a + 2 < e) s[a + 2].lowercaseChar() else ' '
        return when {
            c0 == 'a' -> SampleSet.PL_AC
            c0 == 'w' -> SampleSet.PL_WIRELESS
            c0 == 'b' -> SampleSet.PL_BOOT
            c0 == 'u' && c1 == 's' -> SampleSet.PL_USB
            c0 == 'u' && c1 == 'n' && c2 == 'p' -> SampleSet.PL_UNPLUGGED
            else -> SampleSet.PL_UNKNOWN
        }
    }
}
