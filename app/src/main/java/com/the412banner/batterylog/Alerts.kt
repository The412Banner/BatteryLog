package com.the412banner.batterylog

import android.content.Context

/**
 * Charge alarm, low-battery and hot-battery alerts. Evaluated by [BatteryLogService] on every battery
 * change while it runs (Auto or Monitoring). Each alert fires once, then re-arms after the condition clears.
 */
class Alerts(private val context: Context) {
    private var chargeFired = false
    private var lowFired = false
    private var tempFiredAt = 0L

    fun check(b: BatteryInfo, s: AppSettings) {
        val pct = b.capacityPct
        if (pct < 0) return

        if (s.chargeAlarm) {
            if (b.onCharger && pct >= s.chargeAlarmPct && !chargeFired) {
                chargeFired = true
                Notifier.show(
                    context, Notifier.ID_CHARGE, Notifier.CH_ALERTS,
                    "Charged to $pct %",
                    "Your charge alarm is set to ${s.chargeAlarmPct} %. You can unplug now.",
                )
            }
            if (!b.onCharger || pct < s.chargeAlarmPct - 2) chargeFired = false
        }

        if (s.lowAlert) {
            if (!b.onCharger && pct <= s.lowAlertPct && !lowFired) {
                lowFired = true
                Notifier.show(context, Notifier.ID_LOW, Notifier.CH_ALERTS, "Battery at $pct %", "Plug in soon.")
            }
            if (b.onCharger || pct > s.lowAlertPct + 2) lowFired = false
        }

        if (s.tempAlert && !b.tempC.isNaN()) {
            val now = System.currentTimeMillis()
            if (b.tempC >= s.tempAlertC && now - tempFiredAt > 15 * 60_000L) {
                tempFiredAt = now
                Notifier.show(
                    context, Notifier.ID_TEMP, Notifier.CH_ALERTS,
                    "Battery is hot: ${Fmt.temp(b.tempC, s.tempUnit)}",
                    "It went over your ${Fmt.temp(s.tempAlertC.toDouble(), s.tempUnit)} alert.",
                )
            }
            if (b.tempC < s.tempAlertC - 2) tempFiredAt = 0L
        }
    }
}
