package com.the412banner.batterylog

import android.app.Application
import android.os.BatteryManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.the412banner.batterylog.ui.Metric
import com.the412banner.batterylog.ui.health.HealthActions
import com.the412banner.batterylog.ui.health.HealthContent
import com.the412banner.batterylog.ui.health.HealthUi
import com.the412banner.batterylog.ui.health.StorageInfo
import com.the412banner.batterylog.ui.history.HistoryContent
import com.the412banner.batterylog.ui.history.HistoryUi
import com.the412banner.batterylog.ui.history.Range
import com.the412banner.batterylog.ui.history.WindowStats
import com.the412banner.batterylog.ui.now.NowActions
import com.the412banner.batterylog.ui.now.NowContent
import com.the412banner.batterylog.ui.now.NowUi
import com.the412banner.batterylog.ui.run.RunContent
import com.the412banner.batterylog.ui.run.RunUi
import com.the412banner.batterylog.ui.settings.SettingsActions
import com.the412banner.batterylog.ui.settings.SettingsContent
import com.the412banner.batterylog.ui.settings.UpdatesBody
import com.the412banner.batterylog.ui.theme.BatteryLogTheme
import com.the412banner.batterylog.update.AppRelease
import com.the412banner.batterylog.update.AppVersion
import com.the412banner.batterylog.update.GhAsset
import com.the412banner.batterylog.update.UpdateState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.sin

