package com.the412banner.batterylog.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.the412banner.batterylog.AppSettings
import com.the412banner.batterylog.BatteryLogApp
import com.the412banner.batterylog.BatteryLogService
import com.the412banner.batterylog.BuildConfig
import com.the412banner.batterylog.Fmt
import com.the412banner.batterylog.TempUnit
import com.the412banner.batterylog.ThemeMode
import com.the412banner.batterylog.update.UpdateScheduler
import com.the412banner.batterylog.update.UpdateState
import com.the412banner.batterylog.ui.components.Pill
import com.the412banner.batterylog.ui.components.SectionCard
import com.the412banner.batterylog.ui.theme.LocalChartColors

class SettingsActions(
    val change: ((AppSettings) -> AppSettings) -> Unit = {},
    val openUpdates: () -> Unit = {},
    val openRepo: () -> Unit = {},
    val startAuto: () -> Unit = {},
)

@Composable
fun SettingsScreen(openUpdates: () -> Unit) {
    val context = LocalContext.current
    val app = BatteryLogApp.get(context)
    val settings by app.settings.flow.collectAsStateWithLifecycle()
    val service by BatteryLogService.state.collectAsStateWithLifecycle()
    val update by app.updates.state.collectAsStateWithLifecycle()
    SettingsContent(
        settings = settings,
        serviceActive = service.active,
        updateAvailable = update is UpdateState.Available,
        actions = SettingsActions(
            change = { f ->
                val before = app.settings.current()
                app.settings.update(f)
                val after = app.settings.current()
                if (before.autoUpdateCheck != after.autoUpdateCheck) runCatching { UpdateScheduler.schedule(context, after.autoUpdateCheck) }
            },
            openUpdates = openUpdates,
            openRepo = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${BuildConfig.UPDATE_REPO}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            },
            startAuto = { BatteryLogService.startAuto(context) },
        ),
    )
}

@Composable
fun SettingsContent(settings: AppSettings, serviceActive: Boolean, updateAvailable: Boolean, actions: SettingsActions) {
    val c = LocalChartColors.current
    val s = settings
    val set = actions.change
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 12.dp))

        SectionCard("Appearance", Icons.Rounded.Palette, tint = c.power) {
            Label("Theme")
            Segmented(ThemeMode.entries.map { it.name }, ThemeMode.entries.indexOf(s.theme)) { i -> set { it.copy(theme = ThemeMode.entries[i]) } }
            if (Build.VERSION.SDK_INT >= 31) {
                SwitchRow("Wallpaper colours", s.dynamicColor) { v -> set { it.copy(dynamicColor = v) } }
            }
            Spacer(Modifier.height(8.dp))
            Label("Temperature")
            Segmented(listOf("°C", "°F"), if (s.tempUnit == TempUnit.C) 0 else 1) { i -> set { it.copy(tempUnit = if (i == 0) TempUnit.C else TempUnit.F) } }
        }

        SectionCard("Logging", Icons.Rounded.Tune, tint = c.current) {
            Label("Record every")
            val choices = AppSettings.SAMPLE_CHOICES
            Segmented(choices.map { "$it s" }, choices.indexOf(s.sampleSeconds).coerceAtLeast(0)) { i -> set { it.copy(sampleSeconds = choices[i]) } }
            Spacer(Modifier.height(6.dp))
            SwitchRow("Resume after restart", s.resumeAfterRestart) { v -> set { it.copy(resumeAfterRestart = v) } }
            Spacer(Modifier.height(6.dp))
            Label("Delete logs older than")
            val keep = AppSettings.KEEP_CHOICES
            Segmented(keep.map { if (it == 0) "Never" else "${it}d" }, keep.indexOf(s.keepDays).coerceAtLeast(0)) { i -> set { it.copy(keepDays = keep[i]) } }
        }

        SectionCard("Alerts", Icons.Rounded.NotificationsActive, tint = c.warn, subtitle = "Work while Auto or Recording is on") {
            SwitchRow("Charge alarm", s.chargeAlarm, sub = "at ${s.chargeAlarmPct} %") { v -> set { it.copy(chargeAlarm = v) } }
            AnimatedVisibility(s.chargeAlarm) {
                IntSlider(s.chargeAlarmPct, 50..100, 5) { v -> set { it.copy(chargeAlarmPct = v) } }
            }
            SwitchRow("Low battery", s.lowAlert, sub = "at ${s.lowAlertPct} %") { v -> set { it.copy(lowAlert = v) } }
            AnimatedVisibility(s.lowAlert) {
                IntSlider(s.lowAlertPct, 5..50, 5) { v -> set { it.copy(lowAlertPct = v) } }
            }
            SwitchRow("Hot battery", s.tempAlert, sub = "at ${Fmt.temp(s.tempAlertC.toDouble(), s.tempUnit)}") { v -> set { it.copy(tempAlert = v) } }
            AnimatedVisibility(s.tempAlert) {
                IntSlider(s.tempAlertC, 35..60, 1) { v -> set { it.copy(tempAlertC = v) } }
            }
            AnimatedVisibility(s.anyAlert && !serviceActive) {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Nothing is running right now", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    FilledTonalButton(onClick = actions.startAuto) { Text("Turn on Auto") }
                }
            }
        }

        SectionCard("Per-app monitoring", Icons.Rounded.Apps, tint = c.temp, subtitle = "Needs root") {
            SwitchRow("Per-app monitoring", s.perApp) { v -> set { it.copy(perApp = v) } }
            AnimatedVisibility(s.perApp) {
                Column {
                    Spacer(Modifier.height(6.dp))
                    Label("Record every")
                    val pc = AppSettings.PER_APP_CHOICES
                    Segmented(pc.map { "$it s" }, pc.indexOf(s.perAppSeconds).coerceAtLeast(0)) { i -> set { it.copy(perAppSeconds = pc[i]) } }
                    Spacer(Modifier.height(6.dp))
                    SwitchRow("Show all metrics", s.showAllMetrics) { v -> set { it.copy(showAllMetrics = v) } }
                }
            }
        }

        SectionCard(
            "Updates", Icons.Rounded.SystemUpdate, tint = MaterialTheme.colorScheme.primary,
            onClick = actions.openUpdates,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (updateAvailable) Pill("New version")
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
                }
            },
        ) {
            SwitchRow("Check automatically", s.autoUpdateCheck) { v -> set { it.copy(autoUpdateCheck = v) } }
            SwitchRow("Include test builds", s.includePrereleases) { v -> set { it.copy(includePrereleases = v) } }
        }

        SectionCard("About", Icons.Rounded.Code, tint = MaterialTheme.colorScheme.secondary, onClick = actions.openRepo) {
            Text("BatteryLog ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})", style = MaterialTheme.typography.bodyMedium)
            Text("github.com/${BuildConfig.UPDATE_REPO}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun Segmented(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        labels.forEachIndexed { i, l ->
            SegmentedButton(
                selected = i == selected,
                onClick = { onSelect(i) },
                shape = SegmentedButtonDefaults.itemShape(i, labels.size),
                icon = {},
            ) { Text(l, maxLines = 1) }
        }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, sub: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun IntSlider(value: Int, range: IntRange, step: Int, onDone: (Int) -> Unit) {
    var pos by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val steps = ((range.last - range.first) / step - 1).coerceAtLeast(0)
    Slider(
        value = pos,
        onValueChange = { pos = it },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = steps,
        onValueChangeFinished = { onDone(kotlin.math.round(pos).toInt()) },
    )
}
