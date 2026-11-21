package com.the412banner.batterylog.hud

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the HUD can show. */
@Serializable
enum class HudMetric(val label: String) {
    Fps("FPS"), Cpu("CPU %"), Gpu("GPU %"), GpuMhz("GPU MHz"),
    CpuTemp("CPU °"), GpuTemp("GPU °"), CaseTemp("Case °"), BattTemp("Battery °"),
    BattPct("Battery %"), Watts("Watts"), Clock("Clock"),
}

/** HUD look + which apps show it (and with which metrics). */
@Serializable
data class HudConfig(
    val enabled: Boolean = false,
    /** 0 top left, 1 top right, 2 bottom left, 3 bottom right. */
    val corner: Int = 0,
    /** Distance from that corner in px (changed by dragging). */
    val x: Int = 24,
    val y: Int = 24,
    /** 0 small, 1 medium, 2 large. */
    val size: Int = 1,
    /** Background darkness in %. */
    val opacity: Int = 60,
    val oneLine: Boolean = true,
    /** Locked = touches go through to the game; unlocked = the HUD can be dragged. */
    val locked: Boolean = false,
    val apps: Map<String, Set<HudMetric>> = emptyMap(),
) {
    companion object {
        val DEFAULT_METRICS: Set<HudMetric> = setOf(
            HudMetric.Fps, HudMetric.Cpu, HudMetric.Gpu, HudMetric.CpuTemp,
            HudMetric.CaseTemp, HudMetric.BattTemp, HudMetric.Watts,
        )
    }
}

object HudStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var prefs: SharedPreferences? = null
    private val _flow = MutableStateFlow(HudConfig())
    val flow: StateFlow<HudConfig> = _flow.asStateFlow()

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences("hud", Context.MODE_PRIVATE)
        prefs = p
        _flow.value = p.getString("config", null)?.let { runCatching { json.decodeFromString<HudConfig>(it) }.getOrNull() } ?: HudConfig()
    }

    fun current(): HudConfig = _flow.value

    @Synchronized
    fun update(f: (HudConfig) -> HudConfig) {
        val n = f(_flow.value)
        _flow.value = n
        prefs?.edit()?.putString("config", json.encodeToString(HudConfig.serializer(), n))?.apply()
    }
}
