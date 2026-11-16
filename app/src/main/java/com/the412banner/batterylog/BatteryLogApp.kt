package com.the412banner.batterylog

import android.app.Application
import android.content.Context
import com.the412banner.batterylog.update.UpdateRepository
import com.the412banner.batterylog.update.UpdateScheduler

/** Process-wide singletons (settings, logs, updater). */
class BatteryLogApp : Application() {
    lateinit var settings: SettingsStore
        private set
    lateinit var logs: LogRepository
        private set
    lateinit var updates: UpdateRepository
        private set

    override fun onCreate() {
        super.onCreate()
        setup(this)
        Notifier.createChannels(this)
        updates.clearDownloads()
        runCatching { UpdateScheduler.schedule(this, settings.current().autoUpdateCheck) }
    }

    private fun setup(ctx: Context) {
        settings = SettingsStore(ctx)
        logs = LogRepository(ctx)
        updates = UpdateRepository(ctx, settings)
    }

    companion object {
        @Volatile private var fallback: BatteryLogApp? = null

        /**
         * The app object. Under tests (plain Application) a stand-in is built once so screens and
         * repositories still work.
         */
        fun get(ctx: Context): BatteryLogApp {
            val app = ctx.applicationContext
            if (app is BatteryLogApp) return app
            return fallback ?: synchronized(this) {
                fallback ?: BatteryLogApp().also { it.setup(app); fallback = it }
            }
        }
    }
}
