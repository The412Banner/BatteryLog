package com.the412banner.batterylog

import android.content.Context
import java.io.File

/** One logged sample row (shared by the report and the history graph). */
data class Sample(
    val epoch: Long, val uptime: Long, val cap: Int, val status: String,
    val plugged: String, val vMv: Int, val iUa: Int, val chgUah: Int,
    val energyNwh: Long, val tempDecideg: Int, val health: String
)

object LogStore {

    /** All CSV logs, newest first. */
    fun logs(ctx: Context): List<File> =
        BatteryLogService.logDir(ctx).listFiles { f -> f.name.endsWith(".csv") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun latest(ctx: Context): File? = logs(ctx).firstOrNull()

    fun parse(csv: File): List<Sample> =
        csv.readLines().drop(1).mapNotNull { ln ->
            val p = ln.split(",")
            if (p.size < 12) return@mapNotNull null
            runCatching {
                Sample(
                    epoch = p[0].toLong(), uptime = p[2].toLong(), cap = p[3].toInt(),
                    status = p[4], plugged = p[5], vMv = p[6].toInt(), iUa = p[7].toInt(),
                    chgUah = p[8].toInt(), energyNwh = p[9].toLongOrNull() ?: 0L,
                    tempDecideg = p[10].toInt(), health = p[11]
                )
            }.getOrNull()
        }
}
