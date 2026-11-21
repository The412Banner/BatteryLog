package com.the412banner.batterylog.apps

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.the412banner.batterylog.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Shizuku: shell rights through Wireless debugging, so per-app monitoring works without root. */
object ShizukuAccess {
    enum class State { NotRunning, NeedsPermission, Ready }

    private const val REQUEST = 4127
    private val _state = MutableStateFlow(State.NotRunning)
    val state: StateFlow<State> = _state.asStateFlow()
    @Volatile private var listening = false

    /** Call once from Application.onCreate. */
    fun init() {
        if (listening) return
        listening = true
        runCatching {
            Shizuku.addBinderReceivedListenerSticky { refresh() }
            Shizuku.addBinderDeadListener { refresh() }
            Shizuku.addRequestPermissionResultListener { _, _ -> refresh() }
        }
        refresh()
    }

    fun refresh(): State {
        val s = runCatching {
            when {
                !Shizuku.pingBinder() || Shizuku.isPreV11() -> State.NotRunning
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> State.Ready
                else -> State.NeedsPermission
            }
        }.getOrDefault(State.NotRunning)
        _state.value = s
        return s
    }

    /** Shows Shizuku's "allow BatteryLog" dialog. */
    fun requestPermission() {
        runCatching { if (Shizuku.pingBinder()) Shizuku.requestPermission(REQUEST) }
    }
}

/** [PrivShell] backed by a Shizuku user service ([ShellUserService]). */
class ShizukuShell(private val ctx: Context) : PrivShell {
    override val label = "Shizuku"
    @Volatile private var service: IShellService? = null
    private var conn: ServiceConnection? = null
    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(ctx.packageName, ShellUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(false)
            .version(BuildConfig.VERSION_CODE)
    }

    @Synchronized
    override fun run(script: String): String? {
        if (ShizukuAccess.refresh() != ShizukuAccess.State.Ready) return null
        val s = service ?: connect() ?: return null
        return runCatching { s.run(script) }.getOrElse { service = null; null }
    }

    private fun connect(): IShellService? {
        val latch = CountDownLatch(1)
        val c = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder != null && binder.pingBinder()) service = IShellService.Stub.asInterface(binder)
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) { service = null }
        }
        conn = c
        if (runCatching { Shizuku.bindUserService(args, c) }.isFailure) return null
        latch.await(10, TimeUnit.SECONDS)
        return service
    }

    @Synchronized
    override fun close() {
        conn?.let { c -> runCatching { Shizuku.unbindUserService(args, c, true) } }
        conn = null
        service = null
    }
}
