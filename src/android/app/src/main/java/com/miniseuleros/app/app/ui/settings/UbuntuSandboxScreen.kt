package com.miniseuleros.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.miniseuleros.app.sandbox.UbuntuSandbox
import com.miniseuleros.app.sandbox.UbuntuSandbox.InstallState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Management screen for the secondary Ubuntu 24.04 (arm64) sandbox.
 *
 * Installs an Ubuntu Base rootfs next to the main EulerOS one and keeps the
 * host workspace folder bind-mounted at /workspace inside it, so apt-type
 * tasks (build-essential, python toolchains, ...) run without touching the
 * main sandbox.
 */
@Composable
fun UbuntuSandboxScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val installState by UbuntuSandbox.installState.collectAsState()
    var installed by remember { mutableStateOf(UbuntuSandbox.isInstalled(context)) }
    var sizeMb by remember { mutableStateOf(0L) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // Refresh status whenever an install finishes or the screen re-enters.
    // [T-ubuntu-sandbox-size-crash] rootfsSizeBytes walks the whole rootfs —
    // thousands of small files, seconds of flash I/O on a phone. Before this
    // fix it ran on the composition dispatcher: it CRASHED the screen outright
    // when the rootfs wasn't installed yet (FileTreeWalk asserts on a missing
    // rootDir → AssertionError, crash-2026-09-30_10-37-59) and froze the UI for
    // 3-9s when it was (JankDiag escalations 3134ms→9513ms in the same log).
    // Both are addressed: the probe returns 0 for a missing rootfs, and the
    // walk runs on Dispatchers.IO.
    suspend fun refreshStatus() {
        val (inst, bytes) = withContext(Dispatchers.IO) {
            UbuntuSandbox.isInstalled(context) to UbuntuSandbox.rootfsSizeBytes(context)
        }
        installed = inst
        sizeMb = bytes / (1024 * 1024)
    }
    LaunchedEffect(installState) {
        if (installState is InstallState.Idle || installState is InstallState.Success) {
            refreshStatus()
        }
    }
    LaunchedEffect(Unit) { refreshStatus() }

    SettingsScaffold(title = "Ubuntu Sandbox", onBack = onBack) {
        SettingsSection(
            header = "Status",
            footer = "Files under /var/minis-euleros/workspace are mounted at /workspace inside Ubuntu. " +
                "The main sandbox is never modified by anything installed here.",
        ) {
            SettingsRow(
                title = if (installed) "Installed" else "Not installed",
                subtitle = if (installed) "Ubuntu 24.04 LTS (arm64) · ${sizeMb} MB · apt ready" else
                    "Ubuntu Base 24.04.3 arm64, ~27 MB download",
                icon = androidx.compose.material.icons.Icons.Filled.Terminal,
            )
        }

        SettingsSection(header = "Install") {
            when (val state = installState) {
                is InstallState.Downloading -> {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                        val total = state.totalBytes
                        val text = if (total > 0)
                            "Downloading… ${state.bytes / (1024 * 1024)} / ${total / (1024 * 1024)} MB"
                        else "Downloading… ${state.bytes / (1024 * 1024)} MB"
                        Text(text, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        if (total > 0) {
                            LinearProgressIndicator(
                                progress = { (state.bytes.toFloat() / total).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                }
                InstallState.Extracting -> ProgressLine("Extracting rootfs…")
                InstallState.Patching -> ProgressLine("Patching (DNS / apt sources)…")
                is InstallState.Failed -> {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text("Install failed: ${state.message}", color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { UbuntuSandbox.startInstall(context) }) { Text("Retry") }
                    }
                }
                else -> {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                        if (!installed) {
                            Button(onClick = { UbuntuSandbox.startInstall(context) }) {
                                Text("Install Ubuntu 24.04 sandbox")
                            }
                        } else {
                            OutlinedButton(onClick = { UbuntuSandbox.startInstall(context) }) {
                                Text("Reinstall (reset rootfs)")
                            }
                        }
                    }
                }
            }
        }

        if (installed) {
            SettingsSection(header = "Danger zone") {
                SettingsRow(
                    title = "Delete rootfs",
                    subtitle = "Frees ${sizeMb} MB. Files in /workspace are kept.",
                    icon = androidx.compose.material.icons.Icons.Filled.Delete,
                    iconColor = MaterialTheme.colorScheme.error,
                    onClick = { showDeleteConfirm = true },
                )
            }
        }

        SettingsSection(
            header = "How to use",
            footer = "Example: file_write a script to /var/minis-euleros/workspace/hello.py, " +
                "then ubuntu_shell \"cd /workspace && python3 hello.py\".",
        ) {
            SettingsRow(
                title = "ubuntu_shell tool",
                subtitle = "Available to the agent once installed. Commands run as root " +
                    "under proot with /workspace bound in; timeout up to 30 min.",
            )
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Ubuntu rootfs?") },
            text = { Text("This removes the Ubuntu 24.04 rootfs (about ${sizeMb} MB). " +
                "Files you created under /workspace (the shared workspace folder) are NOT deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    UbuntuSandbox.delete(context)
                    installed = false
                    sizeMb = 0
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ProgressLine(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
