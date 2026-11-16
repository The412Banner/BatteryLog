package com.the412banner.batterylog.ui.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.rounded.ElectricBolt
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.the412banner.batterylog.Analysis
import com.the412banner.batterylog.BatteryLogApp
import com.the412banner.batterylog.BatteryLogService
import com.the412banner.batterylog.ChargeControl
import com.the412banner.batterylog.Fmt
import com.the412banner.batterylog.LifetimeStats
import com.the412banner.batterylog.MagiskModule
import com.the412banner.batterylog.RootHelper
import com.the412banner.batterylog.Storage
import com.the412banner.batterylog.TempUnit
import com.the412banner.batterylog.ui.components.AppCard
import com.the412banner.batterylog.ui.components.GaugeRing
import com.the412banner.batterylog.ui.components.InfoRow
import com.the412banner.batterylog.ui.components.MiniStat
import com.the412banner.batterylog.ui.components.PulseDot
import com.the412banner.batterylog.ui.components.SectionCard
import com.the412banner.batterylog.ui.run.shareFile
import com.the412banner.batterylog.ui.theme.LocalChartColors
import com.the412banner.batterylog.ui.theme.NumberStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StorageInfo(
    val publicOk: Boolean = false,
    val dir: String = "",
    val count: Int = 0,
    val bytes: Long = 0,
)

data class HealthUi(
    val stats: LifetimeStats.Stats? = null,
    val measured: Analysis.Measured? = null,
    val charge: ChargeControl.State? = null,
    val root: Boolean? = null,
    val module: MagiskModule.Status? = null,
    val storage: StorageInfo = StorageInfo(),
    val tempUnit: TempUnit = TempUnit.C,
    val cells: Int = 1,
    val busy: Boolean = false,
    val message: String? = null,
)

class HealthViewModel(private val app: BatteryLogApp) : ViewModel() {
    private val _ui = MutableStateFlow(HealthUi(tempUnit = app.settings.current().tempUnit))
    val ui: StateFlow<HealthUi> = _ui.asStateFlow()

    init { refresh(full = true) }

    fun refresh(full: Boolean = false) = viewModelScope.launch {
        val stats = withContext(Dispatchers.IO) { LifetimeStats.read(fresh = true) }
        val charge = withContext(Dispatchers.IO) { ChargeControl.read() }
        val cells = withContext(Dispatchers.IO) { LifetimeStats.cells() }
        val storage = withContext(Dispatchers.IO) { storage() }
        _ui.update { it.copy(stats = stats, charge = charge, storage = storage, tempUnit = app.settings.current().tempUnit, cells = cells) }
        if (full) {
            val measured = runCatching { Analysis.measuredCapacity(app.logs.window(System.currentTimeMillis() - 90 * 86_400_000L)) }.getOrNull()
            _ui.update { it.copy(measured = measured) }
        }
        val root = withContext(Dispatchers.IO) { RootHelper.rootCached() }
        val module = if (root) withContext(Dispatchers.IO) { MagiskModule.status() } else null
        val charge2 = if (root) withContext(Dispatchers.IO) { ChargeControl.read() } else charge
        _ui.update { it.copy(root = root, module = module, charge = charge2) }
    }

    private fun storage(): StorageInfo {
        val files = app.logs.files()
        return StorageInfo(Storage.canUsePublic(), Storage.logDir(app).absolutePath, files.size, files.sumOf { it.length() })
    }

    private fun act(label: String, block: suspend () -> String) = viewModelScope.launch {
        _ui.update { it.copy(busy = true, message = null) }
        val msg = runCatching { block() }.getOrElse { "$label failed: ${it.message}" }
        _ui.update { it.copy(busy = false, message = msg) }
        refresh()
    }

    fun setLimit(pct: Int) = act("Charge limit") {
        val ok = withContext(Dispatchers.IO) { ChargeControl.setLimit(pct) }
        if (ok) (if (pct >= 100) "Charge limit off" else "Charging now stops at $pct %") else "The kernel didn't keep that value"
    }

    fun setSpeed(value: Int) = act("Charge speed") {
        val st = _ui.value.charge ?: return@act "Not supported"
        val ok = withContext(Dispatchers.IO) { ChargeControl.setSpeed(st, value) }
        if (ok) "Charge speed set" else "The kernel didn't keep that value"
    }

    fun installModule() = act("Install") {
        val r = withContext(Dispatchers.IO) { MagiskModule.install(app) }
        if (r.ok && r.out.contains("INSTALL_OK")) "Boot module installed. It starts after the next reboot." else "Install failed: ${r.out.take(160)}"
    }

    fun removeModule() = act("Remove") {
        val r = withContext(Dispatchers.IO) { MagiskModule.remove() }
        if (r.ok) "Boot module removed (gone after the next reboot)" else "Remove failed: ${r.out.take(160)}"
    }

