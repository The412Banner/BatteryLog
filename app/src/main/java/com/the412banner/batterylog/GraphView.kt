package com.the412banner.batterylog

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Simple line chart: battery % over time, with secondary voltage and temperature traces. */
class GraphView(ctx: Context) : View(ctx) {

    private var samples: List<Sample> = emptyList()

    /** Compact mode for the small main-screen live graph: thinner, no legend. */
    var compact = false

    /**
     * When > 0, the line is broken (lifted) wherever two consecutive samples are more
     * than this many ms apart, so merged multi-run range views don't draw a misleading
     * straight bridge across periods when nothing was being logged. 0 = always connect.
     */
    var gapBreakMs: Long = 0

    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#555555"); strokeWidth = 1.5f
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2E2E2E"); strokeWidth = 1f
    }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAAAAA"); textSize = 26f
    }
    private val pctLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4FC3F7"); strokeWidth = 4f; style = Paint.Style.STROKE
    }
    private val voltLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFB74D"); strokeWidth = 2.5f; style = Paint.Style.STROKE
    }
    private val tempLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E57373"); strokeWidth = 2.5f; style = Paint.Style.STROKE
    }

    private val pctColor = Color.parseColor("#4FC3F7")
    private val voltColor = Color.parseColor("#FFB74D")
    private val tempColor = Color.parseColor("#E57373")

    fun setData(s: List<Sample>) { samples = s; invalidate() }

    override fun onDraw(c: Canvas) {
        txt.textSize = if (compact) 20f else 26f
        val padL = if (compact) 52f else 70f; val padR = if (compact) 48f else 60f
        val padT = if (compact) 10f else 20f; val padB = if (compact) 34f else 50f
        val w = width - padL - padR
        val h = height - padT - padB
        val left = padL; val top = padT; val right = padL + w; val bottom = padT + h

        // Y grid + labels (0..100%)
        for (p in 0..100 step 25) {
            val y = bottom - p / 100f * h
            c.drawLine(left, y, right, y, grid)
            c.drawText("$p", 8f, y + 9f, txt)
        }
        c.drawLine(left, top, left, bottom, axis)
        c.drawLine(left, bottom, right, bottom, axis)

        if (samples.size < 2) {
            c.drawText("No data — start a monitoring run first.", left + 20f, top + h / 2, txt)
            return
        }

        val t0 = samples.first().epoch
        val t1 = samples.last().epoch
        val span = (t1 - t0).coerceAtLeast(1)
        fun x(e: Long) = left + (e - t0).toFloat() / span * w

        // % path
        val pPath = Path()
        var pPrev = 0L
        samples.forEachIndexed { i, s ->
            val px = x(s.epoch); val py = bottom - s.cap.coerceIn(0, 100) / 100f * h
            if (i == 0 || (gapBreakMs > 0 && s.epoch - pPrev > gapBreakMs)) pPath.moveTo(px, py)
            else pPath.lineTo(px, py)
            pPrev = s.epoch
        }
        c.drawPath(pPath, pctLine)

        // voltage path (right axis, auto-scaled)
        var voltRange: String? = null
        val volts = samples.filter { it.vMv > 0 }
        if (volts.size >= 2) {
            val vMin = volts.minOf { it.vMv }
            val vMax = volts.maxOf { it.vMv }
            val vSpan = (vMax - vMin).coerceAtLeast(1)
            val vPath = Path()
            var vPrev = 0L
            volts.forEachIndexed { i, s ->
                val px = x(s.epoch)
                val py = bottom - (s.vMv - vMin).toFloat() / vSpan * h
                if (i == 0 || (gapBreakMs > 0 && s.epoch - vPrev > gapBreakMs)) vPath.moveTo(px, py)
                else vPath.lineTo(px, py)
                vPrev = s.epoch
            }
            c.drawPath(vPath, voltLine)
            txt.color = voltColor
            c.drawText("%.2fV".format(vMax / 1000.0), right + 4f, top + 20f, txt)
            c.drawText("%.2fV".format(vMin / 1000.0), right + 4f, bottom, txt)
            txt.color = Color.parseColor("#AAAAAA")
            voltRange = "%.2f–%.2fV".format(vMin / 1000.0, vMax / 1000.0)
        }

        // temperature path (own auto-scale; tempDecideg = tenths of °C)
        var tempRange: String? = null
        val temps = samples.filter { it.tempDecideg > 0 }
        if (temps.size >= 2) {
            val tMin = temps.minOf { it.tempDecideg }
            val tMax = temps.maxOf { it.tempDecideg }
            val tSpan = (tMax - tMin).coerceAtLeast(1)
            val tPath = Path()
            var tPrev = 0L
            temps.forEachIndexed { i, s ->
                val px = x(s.epoch)
                val py = bottom - (s.tempDecideg - tMin).toFloat() / tSpan * h
                if (i == 0 || (gapBreakMs > 0 && s.epoch - tPrev > gapBreakMs)) tPath.moveTo(px, py)
                else tPath.lineTo(px, py)
                tPrev = s.epoch
            }
            c.drawPath(tPath, tempLine)
            txt.color = tempColor
            c.drawText("%.1f°".format(tMax / 10.0), right + 4f, top + 56f, txt)
            c.drawText("%.1f°".format(tMin / 10.0), right + 4f, bottom - 36f, txt)
            txt.color = Color.parseColor("#AAAAAA")
            tempRange = "%.1f–%.1f°C".format(tMin / 10.0, tMax / 10.0)
        }

        // legend (top-left, inside plot) — skipped in the compact main-screen graph
        if (!compact) {
            var lx = left + 14f
            val ly = top + 26f
            fun legend(color: Int, label: String) {
                txt.color = color
                c.drawText("●", lx, ly, txt)
                lx += txt.measureText("●") + 4f
                txt.color = Color.parseColor("#CCCCCC")
                c.drawText(label, lx, ly, txt)
                lx += txt.measureText(label) + 22f
            }
            legend(pctColor, "%")
            voltRange?.let { legend(voltColor, it) }
            tempRange?.let { legend(tempColor, it) }
            txt.color = Color.parseColor("#AAAAAA")
        }

        // X time labels — switch to a date format once the window spans more than ~36h
        val multiDay = span > 36L * 3_600_000
        val tf = SimpleDateFormat(if (multiDay) "MMM d" else "HH:mm", Locale.US)
        c.drawText(tf.format(Date(t0)), left, bottom + 38f, txt)
        val endLabel = tf.format(Date(t1))
        c.drawText(endLabel, right - txt.measureText(endLabel), bottom + 38f, txt)
        if (!compact && multiDay) {
            val midLabel = tf.format(Date(t0 + span / 2))
            c.drawText(midLabel, left + w / 2 - txt.measureText(midLabel) / 2, bottom + 38f, txt)
        }
    }
}
