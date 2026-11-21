package com.the412banner.batterylog.apps

/** A shell with more rights than the app: root (`su`) or Shizuku (shell or root uid). */
interface PrivShell {
    /** Output of [script], or null when the shell is not available. */
    fun run(script: String): String?
    fun close()
    /** Shown to the user: "root" or "Shizuku". */
    val label: String
}
