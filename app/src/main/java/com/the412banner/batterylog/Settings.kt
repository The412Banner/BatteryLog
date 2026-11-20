package com.the412banner.batterylog

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { System, Light, Dark, Black }
enum class TempUnit { C, F }

/** What the logging service should be doing; persisted so it can be restored after a reboot or an update. */
enum class LogMode { Off, Auto, Manual }

data class AppSettings(
    val theme: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = false,
    val tempUnit: TempUnit = TempUnit.C,
    val sampleSeconds: Int = 30,
    val resumeAfterRestart: Boolean = true,
    val chargeAlarm: Boolean = false,
    val chargeAlarmPct: Int = 80,
    val lowAlert: Boolean = false,
    val lowAlertPct: Int = 15,
    val tempAlert: Boolean = false,
    val tempAlertC: Int = 45,
    val keepDays: Int = 0,
    val autoUpdateCheck: Boolean = true,
    val includePrereleases: Boolean = false,
    val skippedVersion: String? = null,
    val lastUpdateCheck: Long = 0,
    val mode: LogMode = LogMode.Off,
    /** Per-app monitoring through root (own foreground service). */
    val perApp: Boolean = false,
    val perAppSeconds: Int = 5,
    /** Show every recorded metric (full tables) on the app detail screen. */
    val showAllMetrics: Boolean = false,
) {
    val anyAlert: Boolean get() = chargeAlarm || lowAlert || tempAlert

    companion object {
        val SAMPLE_CHOICES = listOf(15, 30, 60, 120)
        val KEEP_CHOICES = listOf(0, 30, 90, 180, 365)
        val PER_APP_CHOICES = listOf(2, 5, 10, 30)
    }
}

/** SharedPreferences-backed settings, exposed as a StateFlow. One instance per process (see [BatteryLogApp]). */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _flow = MutableStateFlow(read())
    val flow: StateFlow<AppSettings> = _flow.asStateFlow()

    fun current(): AppSettings = _flow.value

    @Synchronized
    fun update(change: (AppSettings) -> AppSettings) {
        val next = change(_flow.value)
        if (next == _flow.value) return
        write(next)
        _flow.value = next
    }

    private fun read(): AppSettings {
        val d = AppSettings()
        fun <E : Enum<E>> enumOf(key: String, values: Array<E>, def: E): E =
            prefs.getString(key, null)?.let { name -> values.firstOrNull { it.name == name } } ?: def
        return AppSettings(
            theme = enumOf("theme", ThemeMode.values(), d.theme),
            dynamicColor = prefs.getBoolean("dynamicColor", d.dynamicColor),
            tempUnit = enumOf("tempUnit", TempUnit.values(), d.tempUnit),
            sampleSeconds = prefs.getInt("sampleSeconds", d.sampleSeconds),
            resumeAfterRestart = prefs.getBoolean("resumeAfterRestart", d.resumeAfterRestart),
            chargeAlarm = prefs.getBoolean("chargeAlarm", d.chargeAlarm),
            chargeAlarmPct = prefs.getInt("chargeAlarmPct", d.chargeAlarmPct),
            lowAlert = prefs.getBoolean("lowAlert", d.lowAlert),
            lowAlertPct = prefs.getInt("lowAlertPct", d.lowAlertPct),
            tempAlert = prefs.getBoolean("tempAlert", d.tempAlert),
            tempAlertC = prefs.getInt("tempAlertC", d.tempAlertC),
            keepDays = prefs.getInt("keepDays", d.keepDays),
            autoUpdateCheck = prefs.getBoolean("autoUpdateCheck", d.autoUpdateCheck),
            // Test builds offer test builds by default so a -preN install can update to the next one.
            includePrereleases = prefs.getBoolean("includePrereleases", BuildConfig.VERSION_NAME.contains('-')),
            skippedVersion = prefs.getString("skippedVersion", null),
            lastUpdateCheck = prefs.getLong("lastUpdateCheck", 0L),
            mode = enumOf("mode", LogMode.values(), d.mode),
            perApp = prefs.getBoolean("perApp", d.perApp),
            perAppSeconds = prefs.getInt("perAppSeconds", d.perAppSeconds),
            showAllMetrics = prefs.getBoolean("showAllMetrics", d.showAllMetrics),
        )
    }

    private fun write(s: AppSettings) {
        prefs.edit()
            .putString("theme", s.theme.name)
            .putBoolean("dynamicColor", s.dynamicColor)
            .putString("tempUnit", s.tempUnit.name)
            .putInt("sampleSeconds", s.sampleSeconds)
            .putBoolean("resumeAfterRestart", s.resumeAfterRestart)
            .putBoolean("chargeAlarm", s.chargeAlarm)
            .putInt("chargeAlarmPct", s.chargeAlarmPct)
            .putBoolean("lowAlert", s.lowAlert)
            .putInt("lowAlertPct", s.lowAlertPct)
            .putBoolean("tempAlert", s.tempAlert)
            .putInt("tempAlertC", s.tempAlertC)
            .putInt("keepDays", s.keepDays)
            .putBoolean("autoUpdateCheck", s.autoUpdateCheck)
            .putBoolean("includePrereleases", s.includePrereleases)
            .putString("skippedVersion", s.skippedVersion)
            .putLong("lastUpdateCheck", s.lastUpdateCheck)
            .putString("mode", s.mode.name)
            .putBoolean("perApp", s.perApp)
            .putInt("perAppSeconds", s.perAppSeconds)
            .putBoolean("showAllMetrics", s.showAllMetrics)
            .apply()
    }
}
