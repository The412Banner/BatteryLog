package com.the412banner.batterylog

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that samples the battery every [SAMPLE_INTERVAL_MS] and
 * appends a row to a CSV in the app's external files dir. No root required:
 * everything comes from BatteryManager + the sticky ACTION_BATTERY_CHANGED intent.
 *
 * Uses foregroundServiceType=specialUse (not dataSync) so Android 14 does NOT
 * impose its ~6h/day cap — a full 100%->0% discharge can take many hours.
 */
class BatteryLogService : Service() {

    companion object {
        const val SAMPLE_INTERVAL_MS = 30_000L
        const val CHANNEL_ID = "batterylog"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "com.the412banner.batterylog.STOP"

        /** Header written as the first line of every CSV. */
        const val CSV_HEADER =
            "epoch_ms,iso_time,uptime_ms,capacity_pct,status,plugged," +
            "voltage_mV,current_uA,charge_counter_uAh,energy_counter_nWh,temp_decideg,health"

        @Volatile var running = false
            private set
        @Volatile var currentLogPath: String? = null
            private set

        /** Directory where logs live. Stable across start/stop so the report can find them. */
        fun logDir(ctx: Context): File =
            File(ctx.getExternalFilesDir(null), "logs").apply { mkdirs() }
    }

    private lateinit var bm: BatteryManager
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private var wakeLock: PowerManager.WakeLock? = null
    private var logFile: File? = null

    private val sampler = object : Runnable {
        override fun run() {
            sampleOnce()
            handler.postDelayed(this, SAMPLE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        thread = HandlerThread("battery-sampler").apply { start() }
        handler = Handler(thread.looper)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_STICKY

        // Partial wakelock keeps the CPU alive so the 30s sampler keeps firing
        // even with the screen off / during doze; otherwise we'd get gaps.
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BatteryLog::sampler").apply {
            setReferenceCounted(false)
            acquire()
        }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        logFile = File(logDir(this), "battery_log_$stamp.csv").also {
            it.appendText(CSV_HEADER + "\n")
            currentLogPath = it.absolutePath
        }

        running = true
        startForeground(NOTIF_ID, buildNotification("starting…"))
        sampleOnce()
        handler.postDelayed(sampler, SAMPLE_INTERVAL_MS)
        return START_STICKY
    }

    private fun sampleOnce() {
        val f = logFile ?: return
        val now = System.currentTimeMillis()

        val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val voltageMv = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val tempDecideg = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val statusInt = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pluggedInt = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val healthInt = sticky?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1

        val capacity = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val currentUa = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val chargeUah = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val energyNwh = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)

        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date(now))
        val line = "$now,$iso,${android.os.SystemClock.elapsedRealtime()},$capacity," +
            "${statusName(statusInt)},${pluggedName(pluggedInt)},$voltageMv,$currentUa," +
            "$chargeUah,$energyNwh,$tempDecideg,${healthName(healthInt)}"

        runCatching { f.appendText(line + "\n") }
        // Force to disk each row so the tail survives a hard PMIC shut-off.
        runCatching { java.io.FileOutputStream(f, true).use { it.fd.sync() } }

        updateNotification(
            "$capacity%  ${"%.2f".format(voltageMv / 1000.0)}V  " +
            "${"%.0f".format(currentUa / 1000.0)}mA  ${"%.1f".format(tempDecideg / 10.0)}°C"
        )

        // Auto-stop when fully drained so the run ends cleanly at 0%.
        if (capacity in 0..0 && statusInt != BatteryManager.BATTERY_STATUS_CHARGING) {
            stopSelf()
        }
    }

    private fun statusName(s: Int) = when (s) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
        BatteryManager.BATTERY_STATUS_FULL -> "full"
        else -> "unknown"
    }

    private fun pluggedName(p: Int) = when (p) {
        BatteryManager.BATTERY_PLUGGED_AC -> "ac"
        BatteryManager.BATTERY_PLUGGED_USB -> "usb"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
        0 -> "unplugged"
        else -> "unknown"
    }

    private fun healthName(h: Int) = when (h) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
        BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
        BatteryManager.BATTERY_HEALTH_COLD -> "cold"
        else -> "unknown"
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL_ID, "Battery logging", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, BatteryLogService::class.java).setAction(ACTION_STOP)
        val stopPi = android.app.PendingIntent.getService(
            this, 0, stopIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle("BatteryLog running")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Stop", stopPi)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(sampler)
        runCatching { wakeLock?.release() }
        wakeLock = null
        thread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
