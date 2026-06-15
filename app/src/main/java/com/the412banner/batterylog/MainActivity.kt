package com.the412banner.batterylog

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
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

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var report: TextView
    private var lastReport: String = ""

    private val notifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* logging works regardless; notification is just nicer */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "BatteryLog"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        })
        status = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(8), 0, dp(12))
        }
        root.addView(status)

        root.addView(button("Start monitoring") { start() })
        root.addView(button("Stop monitoring") { stop() })
        root.addView(button("Generate report") { makeReport() })
        root.addView(button("Share report") { shareReport() })
        root.addView(divider())
        root.addView(button("Install boot module (Magisk)") { installModule() })
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
        ) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        refresh()
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val running = BatteryLogService.running
        val path = BatteryLogService.currentLogPath ?: "(none yet)"
        status.text = buildString {
            append(if (running) "● MONITORING\n" else "○ idle\n")
            append("Log: $path\n")
            append("Logs dir: ${BatteryLogService.logDir(this@MainActivity).absolutePath}")
        }
    }

    private fun start() {
        val i = Intent(this, BatteryLogService::class.java)
        ContextCompat.startForegroundService(this, i)
        toast("Monitoring started — you can disconnect the charger now.")
        status.postDelayed({ refresh() }, 800)
    }

    private fun stop() {
        startService(Intent(this, BatteryLogService::class.java)
            .setAction(BatteryLogService.ACTION_STOP))
        toast("Stopped.")
        status.postDelayed({ refresh() }, 500)
    }

    private fun makeReport() {
        val csv = ReportGenerator.latestLog(this)
        if (csv == null) { toast("No log found yet."); return }
        lastReport = ReportGenerator.generate(this, csv)
        report.text = lastReport
        toast("Report written next to the CSV.")
    }

    private fun shareReport() {
        if (lastReport.isBlank()) { toast("Generate a report first."); return }
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, lastReport),
            "Share report"
        ))
    }

    private fun installModule() {
        Thread {
            if (!RootHelper.hasRoot()) { uiToast("No root (su) — boot module needs Magisk."); return@Thread }
            val r = MagiskModule.install(this)
            uiToast(if (r.ok && r.out.contains("INSTALL_OK"))
                "Boot module installed — active after next reboot." else "Install failed: ${r.out.take(200)}")
        }.start()
    }

    private fun removeModule() {
        Thread {
            if (!RootHelper.hasRoot()) { uiToast("No root (su)."); return@Thread }
            val r = MagiskModule.remove()
            uiToast(if (r.ok) "Boot module removed (takes effect next reboot)." else "Remove failed: ${r.out.take(200)}")
        }.start()
    }

    // helpers
    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

    private fun divider() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).also {
            it.topMargin = dp(8); it.bottomMargin = dp(8)
        }
        setBackgroundColor(Color.GRAY)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun uiToast(s: String) = runOnUiThread { toast(s) }
}
