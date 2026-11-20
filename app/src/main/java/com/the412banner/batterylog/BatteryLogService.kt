package com.the412banner.batterylog

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that samples the battery every N seconds (Settings, default 30) into a CSV.
 * No root needed. foregroundServiceType=specialUse, not dataSync, so Android 14+ doesn't cap it at ~6 h/day.
 *
 * Modes:
 *  - MANUAL: samples right away into a fresh CSV until stopped (or 0 %).
 *  - AUTO: stays resident, opens a fresh discharge log when the charger is unplugged and closes it
 *    when plugged back in. The service is already running, so this isn't blocked by Android 14's
 *    "no foreground service from a background broadcast" rule.
 * The mode is saved, so a restart by Android, a reboot ([BootReceiver]) or an app update resumes it.
 * While running it also drives the battery alerts ([Alerts]).
 */
class BatteryLogService : Service() {

    data class State(
        val active: Boolean = false,
        val auto: Boolean = false,
        val recording: Boolean = false,
        val logPath: String? = null,
        val startedAt: Long = 0,
        val samples: Int = 0,
        val intervalSec: Int = 30,
    )

    companion object {
        const val ACTION_STOP = "com.the412banner.batterylog.STOP"
        const val ACTION_START_AUTO = "com.the412banner.batterylog.START_AUTO"
        const val ACTION_START_MANUAL = "com.the412banner.batterylog.START_MANUAL"
        const val ACTION_RESTORE = "com.the412banner.batterylog.RESTORE"

        /** First line of every CSV (the boot sampler writes the same columns). */
        const val CSV_HEADER =
            "epoch_ms,iso_time,uptime_ms,capacity_pct,status,plugged," +
                "voltage_mV,current_uA,charge_counter_uAh,energy_counter_nWh,temp_decideg,health"

        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state.asStateFlow()

        val running: Boolean get() = _state.value.recording
        val autoArmed: Boolean get() = _state.value.auto
        val currentLogPath: String? get() = _state.value.logPath

        fun logDir(ctx: Context): File = Storage.logDir(ctx)

        fun startAuto(ctx: Context) = start(ctx, ACTION_START_AUTO)
        fun startManual(ctx: Context) = start(ctx, ACTION_START_MANUAL)
        fun restore(ctx: Context) = start(ctx, ACTION_RESTORE)

        fun stop(ctx: Context) {
            BatteryLogApp.get(ctx).settings.update { it.copy(mode = LogMode.Off) }
            if (!_state.value.active) return
            runCatching { ctx.startService(Intent(ctx, BatteryLogService::class.java).setAction(ACTION_STOP)) }
        }

        private fun start(ctx: Context, action: String): Boolean = runCatching {
            ContextCompat.startForegroundService(ctx, Intent(ctx, BatteryLogService::class.java).setAction(action))
            true
        }.getOrDefault(false)
    }

    private lateinit var bm: BatteryManager
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var alerts: Alerts
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var logFile: File? = null
    private var foregroundStarted = false
    private var receiverRegistered = false
    private var lastNotifyAt = 0L
    private var lastCleanupAt = 0L

    private val settings get() = BatteryLogApp.get(this).settings