    fun deleteOlderThan(days: Int) = act("Clean up") {
        val n = app.logs.deleteOlderThan(days, BatteryLogService.currentLogPath)
        "Deleted $n log${if (n == 1) "" else "s"} older than $days days"
    }

    suspend fun zipAll() = app.logs.zipAll()

    fun clearMessage() = _ui.update { it.copy(message = null) }
}

class HealthActions(
    val setLimit: (Int) -> Unit = {},
    val setSpeed: (Int) -> Unit = {},
    val installModule: () -> Unit = {},
    val removeModule: () -> Unit = {},
    val deleteOlderThan: (Int) -> Unit = {},
    val shareAll: () -> Unit = {},
    val grantFiles: () -> Unit = {},
)

@Composable
fun HealthScreen(grantFiles: () -> Unit) {
    val context = LocalContext.current
    val app = BatteryLogApp.get(context)
    val vm: HealthViewModel = viewModel { HealthViewModel(app) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    var sharing by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(sharing) {
        if (sharing) {
            runCatching { vm.zipAll() }.onSuccess { shareFile(context, it, "application/zip") }
            sharing = false
        }
    }
    HealthContent(
        ui,
        HealthActions(
            setLimit = { vm.setLimit(it) },
            setSpeed = { vm.setSpeed(it) },
            installModule = { vm.installModule() },
            removeModule = { vm.removeModule() },
            deleteOlderThan = { vm.deleteOlderThan(it) },
            shareAll = { sharing = true },
            grantFiles = grantFiles,
        ),
    )
    ui.message?.let { msg ->
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(3_500)
            vm.clearMessage()
        }
    }
}

