package com.the412banner.batterylog

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView

class MainActivity : AppCompatActivity() {

    private lateinit var bigLevel: TextView
    private lateinit var summary: TextView
    private lateinit var details: TextView
    private lateinit var expandHint: TextView
    private lateinit var status: TextView
    private lateinit var report: TextView
    private lateinit var autoBtn: Button

    private var expanded = false
    private var lastReport = ""
    private val ui = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { refreshCard(); refreshStatus(); ui.postDelayed(this, 2_000) }
    }

    private val notifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "BatteryLog"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(12))
        })

        root.addView(buildBatteryCard())

        status = TextView(this).apply {
            textSize = 12f
            setPadding(dp(4), dp(14), dp(4), dp(12))
        }
        root.addView(status)

        autoBtn = button("🔌  Auto: start on unplug / stop on plug") { toggleAuto() }
        root.addView(autoBtn)
        root.addView(button("▶  Start monitoring (manual)") { start() })
        root.addView(button("■  Stop monitoring") { stop() })
        root.addView(button("📄  Generate report") { makeReport() })
        root.addView(button("↗  Share report") { shareReport() })
        root.addView(button("📈  History & cycles") {
            startActivity(Intent(this, HistoryActivity::class.java))
        })
        root.addView(divider())
        root.addView(label("Boot persistence (Magisk, root)"))
        root.addView(button("Install boot module") { installModule() })
        root.addView(button("Remove boot module") { removeModule() })

        report = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(report)

        setContentView(ScrollView(this).apply { addView(root) })

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // ---- Battery card ----
    private fun buildBatteryCard(): View {
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bigLevel = TextView(this).apply {
            textSize = 40f
            setTypeface(typeface, Typeface.BOLD)
        }
        headerRow.addView(bigLevel, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        expandHint = TextView(this).apply {
            text = "Details ▾"
            textSize = 13f
            setTextColor(Color.parseColor("#4FC3F7"))
        }
        headerRow.addView(expandHint)
        inner.addView(headerRow)

        summary = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(6), 0, 0)
        }
        inner.addView(summary)

        details = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            visibility = View.GONE
            setPadding(0, dp(12), 0, 0)
            setTextIsSelectable(true)
        }
        inner.addView(details)

        return MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = dp(3).toFloat()
            useCompatPadding = true
            isClickable = true
            addView(inner)
            setOnClickListener { toggleExpand() }
        }.also { refreshCard() }
    }

    private fun toggleExpand() {
        expanded = !expanded
        details.visibility = if (expanded) View.VISIBLE else View.GONE
        expandHint.text = if (expanded) "Hide ▴" else "Details ▾"
        if (expanded) refreshCard()
    }

    private fun refreshCard() {
        val b = BatteryInfo.read(this)
        bigLevel.text = if (b.capacityPct < 0) "—" else "${b.capacityPct}%"
        val parts = mutableListOf(b.status)
        if (b.voltageMv >= 0) parts += "%.2f V".format(b.voltageMv / 1000.0)
        if (!b.currentMa.isNaN()) parts += "%.0f mA".format(b.currentMa)
        if (!b.tempC.isNaN()) parts += "%.1f°C".format(b.tempC)
        if (!b.sohPct.isNaN()) parts += "SoH %.0f%%".format(b.sohPct)
        summary.text = parts.joinToString("   •   ")
        if (expanded) {
            details.text = b.details().joinToString("\n") {
                it.first.padEnd(22, ' ') + it.second
            }
        }
    }

    // ---- monitoring status ----
    private fun refreshStatus() {
        val running = BatteryLogService.running
        val armed = BatteryLogService.autoArmed

        autoBtn.text = if (armed) "🔌  Auto: ARMED — tap to disarm"
                       else "🔌  Auto: start on unplug / stop on plug"

        status.text = buildString {
            when {
                running && armed -> append("● AUTO — recording discharge (every 30s)\n")
                running          -> append("● MONITORING — sampling every 30s\n")
                armed            -> append("◉ AUTO-ARMED — logging starts when you unplug\n")
                else             -> append("○ Idle — not logging\n")
            }
            if (running && BatteryLogService.currentLogPath != null) {
                append("Current log: ${java.io.File(BatteryLogService.currentLogPath!!).name}")
            } else {
                // Don't claim "none" when the boot module or a past run left logs.
                val latest = LogStore.latest(this@MainActivity)
                append("Latest log on disk: ${latest?.name ?: "none"}")
            }
        }
        status.setTextColor(
            when {
                running -> Color.parseColor("#66BB6A")   // green
                armed   -> Color.parseColor("#4FC3F7")   // cyan
                else    -> Color.GRAY
            }
        )
    }

    private fun toggleAuto() {
        if (BatteryLogService.autoArmed) {
            stop()
            toast("Auto mode off.")
        } else {
            ContextCompat.startForegroundService(
                this, Intent(this, BatteryLogService::class.java)
                    .setAction(BatteryLogService.ACTION_START_AUTO)
            )
            toast("Auto armed — unplug to begin a clean discharge log.")
            status.postDelayed({ refreshStatus() }, 600)
        }
    }

    override fun onResume() { super.onResume(); ui.post(ticker) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(ticker) }

    // ---- actions ----
    private fun start() {
        ContextCompat.startForegroundService(this, Intent(this, BatteryLogService::class.java))
        toast("Monitoring started — you can disconnect the charger now.")
        status.postDelayed({ refreshStatus() }, 800)
    }

    private fun stop() {
        startService(Intent(this, BatteryLogService::class.java).setAction(BatteryLogService.ACTION_STOP))
        toast("Stopped.")
        status.postDelayed({ refreshStatus() }, 500)
    }

    private fun makeReport() {
        val csv = ReportGenerator.latestLog(this) ?: run { toast("No log found yet."); return }
        lastReport = ReportGenerator.generate(this, csv)
        report.text = lastReport
        toast("Report written next to the CSV.")
    }

    private fun shareReport() {
        if (lastReport.isBlank()) { toast("Generate a report first."); return }
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, lastReport),
            "Share report"
        ))
    }

    private fun installModule() = Thread {
        if (!RootHelper.hasRoot()) { uiToast("No root (su) — boot module needs Magisk."); return@Thread }
        val r = MagiskModule.install(this)
        uiToast(if (r.ok && r.out.contains("INSTALL_OK"))
            "Boot module installed — active after next reboot." else "Install failed: ${r.out.take(200)}")
    }.start()

    private fun removeModule() = Thread {
        if (!RootHelper.hasRoot()) { uiToast("No root (su)."); return@Thread }
        val r = MagiskModule.remove()
        uiToast(if (r.ok) "Boot module removed (takes effect next reboot)." else "Remove failed: ${r.out.take(200)}")
    }.start()

    // ---- helpers ----
    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            .also { it.topMargin = dp(4) }
        setOnClickListener { onClick() }
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(4), dp(4), 0, dp(2))
    }

    private fun divider() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1))
            .also { it.topMargin = dp(12); it.bottomMargin = dp(8) }
        setBackgroundColor(Color.parseColor("#3A3A3A"))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun uiToast(s: String) = runOnUiThread { toast(s) }
}