    private val sampler = object : Runnable {
        override fun run() {
            sampleOnce()
            handler.postDelayed(this, settings.current().sampleSeconds.coerceIn(5, 600) * 1000L)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_POWER_DISCONNECTED -> handler.post {
                    if (_state.value.auto) startLogging()
                    refreshNotification(force = true)
                }
                Intent.ACTION_POWER_CONNECTED -> handler.post {
                    if (_state.value.auto) stopLogging()
                    refreshNotification(force = true)
                }
                Intent.ACTION_BATTERY_CHANGED -> handler.post {
                    val b = BatteryInfo.read(this@BatteryLogService)
                    alerts.check(b, settings.current())
                    refreshNotification(b)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        thread = HandlerThread("battery-sampler").apply { start() }
        handler = Handler(thread.looper)
        alerts = Alerts(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent = Android restarted us after killing the process: resume the saved mode.
        val action = intent?.action ?: ACTION_RESTORE
        val mode = when (action) {
            ACTION_STOP -> LogMode.Off
            ACTION_START_AUTO -> LogMode.Auto
            ACTION_START_MANUAL -> LogMode.Manual
            ACTION_RESTORE -> settings.current().mode
            else -> LogMode.Manual
        }
        // startForegroundService() requires startForeground() even when we stop right away.
        ensureForeground()
        when (mode) {
            LogMode.Off -> {
                // Also covers the notification's Stop button, which comes straight here.
                if (action == ACTION_STOP) settings.update { it.copy(mode = LogMode.Off) }
                fullStop()
                return START_NOT_STICKY
            }
            LogMode.Auto -> handler.post { startAuto() }
            LogMode.Manual -> handler.post { startManual(fresh = action != ACTION_RESTORE || !_state.value.recording) }
        }
        return START_STICKY
    }

    // ---- modes (run on the sampler thread) ----

    private fun startManual(fresh: Boolean) {
        settings.update { it.copy(mode = LogMode.Manual) }
        _state.value = _state.value.copy(active = true, auto = false)
        registerReceivers()
        if (fresh && _state.value.recording) stopLogging()
        startLogging()
        refreshNotification(force = true)
        cleanupIfDue()
    }

    private fun startAuto() {
        settings.update { it.copy(mode = LogMode.Auto) }
        _state.value = _state.value.copy(active = true, auto = true)
        registerReceivers()
        if (isUnplugged()) startLogging() else stopLogging()
        refreshNotification(force = true)
        cleanupIfDue()
    }

    private fun fullStop() {
        handler.post {
            unregisterReceivers()
            stopLogging()
            _state.value = State()
            // Detach: per-app monitoring may still be using the shared notification.
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            ServiceNotification.refreshOrCancel(this)
            foregroundStarted = false
            stopSelf()
        }
    }

    // ---- logging ----

    private fun startLogging() {
        if (_state.value.recording) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BatteryLog::sampler").apply {
            setReferenceCounted(false)
            acquire()
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = File(logDir(this), "battery_log_$stamp.csv")
        runCatching { f.appendText(CSV_HEADER + "\n") }
        logFile = f
        _state.value = _state.value.copy(
            recording = true, logPath = f.absolutePath, startedAt = System.currentTimeMillis(), samples = 0,
            intervalSec = settings.current().sampleSeconds,
        )
        handler.removeCallbacks(sampler)
        handler.post(sampler)
    }

    private fun stopLogging() {
        handler.removeCallbacks(sampler)
        logFile = null
        runCatching { wakeLock?.release() }
        wakeLock = null
        _state.value = _state.value.copy(recording = false, logPath = null, samples = 0)
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
        val line = "$now,$iso,${SystemClock.elapsedRealtime()},$capacity," +
            "${statusName(statusInt)},${pluggedName(pluggedInt)},$voltageMv,$currentUa," +
            "$chargeUah,$energyNwh,$tempDecideg,${healthName(healthInt)}\n"
        // Synced to disk on every row so the tail survives a hard shut-off.
        runCatching {
            FileOutputStream(f, true).use { out ->
                out.write(line.toByteArray())
                out.fd.sync()
            }
        }
        _state.value = _state.value.copy(samples = _state.value.samples + 1, intervalSec = settings.current().sampleSeconds)

        if (capacity == 0 && statusInt != BatteryManager.BATTERY_STATUS_CHARGING) {
            if (_state.value.auto) stopLogging() else {
                settings.update { it.copy(mode = LogMode.Off) }
                fullStop()
            }
        }
        refreshNotification()
    }

    private fun cleanupIfDue() {
        val days = settings.current().keepDays
        val now = System.currentTimeMillis()
        if (days <= 0 || now - lastCleanupAt < 6 * 3_600_000L) return
        lastCleanupAt = now
        val keep = _state.value.logPath
        scope.launch { runCatching { BatteryLogApp.get(this@BatteryLogService).logs.deleteOlderThan(days, keep) } }
    }

    // ---- receivers ----

    private fun registerReceivers() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    private fun unregisterReceivers() {
        if (!receiverRegistered) return
        runCatching { unregisterReceiver(receiver) }
        receiverRegistered = false
    }

    private fun isUnplugged(): Boolean {
        val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1) == 0
    }

    // ---- notification ----

    private fun ensureForeground() {
        if (foregroundStarted) return
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notifier.ID_SERVICE, buildNotification(BatteryInfo.read(this)), type)
        foregroundStarted = true
    }

    private fun refreshNotification(b: BatteryInfo? = null, force: Boolean = false) {
        if (!foregroundStarted) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotifyAt < 10_000) return
        lastNotifyAt = now
        val info = b ?: BatteryInfo.read(this)
        runCatching {
            getSystemService(android.app.NotificationManager::class.java).notify(Notifier.ID_SERVICE, buildNotification(info))
        }
    }

    private fun buildNotification(b: BatteryInfo): Notification = ServiceNotification.build(this, b)


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

    override fun onDestroy() {
        unregisterReceivers()
        handler.removeCallbacks(sampler)
        runCatching { wakeLock?.release() }
        _state.value = State()
        scope.cancel()
        thread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
