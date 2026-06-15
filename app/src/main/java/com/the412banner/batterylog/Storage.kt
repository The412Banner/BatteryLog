package com.the412banner.batterylog

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File

/**
 * Decides WHERE logs and reports live so they survive an app uninstall/reinstall.
 *
 * Android deletes an app's getExternalFilesDir() (the old location) on uninstall,
 * which wiped every collected log. With All-files access we instead write to a
 * package-independent public folder — /sdcard/BatteryLog/logs — that the OS never
 * touches when the app is removed. The Magisk boot sampler (root) always writes
 * there too, so boot-persisted logs are safe regardless of the app's permission.
 *
 * Falls back to the old app-private dir when the permission isn't granted, so
 * logging never silently fails; readers scan both and the migration copies any
 * legacy logs into the safe folder once access is available.
 */
object Storage {

    /** Public, uninstall-proof folder. Same path the root boot sampler writes to. */
    fun publicDir(): File = File(Environment.getExternalStorageDirectory(), "BatteryLog/logs")

    /** Absolute path handed to the root shell sampler at install time. */
    fun publicPath(): String = publicDir().absolutePath

    /** Old app-private location (wiped on uninstall) — fallback / migration source. */
    fun legacyDir(ctx: Context): File = File(ctx.getExternalFilesDir(null), "logs")

    /** True when we may write plain files to [publicDir] (All-files access on R+). */
    fun canUsePublic(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /** Active write dir: the safe public folder if we can, else the legacy fallback. */
    fun logDir(ctx: Context): File {
        if (canUsePublic()) {
            val p = publicDir()
            if (p.exists() || p.mkdirs()) return p
        }
        return legacyDir(ctx).apply { mkdirs() }
    }

    /** All dirs to scan when listing logs (public first so it wins on name clashes). */
    fun readDirs(ctx: Context): List<File> =
        listOf(publicDir(), legacyDir(ctx)).filter { it.isDirectory }

    /**
     * Copy any logs/reports left in the legacy app dir into the safe public folder
     * (one-time, idempotent). Runs only when public access is available.
     */
    fun migrateLegacy(ctx: Context): Int {
        if (!canUsePublic()) return 0
        val pub = publicDir()
        if (!pub.exists() && !pub.mkdirs()) return 0
        val leg = legacyDir(ctx)
        if (!leg.isDirectory) return 0
        var moved = 0
        leg.listFiles()?.forEach { src ->
            val dst = File(pub, src.name)
            if (!dst.exists()) runCatching { src.copyTo(dst); moved++ }
        }
        return moved
    }
}
