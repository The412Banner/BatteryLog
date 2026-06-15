package com.the412banner.batterylog

import android.content.Context

/**
 * Optional boot-persistence: installs a tiny Magisk module whose service.sh
 * launches a self-contained shell sampler at every boot. It writes CSV rows in
 * the SAME column layout as [BatteryLogService] into the app's own log dir, so
 * [ReportGenerator] analyses boot-logged runs identically. Requires root.
 *
 * NOTE: the shell bodies below use the sentinel __D__ for a literal '$' to avoid
 * Kotlin raw-string interpolation (this module targets Kotlin 1.9).
 */
object MagiskModule {

    const val ID = "batterylog_boot"
    private const val DIR = "/data/adb/modules/$ID"

    fun isInstalled(): Boolean =
        RootHelper.run("test -d $DIR && echo INSTALLED").out.contains("INSTALLED")

    fun install(ctx: Context): RootHelper.Result {
        val logDir = BatteryLogService.logDir(ctx).absolutePath
        val header = BatteryLogService.CSV_HEADER

        // sampler.sh — same CSV columns as the app; 'boot' marks the plugged field.
        val sampler = """
            #!/system/bin/sh
            B=/sys/class/power_supply/battery
            DIR="__LOGDIR__"
            mkdir -p "__D__DIR"
            F="__D__DIR/boot___D__(date +%Y%m%d_%H%M%S).csv"
            echo "__HEADER__" > "__D__F"
            sync
            while true; do
              ms=__D__((__D__(date +%s)*1000))
              iso=__D__(date +%Y-%m-%dT%H:%M:%S)
              up=__D__(awk '{printf "%d",__D__1*1000}' /proc/uptime 2>/dev/null)
              cap=__D__(cat __D__B/capacity 2>/dev/null)
              st=__D__(cat __D__B/status 2>/dev/null | tr 'A-Z' 'a-z')
              v=__D__(cat __D__B/voltage_now 2>/dev/null); vmv=__D__(( __D__{v:-0} / 1000 ))
              cur=__D__(cat __D__B/current_now 2>/dev/null)
              chg=__D__(cat __D__B/charge_counter 2>/dev/null)
              en=__D__(cat __D__B/energy_now 2>/dev/null || echo 0)
              tmp=__D__(cat __D__B/temp 2>/dev/null)
              hl=__D__(cat __D__B/health 2>/dev/null | tr 'A-Z' 'a-z')
              echo "__D__ms,__D__iso,__D__up,__D__cap,__D__st,boot,__D__vmv,__D__cur,__D__chg,__D__en,__D__tmp,__D__hl" >> "__D__F"
              sync
              sleep 30
            done
        """.trimIndent()
            .replace("__LOGDIR__", logDir)
            .replace("__HEADER__", header)
            .replace("__D__", "$")

        val moduleProp = """
            id=$ID
            name=BatteryLog Boot Sampler
            version=1.0.0
            versionCode=1
            author=The412Banner
            description=Auto-starts battery CSV logging at every boot for the BatteryLog app.
        """.trimIndent()

        val service = """
            #!/system/bin/sh
            MODDIR=__D__{0%/*}
            until [ "__D__(getprop sys.boot_completed)" = "1" ]; do sleep 2; done
            sleep 20
            setsid sh "__D__MODDIR/sampler.sh" >/dev/null 2>&1 &
        """.trimIndent().replace("__D__", "$")

        // Quoted heredocs ('EOF') so the installing root shell expands nothing.
        val script = buildString {
            append("set -e\n")
            append("mkdir -p $DIR\n")
            append("cat > $DIR/module.prop <<'EOF'\n").append(moduleProp).append("\nEOF\n")
            append("cat > $DIR/service.sh <<'EOF'\n").append(service).append("\nEOF\n")
            append("cat > $DIR/sampler.sh <<'EOF'\n").append(sampler).append("\nEOF\n")
            append("chmod 0755 $DIR/service.sh $DIR/sampler.sh\n")
            append("echo INSTALL_OK\n")
        }
        return RootHelper.run(script)
    }

    fun remove(): RootHelper.Result =
        RootHelper.run("rm -rf $DIR && echo REMOVED")
}
