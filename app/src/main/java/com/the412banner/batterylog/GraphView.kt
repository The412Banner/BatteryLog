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

/** Simple line chart: battery % over time, with a secondary voltage trace. */
class GraphView(ctx: Context) : View(ctx) {

    private var samples: List<Sample> = emptyList()

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

    fun setData(s: List<Sample>) { samples = s; invalidate() }

    override fun onDraw(c: Canvas) {
        val padL = 70f; val padR = 60f; val padT = 20f; val padB = 50f
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
        samples.forEachIndexed { i, s ->
            val px = x(s.epoch); val py = bottom - s.cap.coerceIn(0, 100) / 100f * h
            if (i == 0) pPath.moveTo(px, py) else pPath.lineTo(px, py)
        }
        c.drawPath(pPath, pctLine)

        // voltage path (right axis, auto-scaled)
        val volts = samples.filter { it.vMv > 0 }
        if (volts.size >= 2) {
            val vMin = volts.minOf { it.vMv }
            val vMax = volts.maxOf { it.vMv }
            val vSpan = (vMax - vMin).coerceAtLeast(1)
            val vPath = Path()
            volts.forEachIndexed { i, s ->
                val px = x(s.epoch)
                val py = bottom - (s.vMv - vMin).toFloat() / vSpan * h
                if (i == 0) vPath.moveTo(px, py) else vPath.lineTo(px, py)
            }
            c.drawPath(vPath, voltLine)
            txt.color = Color.parseColor("#FFB74D")
            c.drawText("%.2fV".format(vMax / 1000.0), right + 4f, top + 20f, txt)
            c.drawText("%.2fV".format(vMin / 1000.0), right + 4f, bottom, txt)
            txt.color = Color.parseColor("#AAAAAA")
        }

        // X time labels
        val tf = SimpleDateFormat("HH:mm", Locale.US)
        c.drawText(tf.format(Date(t0)), left, bottom + 38f, txt)
        val endLabel = tf.format(Date(t1))
        c.drawText(endLabel, right - txt.measureText(endLabel), bottom + 38f, txt)
    }
}
