package com.the412banner.batterylog.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.the412banner.batterylog.AppSettings
import com.the412banner.batterylog.BatteryLogApp
import com.the412banner.batterylog.BuildConfig
import com.the412banner.batterylog.Fmt
import com.the412banner.batterylog.update.AppRelease
import com.the412banner.batterylog.update.UpdateInstaller
import com.the412banner.batterylog.update.UpdateState
import com.the412banner.batterylog.ui.components.InfoRow
import com.the412banner.batterylog.ui.components.MarkdownLite
import com.the412banner.batterylog.ui.components.Pill
import com.the412banner.batterylog.ui.components.SectionCard
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant

@Composable
fun UpdatesScreen(back: () -> Unit) {
    val context = LocalContext.current
    val app = BatteryLogApp.get(context)
    val state by app.updates.state.collectAsStateWithLifecycle()
    val settings by app.settings.flow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        if (state is UpdateState.Idle || state is UpdateState.UpToDate || state is UpdateState.Failed) app.updates.check()
    }
    // Re-checked on every resume: the "install unknown apps" switch is flipped in Android's settings.
    var canInstall by remember { mutableStateOf(UpdateInstaller.canInstall(context)) }
    LifecycleResumeEffect(Unit) {
        canInstall = UpdateInstaller.canInstall(context)
        onPauseOrDispose { }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Updates") },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            UpdatesBody(
                state = state,
                settings = settings,
                canInstall = canInstall,
                onCheck = { scope.launch { app.updates.check() } },
                onDownload = { r -> scope.launch { app.updates.download(r) } },
                onInstall = { r, f ->
                    scope.launch {
                        app.updates.markInstalling(r)
                        runCatching { UpdateInstaller.install(context, f) }.onFailure { app.updates.installFinished(false, it.message) }
                    }
                },
                onSkip = { r ->
                    app.settings.update { it.copy(skippedVersion = r.version.toString()) }
                    app.updates.reset()
                },
                onAllowInstall = { runCatching { context.startActivity(UpdateInstaller.permissionIntent(context)) } },
                onOpenPage = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                onPrereleases = { on ->
                    app.settings.update { it.copy(includePrereleases = on) }
                    scope.launch { app.updates.check() }
                },
                samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true),
            )
        }
    }
}

@Composable
fun UpdatesBody(
    state: UpdateState,
    settings: AppSettings,
    canInstall: Boolean,
    onCheck: () -> Unit,
    onDownload: (AppRelease) -> Unit,
    onInstall: (AppRelease, File) -> Unit,
    onSkip: (AppRelease) -> Unit,
    onAllowInstall: () -> Unit,
    onOpenPage: (String) -> Unit,
    onPrereleases: (Boolean) -> Unit,
    samsung: Boolean = false,
) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("This version", Icons.Rounded.SystemUpdate) {
            InfoRow("Installed", BuildConfig.VERSION_NAME)
            InfoRow("Build", BuildConfig.GIT_SHA)
            InfoRow("Last check", if (settings.lastUpdateCheck > 0) Fmt.dateTime(settings.lastUpdateCheck) else "Never")
            InfoRow("Channel", if (settings.includePrereleases) "Releases and test builds" else "Releases only")
            TextButton(onClick = { onPrereleases(!settings.includePrereleases) }) {
                Text(if (settings.includePrereleases) "Only offer full releases" else "Also offer test builds")
            }
        }
        val release = when (state) {
            is UpdateState.Available -> state.release
            is UpdateState.Downloading -> state.release
            is UpdateState.Verifying -> state.release
            is UpdateState.ReadyToInstall -> state.release
            is UpdateState.Installing -> state.release
            is UpdateState.Failed -> state.release
            else -> null
        }
        when (state) {
            UpdateState.Idle, UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("  Checking GitHub for updates…")
            }
            is UpdateState.UpToDate -> SectionCard("You're up to date", Icons.Rounded.CheckCircle) {
                Text(
                    state.latest?.let { "Newest ${if (it.prerelease) "test build" else "release"}: ${it.tag}" } ?: "No releases found.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onCheck) { Text("Check again") }
            }
            is UpdateState.Failed -> SectionCard("Update problem", Icons.Rounded.ErrorOutline, tint = MaterialTheme.colorScheme.error) {
                Text(state.message, color = MaterialTheme.colorScheme.error)
                if (samsung && state.atInstall) AutoBlockerTip()
                TextButton(onClick = onCheck) { Text("Try again") }
            }
            else -> {}
        }
        if (release != null) {
            SectionCard(release.title, Icons.Rounded.NewReleases, trailing = { if (release.prerelease) Pill("Test build") }) {
                Text(
                    listOfNotNull(
                        release.tag,
                        release.publishedAt?.let { runCatching { Fmt.dateTime(Instant.parse(it).toEpochMilli()) }.getOrNull() },
                        "%.1f MB".format(release.apk.size / 1_048_576.0),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                if (release.notes.isNotBlank()) MarkdownLite(release.notes.take(6_000))
                Spacer(Modifier.height(12.dp))
                when (state) {
                    is UpdateState.Downloading -> {
                        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                        Text("Downloading ${(state.progress * 100).toInt()} %", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    }
                    is UpdateState.Verifying -> Text("Checking the download…", style = MaterialTheme.typography.bodySmall)
                    is UpdateState.Installing -> Text("Waiting for Android's installer…", style = MaterialTheme.typography.bodySmall)
                    is UpdateState.ReadyToInstall -> {
                        if (!canInstall) {
                            Text("Allow BatteryLog to install updates first.", style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = onAllowInstall) { Text("Allow") }
                            if (samsung) AutoBlockerTip()
                        }
                        Button(onClick = { onInstall(state.release, state.file) }, enabled = canInstall, modifier = Modifier.fillMaxWidth()) { Text("Install") }
                    }
                    else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { onDownload(release) }) { Text("Download & install") }
                        OutlinedButton(onClick = { onSkip(release) }) { Text("Skip") }
                    }
                }
                release.pageUrl?.let { url -> TextButton(onClick = { onOpenPage(url) }) { Text("View on GitHub") } }
            }
        }
    }
}

/** Samsung's Auto Blocker (on by default since One UI 6.1.1) blocks installs from outside the Play Store. */
@Composable
private fun AutoBlockerTip() {
    Text(
        "Samsung: if Android blocks the install, turn off Auto Blocker in Settings → Security and privacy → Auto Blocker.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}
