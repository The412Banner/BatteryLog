package com.the412banner.batterylog

import java.io.DataOutputStream
import java.util.concurrent.TimeUnit

/** Minimal `su` runner for the root-only features (boot module, charge control, hidden sysfs nodes). */
object RootHelper {

    data class Result(val ok: Boolean, val out: String)

    @Volatile private var known: Boolean? = null

    /** Last result of [hasRoot] without spawning `su` (false until a check has run). */
    fun knownRoot(): Boolean = known == true

    /** True if `su` grants a root shell. Spawns `su` (may show the root manager's prompt), so call off the UI thread. */
    fun hasRoot(): Boolean {
        val r = exec(listOf("su", "-c", "id -u"), null, 15)
        val ok = r.ok && r.out.trim().lines().lastOrNull()?.trim() == "0"
        known = ok
        return ok
    }

    /** Cached [hasRoot]: only asks `su` the first time per process. */
    fun rootCached(): Boolean = known ?: hasRoot()

    /** Pipes [script] into a root shell; returns combined output + success. */
    fun run(script: String, timeoutSec: Long = 30): Result = exec(listOf("su"), script, timeoutSec)

    private fun exec(cmd: List<String>, stdin: String?, timeoutSec: Long): Result = runCatching {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        if (stdin != null) {
            DataOutputStream(p.outputStream).use { os ->
                os.writeBytes(stdin)
                os.writeBytes("\nexit\n")
                os.flush()
            }
        } else {
            p.outputStream.close()
        }
        val out = StringBuilder()
        val reader = Thread {
            runCatching { p.inputStream.bufferedReader().use { r -> out.append(r.readText()) } }
        }.apply { isDaemon = true; start() }
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return Result(false, "su timed out")
        }
        reader.join(2_000)
        Result(p.exitValue() == 0, synchronized(out) { out.toString() })
    }.getOrElse { Result(false, it.message ?: "su failed") }
}
