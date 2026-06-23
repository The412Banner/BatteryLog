package com.the412banner.batterylog

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class HistoryActivity : AppCompatActivity() {

    private lateinit var graph: GraphView
    private lateinit var detail: TextView
    private lateinit var picker: LinearLayout
    private var selected: File? = null

    // ---- range views (aggregate across all recorded runs) ----
    private val ranges = listOf(
        "24h"  to 24L * 3_600_000,
        "7d"   to 7L * 86_400_000,
        "14d"  to 14L * 86_400_000,
        "1 mo" to 30L * 86_400_000,
        "3 mo" to 90L * 86_400_000,
    )
    private val rangeButtons = mutableListOf<Button>()
    private var activeRange: String? = null
    /** Break the line across logging gaps longer than this in range views. */
    private val gapBreakMs = 20L * 60_000

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Battery cycles"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "Battery History"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(12))
        })

        // ---- Lifetime card (whole-life fuel-gauge data, since manufacture) ----
        root.addView(lifetimeCard())

        root.addView(TextView(this).apply {
            text = "Time range"
            textSize = 13f; setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), dp(8), 0, dp(6))
        })
        root.addView(rangeSelector())

        graph = GraphView(this)
        root.addView(MaterialCardView(this).apply {
            radius = dp(14).toFloat(); cardElevation = dp(2).toFloat(); useCompatPadding = true
            addView(graph, LinearLayout.LayoutParams(MATCH_PARENT, dp(240)))
        })
        root.addView(legend())

        detail = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 12f
            setPadding(dp(4), dp(12), dp(4), dp(12)); setTextIsSelectable(true)
        }
        root.addView(detail)

        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1))
                .also { it.topMargin = dp(8); it.bottomMargin = dp(8) }
            setBackgroundColor(Color.parseColor("#3A3A3A"))
        })
        root.addView(TextView(this).apply {
            text = "Recorded runs (tap to view)"
            textSize = 13f; setTypeface(typeface, Typeface.BOLD); setPadding(dp(4), 0, 0, dp(4))
        })

        picker = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(picker)

        setContentView(ScrollView(this).apply { addView(root) })
        loadList()
    }

    override fun onResume() { super.onResume(); loadList() }

    private fun loadList() {
        val logs = LogStore.logs(this)
        picker.removeAllViews()
        if (logs.isEmpty()) {
            detail.text = "No runs recorded yet.\nStart monitoring on the main screen, then come back here."
            graph.setData(emptyList())
            return
        }
        logs.forEach { f -> picker.addView(rowFor(f)) }
        when {
            activeRange != null ->
                ranges.firstOrNull { it.first == activeRange }
                    ?.let { selectRange(it.first, it.second) }
                    ?: selectRange(ranges[0].first, ranges[0].second)
            selected != null && logs.contains(selected) -> select(selected!!)
            else -> selectRange(ranges[0].first, ranges[0].second) // default: last 24h
        }
    }

    // ---- range selector (horizontal chips above the graph) ----
    private fun rangeSelector(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rangeButtons.clear()
        ranges.forEach { (label, ms) ->
            val b = Button(this).apply {
                text = label; isAllCaps = false; textSize = 13f
                minWidth = 0; minimumWidth = 0
                setPadding(dp(16), dp(6), dp(16), dp(6))
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                    .also { it.rightMargin = dp(8) }
                setOnClickListener { selectRange(label, ms) }
            }
            rangeButtons.add(b)
            row.addView(b)
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, dp(8))
            addView(row)
        }
    }

    private fun highlightRange() {
        rangeButtons.forEach { b ->
            val on = b.text.toString() == activeRange
            b.setBackgroundColor(Color.parseColor(if (on) "#1565C0" else "#2A2A2A"))
            b.setTextColor(if (on) Color.WHITE else Color.parseColor("#BBBBBB"))
        }
    }

    private fun selectRange(label: String, windowMs: Long) {
        activeRange = label
        selected = null
        highlightRange()
        val cutoff = System.currentTimeMillis() - windowMs
        val rows = LogStore.samplesSince(this, cutoff)
        graph.gapBreakMs = gapBreakMs
        graph.setData(rows)
        detail.text = summarizeRange(label, rows, cutoff)
    }

    private fun summarizeRange(label: String, rows: List<Sample>, cutoff: Long): String {
        if (rows.size < 2)
            return "Range     : last $label\n" +
                "Since     : ${full(cutoff)}\n" +
                "Not enough samples in this window yet (${rows.size}).\n" +
                "Start a monitoring run, or pick a wider range / an individual run below."
        val a = rows.first(); val b = rows.last()
        var drained = 0; var charged = 0
        var dischMs = 0L; var dischDrop = 0
        var sessions = 1
        for (i in 1 until rows.size) {
            val dt = rows[i].epoch - rows[i - 1].epoch
            val dc = rows[i].cap - rows[i - 1].cap
            if (dt > gapBreakMs) { sessions++; continue }       // new run / device was off
            if (dc < 0) { drained += -dc; dischMs += dt; dischDrop += -dc }
            else if (dc > 0) charged += dc
        }
        val sb = StringBuilder()
        fun ln(s: String) = sb.append(s).append('\n')
        ln("Range     : last $label   ($sessions run${if (sessions == 1) "" else "s"}, ${rows.size} samples)")
        ln("Window    : ${full(a.epoch)}")
        ln("       →    ${full(b.epoch)}")
        ln("Level     : ${a.cap}% → ${b.cap}%   (net Δ ${b.cap - a.cap}%)")
        ln("Discharged: ${drained}%   •   Charged: ${charged}%")
        if (dischMs > 0 && dischDrop > 0) {
            val rate = dischDrop / (dischMs / 3_600_000.0)
            ln("Avg drain : ${"%.1f".format(rate)} %/hr   (over ${fmtDur(dischMs)} of use)")
        }
        val volts = rows.filter { it.vMv > 0 }
        if (volts.isNotEmpty()) {
            val mn = volts.minBy { it.vMv }
            ln("Voltage   : min ${"%.3f".format(mn.vMv / 1000.0)}V @ ${mn.cap}%" +
               "   (max ${"%.3f".format(volts.maxOf { it.vMv } / 1000.0)}V)")
        }
        val temps = rows.filter { it.tempDecideg > 0 }
        if (temps.isNotEmpty())
            ln("Temp      : ${"%.1f".format(temps.minOf { it.tempDecideg } / 10.0)}–" +
               "${"%.1f".format(temps.maxOf { it.tempDecideg } / 10.0)}°C")
        return sb.toString().trimEnd()
    }

    private fun rowFor(f: File): View {
        val rows = LogStore.parse(f)
        val sub = if (rows.isEmpty()) "(empty)" else {
            val a = rows.first(); val b = rows.last()
            val dir = when {
                b.cap > a.cap -> "charge"; b.cap < a.cap -> "discharge"; else -> "flat"
            }
            "${a.cap}% → ${b.cap}%  ($dir, ${rows.size} pts)"
        }
        return Button(this).apply {
            text = "${stamp(f)}\n$sub"
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .also { it.topMargin = dp(4) }
            setOnClickListener { selected = f; select(f) }
        }
    }

    private fun select(f: File) {
        selected = f
        activeRange = null
        highlightRange()
        val rows = LogStore.parse(f)
        graph.gapBreakMs = 0          // single run: draw a continuous line as before
        graph.setData(rows)
        detail.text = summarize(f, rows)
    }

    private fun summarize(f: File, rows: List<Sample>): String {
        if (rows.size < 2) return "${f.name}\nNot enough samples."
        val a = rows.first(); val b = rows.last()
        val durMs = b.epoch - a.epoch
        val durH = durMs / 3_600_000.0
        val sb = StringBuilder()
        fun ln(s: String) = sb.append(s).append('\n')
        val dir = if (b.cap < a.cap) "Discharge" else if (b.cap > a.cap) "Charge" else "Flat"
        ln("File      : ${f.name}")
        ln("Type      : $dir")
        ln("From      : ${full(a.epoch)}")
        ln("To        : ${full(b.epoch)}")
        ln("Duration  : ${fmtDur(durMs)}   (${rows.size} samples)")
        ln("Level     : ${a.cap}% → ${b.cap}%   (Δ ${b.cap - a.cap}%)")

        val chg = rows.filter { it.chgUah > 0 }
        if (chg.size >= 2) {
            val used = abs(chg.first().chgUah - chg.last().chgUah) / 1000.0
            ln("Charge    : ${chg.first().chgUah / 1000} → ${chg.last().chgUah / 1000} mAh  (Δ ${"%.0f".format(used)} mAh)")
            val capDelta = abs(b.cap - a.cap)
            if (capDelta > 0) ln("Extrap.full: ${"%.0f".format(used / capDelta * 100)} mAh (0→100%)")
        }
        val volts = rows.filter { it.vMv > 0 }
        if (volts.isNotEmpty()) {
            val mn = volts.minBy { it.vMv }
            ln("Voltage   : ${"%.3f".format(a.vMv / 1000.0)} → ${"%.3f".format(b.vMv / 1000.0)} V" +
               "   (min ${"%.3f".format(mn.vMv / 1000.0)}V @ ${mn.cap}%)")
        }
        val temps = rows.filter { it.tempDecideg > 0 }
        if (temps.isNotEmpty())
            ln("Temp      : ${"%.1f".format(temps.minOf { it.tempDecideg } / 10.0)}–" +
               "${"%.1f".format(temps.maxOf { it.tempDecideg } / 10.0)}°C")
        if (durH > 0 && b.cap != a.cap)
            ln("Rate      : ${"%.1f".format(abs(b.cap - a.cap) / durH)} %/hr")
        return sb.toString()
    }

    // ---- Lifetime card ----
    private fun lifetimeCard(): View {
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        inner.addView(TextView(this).apply {
            text = "Lifetime — Battery Health"
            textSize = 16f; setTypeface(typeface, Typeface.BOLD)
        })
        inner.addView(TextView(this).apply {
            text = "Maintained by the device since the battery was manufactured."
            textSize = 11f; setTextColor(Color.parseColor("#9E9E9E"))
            setPadding(0, dp(2), 0, dp(10))
        })
        val body = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 13f
            setTextIsSelectable(true)
            text = "Reading fuel gauge…"
        }
        inner.addView(body)

        // Off the UI thread — read() may consult `su`.
        Thread {
            val s = LifetimeStats.read()
            val txt = if (!s.anyData) "Fuel-gauge data unavailable on this device." else buildString {
                fun ln(k: String, v: String) = append(k.padEnd(20, ' ')).append(v).append('\n')
                s.cycleCount?.let { ln("Charge cycles", "$it   (since manufacture)") }
                val f = s.fullMah; val d = s.designMah
                if (f != null) ln("Full capacity now", "$f mAh")
                if (d != null) ln("Design capacity", "$d mAh")
                s.sohPct?.let { ln("State of health", "%.1f %%".format(it)) }
                s.lostMah?.let { lost ->
                    val pct = if (d != null && d > 0) " (%.0f%%)".format(lost.toDouble() / d * 100) else ""
                    ln("Capacity lost", "$lost mAh$pct")
                }
                s.technology?.let { ln("Technology", it) }
                s.model?.let { ln("Model", it) }
                s.ocvMv?.let { ln("Open-circuit V", "%.3f V".format(it / 1000.0)) }
                s.vmaxMv?.let { ln("Voltage max", "%.3f V".format(it / 1000.0)) }
                s.tempC?.let { ln("Temperature", "%.1f °C".format(it)) }
                if (s.usedRoot) append("\n(read via root)")
            }
            runOnUiThread { body.text = txt.trimEnd() }
        }.start()

        return MaterialCardView(this).apply {
            radius = dp(14).toFloat(); cardElevation = dp(2).toFloat(); useCompatPadding = true
            addView(inner)
        }
    }

    // helpers
    private fun legend() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(6), dp(6), 0, 0)
        addView(TextView(this@HistoryActivity).apply {
            text = "▬ Battery %"; setTextColor(Color.parseColor("#4FC3F7")); textSize = 12f
        })
        addView(TextView(this@HistoryActivity).apply {
            text = "    ▬ Voltage"; setTextColor(Color.parseColor("#FFB74D")); textSize = 12f
        })
    }

    private fun stamp(f: File) =
        SimpleDateFormat("EEE MMM d, HH:mm", Locale.US).format(Date(f.lastModified()))
    private fun full(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))
    private fun fmtDur(ms: Long): String {
        val s = ms / 1000; return "%dh %02dm %02ds".format(s / 3600, (s % 3600) / 60, s % 60)
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
