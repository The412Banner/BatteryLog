package com.the412banner.batterylog

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Serializable
enum class LogKind { App, Boot, Other }

/** Cached summary of one CSV log (kept in files/log-index.json so the run list opens instantly). */
@Serializable
data class LogInfo(
    val path: String,
    val name: String,
    val size: Long,
    val modified: Long,
    val kind: LogKind,
    val start: Long,
    val end: Long,
    val count: Int,
    val startCap: Int,
    val endCap: Int,
    val minCap: Int,
    val maxCap: Int,
    val drained: Int,
    val charged: Int,
    val minMv: Int,
    val maxTemp: Int,
    val firstUptime: Long,
) {
    val durationMs: Long get() = (end - start).coerceAtLeast(0)
}

/**
 * All log reading goes through here, off the main thread. Parsed files are cached in memory
 * (keyed by path + size + mtime, so a growing live log is re-read and finished logs are not).
 */
class LogRepository(private val context: Context) {

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val indexFile = File(context.filesDir, "log-index.json")
    private var index: MutableMap<String, LogInfo>? = null

    private data class Cached(val size: Long, val modified: Long, val set: SampleSet, val firstUptime: Long)
    private val parsed = object : LinkedHashMap<String, Cached>(16, 0.75f, true) {}
    private var parsedRows = 0L

    private val _version = MutableStateFlow(0L)
    /** Bumped when logs are deleted, so screens reload. */
    val version: StateFlow<Long> = _version.asStateFlow()

    fun files(): List<File> =
        Storage.readDirs(context)
            .flatMap { it.listFiles { f -> f.isFile && f.name.endsWith(".csv") }?.toList() ?: emptyList() }
            .distinctBy { it.name }

