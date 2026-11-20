package com.the412banner.batterylog.apps

import java.io.BufferedReader
import java.io.Writer

/** One long-lived `su` shell for frequent sampling (spawning su every few seconds is slow and noisy). */
class RootShell {
    private var proc: Process? = null
    private var writer: Writer? = null
    private var reader: BufferedReader? = null
    private var counter = 0L

    @Synchronized
    fun run(script: String): String? {
        repeat(2) {
            val out = runCatching { runOnce(script) }.getOrNull()
            if (out != null) return out
            close()
        }
        return null
    }

    private fun runOnce(script: String): String? {
        if (proc?.isAlive != true) start()
        val w = writer ?: return null
        val r = reader ?: return null
        val marker = "__BL_END_${++counter}__"
        w.write(script)
        w.write("\necho $marker\n")
        w.flush()
        val sb = StringBuilder()
        while (true) {
            val line = r.readLine() ?: return null
            if (line == marker) break
            sb.append(line).append('\n')
        }
        return sb.toString()
    }

    private fun start() {
        val p = ProcessBuilder("su").redirectErrorStream(true).start()
        proc = p
        writer = p.outputStream.bufferedWriter()
        reader = p.inputStream.bufferedReader()
    }

    @Synchronized
    fun close() {
        runCatching { writer?.write("exit\n"); writer?.flush() }
        runCatching { proc?.destroy() }
        proc = null; writer = null; reader = null
    }
}
