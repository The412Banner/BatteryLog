package com.the412banner.batterylog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Resumes Auto/Monitoring after a reboot (if enabled in Settings) and always after an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val s = BatteryLogApp.get(context).settings.current()
        if (s.mode == LogMode.Off) return
        val resume = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> s.resumeAfterRestart
            Intent.ACTION_MY_PACKAGE_REPLACED -> true
            else -> false
        }
        if (resume) BatteryLogService.restore(context)
    }
}