@Composable
fun HealthContent(ui: HealthUi, actions: HealthActions) {
    val c = LocalChartColors.current
    var cleanup by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Battery health", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 12.dp))
        ui.message?.let {
            AppCard(color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }

        val s = ui.stats
        val soh = s?.sohPct
        AppCard {
            GaugeRing(
                fraction = ((soh ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f),
                color = when {
                    soh == null -> MaterialTheme.colorScheme.outline
                    soh < 70 -> c.bad
                    soh < 85 -> c.warn
                    else -> c.level
                },
                modifier = Modifier.fillMaxWidth().height(220.dp),
                stroke = 14.dp,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(soh?.let { "%.0f".format(it) } ?: "—", style = MaterialTheme.typography.displayMedium)
                    Text("% health", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MiniStat("Full now", s?.fullMah?.let { "%,d mAh".format(it) } ?: "—")
                MiniStat("Design", s?.designMah?.let { "%,d mAh".format(it) } ?: "—")
                MiniStat("Cycles", s?.cycleCount?.toString() ?: "—")
            }
            Spacer(Modifier.height(12.dp))
            InfoRow(
                "Measured from your logs",
                ui.measured?.let { "%,d mAh (%d run%s)".format(it.fullMah, it.runs, if (it.runs == 1) "" else "s") } ?: "needs a ≥ 15 % discharge",
            )
            s?.lostMah?.let { lost -> if (lost > 0) InfoRow("Capacity lost", "%,d mAh".format(lost), valueColor = c.drain) }
        }

        SectionCard("Battery details", Icons.Rounded.Info, tint = MaterialTheme.colorScheme.secondary) {
            InfoRow("Model", s?.model ?: "—")
            s?.manufacturer?.let { InfoRow("Maker", it) }
            InfoRow("Technology", s?.technology ?: "—")
            InfoRow("Cells", ui.cells.let { "${it}S (${if (it == 1) "single cell" else "$it in series"})" })
            InfoRow("Charge voltage", s?.vmaxMv?.let { Fmt.volts(it) } ?: "—")
            InfoRow("Resting voltage", s?.ocvMv?.let { Fmt.volts(it) } ?: "—")
            InfoRow("Temperature", s?.tempC?.let { Fmt.temp(it, ui.tempUnit) } ?: "—")
            if (s?.usedRoot == true) {
                Text("Some values read with root", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        ChargeCard(ui, actions)
        ModuleCard(ui, actions)

        SectionCard("Log storage", Icons.Rounded.Folder, tint = c.voltage) {
            InfoRow("Folder", ui.storage.dir.removePrefix("/storage/emulated/0"))
            InfoRow("Logs", "${ui.storage.count} · ${Fmt.bytes(ui.storage.bytes)}")
            InfoRow(
                "Survives reinstall",
                if (ui.storage.publicOk) "Yes" else "No",
                valueColor = if (ui.storage.publicOk) c.level else c.warn,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!ui.storage.publicOk) FilledTonalButton(onClick = actions.grantFiles) { Text("Allow file access") }
                FilledTonalButton(onClick = actions.shareAll, enabled = ui.storage.count > 0) { Text("Share all logs") }
                OutlinedButton(onClick = { cleanup = true }, enabled = ui.storage.count > 0) { Text("Delete old logs") }
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    if (cleanup) {
        var days by remember { mutableIntStateOf(90) }
        AlertDialog(
            onDismissRequest = { cleanup = false },
            title = { Text("Delete old logs") },
            text = {
                Column {
                    Text("Delete logs whose last entry is older than:", style = MaterialTheme.typography.bodyMedium)
                    listOf(30, 90, 180, 365).forEach { d ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = days == d, onClick = { days = d })
                            Text("$d days")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { cleanup = false; actions.deleteOlderThan(days) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { cleanup = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ChargeCard(ui: HealthUi, actions: HealthActions) {
    val st = ui.charge ?: return
    if (!st.supported) return
    val c = LocalChartColors.current
    val root = ui.root == true
    SectionCard(
        "Charging",
        Icons.Rounded.ElectricBolt,
        tint = c.charging,
        subtitle = if (root) "Root: changes are read back to confirm" else "Changing these needs root",
    ) {
        if (st.endThreshold != null) {
            val end = st.endThreshold
            InfoRow(
                "Charge limit",
                if (end >= 100) "Off (charges to 100 %)" else "Stops at $end %" + (st.startThreshold?.let { ", resumes at $it %" } ?: ""),
            )
            if (st.limitWritable && root) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(100, 90, 85, 80).forEach { p ->
                        FilterChip(
                            selected = end == p,
                            onClick = { actions.setLimit(p) },
                            enabled = !ui.busy,
                            label = { Text(if (p == 100) "Off" else "$p %") },
                        )
                    }
                }
            }
        }
        val max = st.speedMax
        val value = st.speedValue
        if (st.speedKind != ChargeControl.SpeedKind.None && max != null && value != null) {
            Spacer(Modifier.height(6.dp))
            val label = when (st.speedKind) {
                ChargeControl.SpeedKind.Current -> if (value >= max) "${Fmt.amps(value)} (full)" else "${Fmt.amps(value)} of ${Fmt.amps(max)}"
                else -> if (value == 0) "Full speed" else "Slowed, level $value of $max"
            }
            InfoRow("Charge speed", label)
            if (!st.speedWritable) {
                Text("Set by the system on this device", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (root) {
                var pos by remember(value) {
                    mutableFloatStateOf(
                        if (st.speedKind == ChargeControl.SpeedKind.Current) value.toFloat() else (max - value).toFloat(),
                    )
                }
                val range = if (st.speedKind == ChargeControl.SpeedKind.Current) st.minCurrentUa.toFloat()..max.toFloat() else 0f..max.toFloat()
                Slider(
                    value = pos.coerceIn(range),
                    onValueChange = { pos = it },
                    valueRange = range,
                    enabled = !ui.busy,
                    onValueChangeFinished = {
                        val v = if (st.speedKind == ChargeControl.SpeedKind.Current) {
                            ((pos / 100_000f).toInt() * 100_000)
                        } else {
                            max - pos.toInt()
                        }
                        actions.setSpeed(v)
                    },
                )
                Row {
                    Text("Slower, cooler", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text("Faster", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ModuleCard(ui: HealthUi, actions: HealthActions) {
    val c = LocalChartColors.current
    val m = ui.module
    val (text, color, active) = when {
        ui.root == null -> Triple("Checking…", MaterialTheme.colorScheme.outline, false)
        ui.root == false -> Triple("Needs root (Magisk)", MaterialTheme.colorScheme.outline, false)
        m == null || !m.installed -> Triple("Not installed", MaterialTheme.colorScheme.outline, false)
        !m.enabled -> Triple("Installed but turned off in Magisk", c.warn, false)
        m.running -> Triple("Running (pid ${m.pid})", c.level, true)
        else -> Triple("Installed, starts after a reboot", c.warn, false)
    }
    SectionCard(
        "Boot logger",
        Icons.Rounded.RestartAlt,
        tint = c.voltage,
        subtitle = "Logs from every boot, even before you open the app",
        trailing = { PulseDot(color, active) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium.merge(NumberStyle), color = color, modifier = Modifier.weight(1f))
        }
        if (ui.root == true) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (m?.installed == true) {
                    OutlinedButton(onClick = actions.removeModule, enabled = !ui.busy) { Text("Remove") }
                    FilledTonalButton(onClick = actions.installModule, enabled = !ui.busy) { Text("Reinstall") }
                } else {
                    FilledTonalButton(onClick = actions.installModule, enabled = !ui.busy) { Text("Install") }
                }
            }
        }
    }
}