/** Renders every screen with made-up data into app/build/screenshots/ for visual review (CI artifact). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h1600dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = System.currentTimeMillis()

    /** 24 h: discharge 98→22 %, charge to 92 %, discharge to 64 % now. One sample per minute. */
    private val day: SampleSet = run {
        val b = SampleSet.Builder()
        var pct = 98.0
        var q = 8_200_000.0
        val start = now - 24 * 3_600_000L
        for (m in 0 until 24 * 60) {
            val t = start + m * 60_000L
            if (m in 600..640) continue // device off for 40 minutes
            val charging = m in 760..880
            val load = 1.0 + 0.6 * sin(m / 37.0) + if (m in 300..420) 1.4 else 0.0
            val ua: Int
            if (charging) {
                pct = (pct + 0.6).coerceAtMost(92.0); q += 85_000.0 * 0.6; ua = (2_800_000 - (pct - 20) * 20_000).toInt()
            } else {
                val d = 0.075 * load
                pct = (pct - d).coerceAtLeast(2.0); q -= 84_000.0 * d; ua = -(900_000 * load).toInt()
            }
            val mv = (3_550 + pct * 7.5 + if (charging) 120 else -40 * load).toInt()
            val temp = (290 + 60 * load + if (charging) 55 else 0).toInt()
            b.add(
                t, pct.toInt(), mv, ua, q.toInt(), temp,
                if (charging) SampleSet.ST_CHARGING else SampleSet.ST_DISCHARGING,
                if (charging) SampleSet.PL_AC else SampleSet.PL_UNPLUGGED,
            )
        }
        b.build()
    }

    private val battery = BatteryInfo(
        time = now, capacityPct = 64, statusCode = BatteryManager.BATTERY_STATUS_DISCHARGING, pluggedCode = 0,
        healthCode = BatteryManager.BATTERY_HEALTH_GOOD, technology = "Li-ion", voltageMv = 4_031, tempDeci = 334,
        currentMa = -1_612.0, avgCurrentMa = -1_480.0, chargeNowMah = 5_300,
    )

    private val live = (0 until 60).map { i ->
        battery.copy(time = now - (60 - i) * 2_000L, currentMa = -1_500.0 - 300 * sin(i / 6.0), voltageMv = 4_030 + (i % 5), tempDeci = 330 + i / 10)
    }

    private val rates = Analysis.Rates(drainPctPerH = 9.4, chargePctPerH = 36.0, drainWatts = 5.8, chargeWatts = 14.2, batteryHours = 30.0, chargeHours = 4.0)

    private val logInfo = LogInfo(
        path = "/sdcard/BatteryLog/logs/battery_log_20261115_180000.csv", name = "battery_log_20261115_180000.csv",
        size = 120_000, modified = now, kind = LogKind.App, start = day.first, end = day.last, count = day.size,
        startCap = 98, endCap = 64, minCap = 22, maxCap = 98, drained = 104, charged = 70, minMv = 3_600, maxTemp = 420, firstUptime = 30_000,
    )

    private fun shot(name: String, dark: Boolean = true, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            BatteryLogTheme(if (dark) ThemeMode.Dark else ThemeMode.Light, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        compose.mainClock.advanceTimeBy(3_000)
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    private fun nowUi() = NowUi(
        battery = battery,
        estimates = Estimates.Result(false, 64, 412, null, rates),
        service = BatteryLogService.State(true, true, true, logInfo.path, now - 3 * 3_600_000L, 360, 30),
        recent = day,
        live = live,
        session = Analysis.currentSession(day),
        model = "Pocket FIT Elite",
    )

    @Test fun now_dark() = shot("01-now-dark") { NowContent(nowUi(), NowActions()) }

    @Test fun now_light() = shot("02-now-light", dark = false) { NowContent(nowUi(), NowActions()) }

    @Test fun now_charging() = shot("03-now-charging") {
        val b = battery.copy(capacityPct = 47, statusCode = BatteryManager.BATTERY_STATUS_CHARGING, pluggedCode = BatteryManager.BATTERY_PLUGGED_AC, currentMa = 3_400.0, voltageMv = 4_120)
        NowContent(
            nowUi().copy(
                battery = b, live = live.map { it.copy(currentMa = 3_300.0, statusCode = BatteryManager.BATTERY_STATUS_CHARGING) },
                estimates = Estimates.Result(true, 47, null, 58, rates), service = BatteryLogService.State(true, true, false),
                needsFilesAccess = true,
            ),
            NowActions(),
        )
    }

    private fun historyUi(metric: Metric) = HistoryUi(
        range = Range.H24, metric = metric, loading = false, set = day, bands = Analysis.chargingBands(day),
        stats = WindowStats.of(day),
        runs = listOf(
            logInfo,
            logInfo.copy(path = "b", name = "boot_20261114_185545.csv", kind = LogKind.Boot, start = now - 40 * 3_600_000L, end = now - 25 * 3_600_000L, startCap = 31, endCap = 88, drained = 40, charged = 97, minCap = 12, maxCap = 100),
            logInfo.copy(path = "c", start = now - 60 * 3_600_000L, end = now - 52 * 3_600_000L, startCap = 100, endCap = 18, drained = 82, charged = 0, minCap = 18, maxCap = 100),
        ),
        activePath = logInfo.path,
    )

    @Test fun history_level() = shot("04-history-level") { HistoryContent(historyUi(Metric.Level), {}, {}, {}) }

    @Test fun history_power() = shot("05-history-power") { HistoryContent(historyUi(Metric.Power), {}, {}, {}) }

    @Test fun history_temp_light() = shot("06-history-temp-light", dark = false) { HistoryContent(historyUi(Metric.Temp), {}, {}, {}) }

    @Test fun run_detail() = shot("07-run") {
        val report = Analysis.report(logInfo, day, null)
        RunContent(RunUi(false, logInfo, day, report, Analysis.chargingBands(day)), {}, {}, {}, {})
    }

    @Test fun health() = shot("08-health") {
        val stats = LifetimeStats.Stats(
            mapOf(
                "cycle_count" to "12", "charge_full" to "8987000", "charge_full_design" to "8400000",
                "voltage_ocv" to "4156917", "voltage_max" to "4380000", "technology" to "Li-ion",
                "model_name" to "BELOVED_8200MAH_37W", "temp" to "240",
            ),
            usedRoot = false,
        )
        HealthContent(
            HealthUi(
                stats = stats,
                measured = Analysis.Measured(8_310, 3),
                charge = ChargeControl.State(ChargeControl.SpeedKind.Current, 8_400_000, 8_400_000, false, 70, 80, true),
                root = true,
                module = MagiskModule.Status(true, true, true, true, "6308"),
                storage = StorageInfo(true, "/storage/emulated/0/BatteryLog/logs", 13, 3_560_000),
                message = null,
            ),
            HealthActions(),
        )
    }

    @Test fun settings() = shot("09-settings") {
        SettingsContent(AppSettings(chargeAlarm = true, tempAlert = true), serviceActive = false, updateAvailable = true, actions = SettingsActions())
    }

    @Test fun updates() = shot("10-updates") {
        val r = AppRelease(
            tag = "v2.0.0-pre2", version = AppVersion.parse("2.0.0-pre2")!!, title = "BatteryLog v2.0.0-pre2",
            notes = "## What's new\n- **Smoother graphs** with press-and-drag readout\n- Built-in updater\n- Fixes for single-cell batteries",
            publishedAt = "2026-11-16T23:00:00Z", pageUrl = "https://github.com/The412Banner/BatteryLog/releases",
            apk = GhAsset("BatteryLog-v2.0.0-pre2.apk", "https://x", 4_200_000), checksum = null, prerelease = true,
        )
        UpdatesBody(UpdateState.Downloading(r, 0.42f), AppSettings(includePrereleases = true), true, {}, {}, { _, _ -> }, {}, {}, {}, {})
    }
}
