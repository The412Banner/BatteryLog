package com.the412banner.batterylog.apps

import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** Started by Shizuku in its own process (shell uid with Wireless debugging, root uid in root mode). */
class ShellUserService : IShellService.Stub() {
    override fun run(script: String): String {
        val p = ProcessBuilder("sh").redirectErrorStream(true).start()
        val watchdog = Thread {
            try { Thread.sleep(30_000); p.destroy() } catch (_: InterruptedException) {}
        }.apply { isDaemon = true; start() }
        p.outputStream.bufferedWriter().use { it.write(script); it.write("\nexit\n") }
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor(5, TimeUnit.SECONDS)
        watchdog.interrupt()
        return out
    }

    override fun destroy() {
        exitProcess(0)
    }
}