    /** Summaries of every log, newest first. */
    suspend fun index(): List<LogInfo> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val map = index ?: loadIndex().also { index = it }
            val current = files()
            val seen = HashSet<String>()
            var changed = false
            for (f in current) {
                seen += f.absolutePath
                val old = map[f.absolutePath]
                if (old == null || old.size != f.length() || old.modified != f.lastModified()) {
                    val p = parseLocked(f)
                    map[f.absolutePath] = Analysis.summarize(f, p.set, p.firstUptimeMs)
                    changed = true
                }
            }
            if (map.keys.retainAll(seen)) changed = true
            if (changed) saveIndex(map)
            map.values.sortedByDescending { it.start }
        }
    }

    suspend fun load(file: File): SampleSet = withContext(Dispatchers.IO) { mutex.withLock { parseLocked(file).set } }

    /**
     * Every sample between [from] and [to] across all logs, merged and sorted. Boot-sampler rows that
     * fall inside a period the app itself was logging are dropped, so the same time isn't counted twice.
     */
    suspend fun window(from: Long, to: Long = Long.MAX_VALUE): SampleSet {
        val infos = index().filter { it.count > 0 && it.end >= from && it.start <= to }
        if (infos.isEmpty()) return SampleSet.EMPTY
        return withContext(Dispatchers.Default) {
            val appSpans = infos.filter { it.kind == LogKind.App }.map { it.start to it.end }.sortedBy { it.first }
            class Seg(val set: SampleSet, val a: Int, val b: Int) { val t0 get() = set.t[a] }
            val segs = ArrayList<Seg>()
            for (info in infos) {
                val set = load(File(info.path))
                if (set.isEmpty) continue
                val a0 = set.indexAtOrAfter(from)
                val b0 = if (to == Long.MAX_VALUE) set.size else set.indexAtOrAfter(to + 1)
                if (a0 >= b0) continue
                if (info.kind != LogKind.Boot || appSpans.isEmpty()) {
                    segs += Seg(set, a0, b0)
                    continue
                }
                var runStart = -1
                for (i in a0 until b0) {
                    val tt = set.t[i]
                    val covered = appSpans.any { tt >= it.first && tt <= it.second }
                    if (!covered && runStart < 0) runStart = i
                    if (covered && runStart >= 0) { segs += Seg(set, runStart, i); runStart = -1 }
                }
                if (runStart >= 0) segs += Seg(set, runStart, b0)
            }
            segs.sortBy { it.t0 }
            val total = segs.sumOf { it.b - it.a }
            val out = SampleSet.Builder(total.coerceAtLeast(16))
            var sorted = true
            for (s in segs) {
                for (i in s.a until s.b) {
                    if (s.set.t[i] < out.lastTime()) sorted = false
                    out.addFrom(s.set, i)
                }
            }
            val merged = out.build()
            if (sorted) dedupe(merged) else dedupe(sortByTime(merged))
        }
    }

    suspend fun delete(info: LogInfo): Boolean = withContext(Dispatchers.IO) {
        val f = File(info.path)
        val ok = !f.exists() || f.delete()
        File(f.parentFile, f.nameWithoutExtension + "_report.txt").delete()
        mutex.withLock { parsed.remove(info.path)?.let { parsedRows -= it.set.size } }
        _version.value++
        ok
    }

    /** Deletes logs whose last row is older than [days] days; the log being written right now is kept. */
    suspend fun deleteOlderThan(days: Int, keepPath: String?): Int {
        if (days <= 0) return 0
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        var n = 0
        for (info in index()) {
            if (info.path == keepPath) continue
            val last = if (info.end > 0) info.end else info.modified
            if (last < cutoff && delete(info)) n++
        }
        return n
    }

    /** Writes every CSV log into one zip in the cache folder (for sharing). */
    suspend fun zipAll(): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "BatteryLog-logs-${System.currentTimeMillis() / 1000}.zip")
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            for (f in files().sortedBy { it.name }) {
                zip.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        out
    }

    fun totalBytes(): Long = files().sumOf { it.length() }

    // ---- internals (call with mutex held) ----

    private fun parseLocked(f: File): CsvLog.Parsed {
        val key = f.absolutePath
        val size = f.length()
        val mod = f.lastModified()
        parsed[key]?.let { if (it.size == size && it.modified == mod) return CsvLog.Parsed(it.set, it.firstUptime) }
        val p = CsvLog.parse(f)
        parsed.remove(key)?.let { parsedRows -= it.set.size }
        parsed[key] = Cached(size, mod, p.set, p.firstUptimeMs)
        parsedRows += p.set.size
        // Keep roughly 800k rows (~20 MB) in memory; drop the least recently used files first.
        val it = parsed.entries.iterator()
        while (parsedRows > 800_000 && parsed.size > 1 && it.hasNext()) {
            val e = it.next()
            if (e.key == key) continue
            parsedRows -= e.value.set.size
            it.remove()
        }
        return p
    }

    private fun loadIndex(): MutableMap<String, LogInfo> = runCatching {
        json.decodeFromString(ListSerializer(LogInfo.serializer()), indexFile.readText())
            .associateBy { it.path }.toMutableMap()
    }.getOrElse { HashMap() }

    private fun saveIndex(map: Map<String, LogInfo>) {
        runCatching {
            val tmp = File(indexFile.path + ".tmp")
            tmp.writeText(json.encodeToString(ListSerializer(LogInfo.serializer()), map.values.toList()))
            tmp.renameTo(indexFile)
        }
    }

    private fun sortByTime(s: SampleSet): SampleSet {
        val order = (0 until s.size).sortedBy { s.t[it] }
        val b = SampleSet.Builder(s.size.coerceAtLeast(16))
        for (i in order) b.addFrom(s, i)
        return b.build()
    }

    /** Drops rows closer than 2 s to the previous kept row (two loggers writing at the same moment). */
    private fun dedupe(s: SampleSet): SampleSet {
        if (s.size < 2) return s
        var dup = 0
        for (i in 1 until s.size) if (s.t[i] - s.t[i - 1] < 2_000) dup++
        if (dup == 0) return s
        val b = SampleSet.Builder(s.size)
        var last = Long.MIN_VALUE
        for (i in 0 until s.size) {
            if (last != Long.MIN_VALUE && s.t[i] - last < 2_000) continue
            b.addFrom(s, i)
            last = s.t[i]
        }
        return b.build()
    }

    companion object {
        fun kindOf(name: String): LogKind = when {
            name.startsWith("battery_log_") -> LogKind.App
            name.startsWith("boot_") -> LogKind.Boot
            else -> LogKind.Other
        }
    }
}
