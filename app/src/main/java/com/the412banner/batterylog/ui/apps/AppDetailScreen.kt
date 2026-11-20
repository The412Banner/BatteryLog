package com.the412banner.batterylog.ui.apps

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.the412banner.batterylog.BatteryLogApp
import com.the412banner.batterylog.Fmt
import com.the412banner.batterylog.TempUnit
import com.the412banner.batterylog.apps.AppStore
import com.the412banner.batterylog.apps.AppSummary
import com.the412banner.batterylog.apps.MRow
import com.the412banner.batterylog.apps.Stat
import com.the412banner.batterylog.apps.TRow
import com.the412banner.batterylog.ui.components.AppCard
import com.the412banner.batterylog.ui.components.MiniStat
import com.the412banner.batterylog.ui.components.SectionCard
import com.the412banner.batterylog.ui.components.Series
import com.the412banner.batterylog.ui.components.TimeChart
import com.the412banner.batterylog.ui.components.rememberChartState
import com.the412banner.batterylog.ui.theme.LocalChartColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class Detail(val summary: AppSummary?, val timeline: List<TRow>, val minutes: List<MRow>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailScreen(pkg: String, back: () -> Unit) {
    val ctx = LocalContext.current
    val app = BatteryLogApp.get(ctx)
    val settings by app.settings.flow.collectAsStateWithLifecycle()
    var period by rememberSaveable { mutableStateOf(Period.Today) }
    var data by remember { mutableStateOf<Detail?>(null) }
    LaunchedEffect(pkg, period) {
        data = null
        val r = period.range()
        data = withContext(Dispatchers.IO) {
            runCatching {
                val tl = AppStore.readTimeline(ctx, r.first, r.last)
                val mins = AppStore.readMinutes(ctx, r.first, r.last, pkg)
                val sum = AppStore.summaries(ctx, r.first, r.last).firstOrNull { it.pkg == pkg }
                Detail(sum, tl, mins)
            }.getOrDefault(Detail(null, emptyList(), emptyList()))
        }
    }
    val label = remember(pkg) { appLabel(ctx, pkg) }
    val icon = remember(pkg) { appIcon(ctx, pkg) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (icon != null) Image(icon, null, Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(label, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                            Text(pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        val d = data
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Choice(Period.entries.map { it.label }, period.ordinal) { period = Period.entries[it] }
            if (d == null) {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Column
            }
            val s = d.summary
            if (s == null) {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    Text("Nothing recorded for this app in this period", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                return@Column
            }
            Summary(s, settings.tempUnit)
            Charts(pkg, d, settings.tempUnit)
            if (settings.showAllMetrics) AllMetrics(pkg, d, settings.tempUnit)
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun tempStr(st: Stat, unit: TempUnit): String =
    if (st.n == 0) "—" else Fmt.tempShort(st.avg.toDouble(), unit) + " / " + Fmt.tempShort(st.max.toDouble(), unit)

@Composable
private fun Summary(s: AppSummary, unit: TempUnit) {
    val c = LocalChartColors.current
    SectionCard("While in use", Icons.Rounded.Bolt, tint = c.power) {
        Grid(
            listOf(
                "Screen time" to Fmt.duration(s.fgMs),
                "Energy" to "%.2f Wh".format(s.energyWh),
                "Power avg / peak" to if (s.drawW.n == 0) "—" else "%.2f / %.2f W".format(s.drawW.avg, s.drawW.max),
                "GPU load avg / peak" to if (s.gpuPct.n == 0) "—" else "%.0f / %.0f %%".format(s.gpuPct.avg, s.gpuPct.max),
                "App CPU avg / peak" to if (s.fgCpuPct.n == 0) "—" else "%.1f / %.1f %%".format(s.fgCpuPct.avg, s.fgCpuPct.max),
                "CPU time (all)" to Fmt.duration(s.cpuMs),
            ),
        )
    }
    SectionCard("Temperatures (avg / max)", Icons.Rounded.Thermostat, tint = c.temp) {
        Grid(
            listOf(
                "CPU" to tempStr(s.cpuC, unit),
                "GPU" to tempStr(s.gpuC, unit),
                "Skin" to tempStr(s.skinC, unit),
                "Battery" to tempStr(s.battC, unit),
            ),
        )
    }
    SectionCard("Memory", Icons.Rounded.Memory, tint = c.level) {
        Grid(
            listOf(
                "RAM avg" to mb(s.rssAvgKb),
                "RAM peak" to mb(s.rssMaxKb),
                "GPU memory peak" to mb(s.gpuMaxKb),
            ),
        )
    }
}

@Composable
private fun Grid(items: List<Pair<String, String>>) {
    items.chunked(2).forEachIndexed { i, row ->
        if (i > 0) Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            row.forEach { (l, v) -> MiniStat(l, v, Modifier.weight(1f)) }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

private fun seriesOf(rows: List<TRow>, pkg: String, f: (TRow) -> Float): Series {
    val t = LongArray(rows.size) { rows[it].time }
    val v = FloatArray(rows.size) { i -> val r = rows[i]; if (r.fg == pkg) f(r) else Float.NaN }
    return Series(t, v)
}

@Composable
private fun Charts(pkg: String, d: Detail, unit: TempUnit) {
    val c = LocalChartColors.current
    val chart = rememberChartState()
    val cores = remember { Runtime.getRuntime().availableProcessors().coerceAtLeast(1) }
    fun tc(v: Float) = if (v.isNaN()) v else if (unit == TempUnit.F) v * 9f / 5f + 32f else v
    val tempAxis: (Float) -> String = { "%.0f°".format(it) }
    val gap = 90_000L
    val charts = listOf(
        ChartSpec("Power while in use", c.power, seriesOf(d.timeline, pkg) { it.drawW.toFloat() }, { "%.1fW".format(it) }, 1f),
        ChartSpec("GPU load", c.current, seriesOf(d.timeline, pkg) { it.gpuPct }, { "%.0f%%".format(it) }, 10f),
        ChartSpec("App CPU", c.voltage, seriesOf(d.timeline, pkg) { it.fgCpuPct }, { "%.0f%%".format(it) }, 5f),
        ChartSpec("CPU temperature", c.temp, seriesOf(d.timeline, pkg) { tc(it.cpuC) }, tempAxis, 4f),
        ChartSpec("GPU temperature", c.warn, seriesOf(d.timeline, pkg) { tc(it.gpuC) }, tempAxis, 4f),
        ChartSpec(
            "App CPU (all the time)", c.voltage,
            Series(LongArray(d.minutes.size) { d.minutes[it].minute }, FloatArray(d.minutes.size) { d.minutes[it].cpuMs * 100f / (60_000f * cores) }),
            { "%.0f%%".format(it) }, 5f,
        ),
        ChartSpec(
            "App memory", c.level,
            Series(LongArray(d.minutes.size) { d.minutes[it].minute }, FloatArray(d.minutes.size) { d.minutes[it].rssAvgKb / 1024f }),
            { if (it >= 1024) "%.1fG".format(it / 1024f) else "%.0fM".format(it) }, 50f,
        ),
    )
    for (spec in charts) {
        if (spec.series.v.none { !it.isNaN() }) continue
        AppCard {
            Text(spec.title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            TimeChart(
                series = spec.series, state = chart, color = spec.color,
                axisFormat = spec.axis, valueFormat = spec.axis, minRange = spec.minRange, gapMs = gap,
                modifier = Modifier.fillMaxWidth().height(150.dp),
            )
        }
    }
}

private class ChartSpec(val title: String, val color: Color, val series: Series, val axis: (Float) -> String, val minRange: Float)

/** Every recorded metric for this app: min / avg / max tables and the raw samples (behind "Show all metrics"). */
@Composable
private fun AllMetrics(pkg: String, d: Detail, unit: TempUnit) {
    val c = LocalChartColors.current
    val fg = d.timeline.filter { it.fg == pkg }
    fun tc(v: Float) = if (v.isNaN()) v else if (unit == TempUnit.F) v * 9f / 5f + 32f else v
    val metrics: List<Pair<String, Stat>> = listOf(
        "Power draw (W)" to Stat.of(fg.asSequence().map { it.drawW.toFloat() }),
        "Battery (%)" to Stat.of(fg.asSequence().map { it.level.toFloat().takeIf { v -> v >= 0 } ?: Float.NaN }),
        "Device CPU (%)" to Stat.of(fg.asSequence().map { it.cpuPct }),
        "App CPU (%)" to Stat.of(fg.asSequence().map { it.fgCpuPct }),
        "GPU load (%)" to Stat.of(fg.asSequence().map { it.gpuPct }),
        "GPU clock (MHz)" to Stat.of(fg.asSequence().map { it.gpuMhz }),
        "CPU temp" to Stat.of(fg.asSequence().map { tc(it.cpuC) }),
        "GPU temp" to Stat.of(fg.asSequence().map { tc(it.gpuC) }),
        "Skin temp" to Stat.of(fg.asSequence().map { tc(it.skinC) }),
        "Memory temp" to Stat.of(fg.asSequence().map { tc(it.ddrC) }),
        "Battery temp" to Stat.of(fg.asSequence().map { tc(it.battC) }),
        "App RAM (MB)" to Stat.of(fg.asSequence().map { it.fgRssKb / 1024f }),
        "App GPU memory (MB)" to Stat.of(fg.asSequence().map { it.fgGpuKb / 1024f }),
        "CPU ms per minute (all)" to Stat.of(d.minutes.asSequence().map { it.cpuMs.toFloat() }),
        "RAM avg per minute (MB)" to Stat.of(d.minutes.asSequence().map { it.rssAvgKb / 1024f }),
        "RAM peak per minute (MB)" to Stat.of(d.minutes.asSequence().map { it.rssMaxKb / 1024f }),
        "GPU memory peak per minute (MB)" to Stat.of(d.minutes.asSequence().map { it.gpuMaxKb / 1024f }),
        "Energy per minute (mWh)" to Stat.of(d.minutes.asSequence().map { (it.fgEnergyMj / 3600.0).toFloat() }),
    )
    SectionCard("All metrics", Icons.Rounded.TableChart, tint = c.current, subtitle = "${fg.size} samples while in use · ${d.minutes.size} minutes") {
        Row(Modifier.fillMaxWidth()) {
            Text("Metric", Modifier.weight(1.6f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf("Min", "Avg", "Max").forEach {
                Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        metrics.forEach { (name, st) ->
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth()) {
                Text(name, Modifier.weight(1.6f), style = MaterialTheme.typography.bodySmall)
                listOf(st.min, st.avg, st.max).forEach { v ->
                    Text(if (st.n == 0) "—" else "%.1f".format(v), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    SectionCard("Samples", Icons.Rounded.Videocam, tint = c.voltage, subtitle = "Newest ${minOf(300, fg.size)} while in use") {
        val header = "time      W     CPU%  app%  GPU%  MHz   CPU°  GPU°  skin° batt° RAM MB GPU MB"
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Text(header, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            fg.takeLast(300).asReversed().forEach { r ->
                fun n(v: Float, w: Int, dec: Int = 0) = (if (v.isNaN()) "—" else "%.${dec}f".format(v)).padEnd(w)
                val line = Fmt.time(r.time).padEnd(10) + n(r.drawW.toFloat(), 6, 2) + n(r.cpuPct, 6) + n(r.fgCpuPct, 6) +
                    n(r.gpuPct, 6) + n(r.gpuMhz, 6) + n(tc(r.cpuC), 6) + n(tc(r.gpuC), 6) + n(tc(r.skinC), 6) +
                    n(tc(r.battC), 6) + n(r.fgRssKb / 1024f, 7) + n(r.fgGpuKb / 1024f, 6)
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}
