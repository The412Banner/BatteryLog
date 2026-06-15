package com.the412banner.batterylog

import java.io.DataOutputStream

/** Minimal `su` runner for the optional Magisk-module feature. */
object RootHelper {

    data class Result(val ok: Boolean, val out: String)

    /** True if a `su` binary is present and grants a root shell. */
    fun hasRoot(): Boolean = runCatching {
        val p = ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        out == "0"
    }.getOrDefault(false)

    /** Pipes [script] into a root shell; returns combined output + success. */
    fun run(script: String): Result = runCatching {
        val p = ProcessBuilder("su").redirectErrorStream(true).start()
        DataOutputStream(p.outputStream).use { os ->
            os.writeBytes(script)
            os.writeBytes("\nexit\n")
            os.flush()
        }
        val out = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        Result(code == 0, out)
    }.getOrElse { Result(false, it.message ?: "su failed") }
}
