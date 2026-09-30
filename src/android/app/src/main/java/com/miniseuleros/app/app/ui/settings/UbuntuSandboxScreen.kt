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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.miniseuleros.app.R
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

    SettingsScaffold(title = stringResource(R.string.ubuntu_sandbox_title), onBack = onBack) {
        SettingsSection(
            header = stringResource(R.string.ubuntu_sandbox_status_header),
            footer = stringResource(R.string.ubuntu_sandbox_status_footer),
        ) {
            SettingsRow(
                title = if (installed) stringResource(R.string.ubuntu_sandbox_installed)
                    else stringResource(R.string.ubuntu_sandbox_not_installed),
                subtitle = if (installed) stringResource(R.string.ubuntu_sandbox_installed_subtitle, sizeMb)
                    else stringResource(R.string.ubuntu_sandbox_not_installed_subtitle),
                icon = androidx.compose.material.icons.Icons.Filled.Terminal,
            )
        }

        SettingsSection(header = stringResource(R.string.ubuntu_sandbox_install_header)) {
            when (val state = installState) {
                is InstallState.Downloading -> {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                        val total = state.totalBytes
                        val text = if (total > 0)
                            stringResource(
                                R.string.ubuntu_sandbox_downloading,
                                state.bytes / (1024 * 1024), total / (1024 * 1024),
                            )
                        else stringResource(
                            R.string.ubuntu_sandbox_downloading_unknown_size,
                            state.bytes / (1024 * 1024),
                        )
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
                InstallState.Extracting -> ProgressLine(stringResource(R.string.ubuntu_sandbox_extracting))
                InstallState.Patching -> ProgressLine(stringResource(R.string.ubuntu_sandbox_patching))
                is InstallState.Failed -> {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(
                            stringResource(R.string.ubuntu_sandbox_install_failed, state.message),
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { UbuntuSandbox.startInstall(context) }) {
                            Text(stringResource(R.string.ubuntu_sandbox_retry))
                        }
                    }
                }
                else -> {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                        if (!installed) {
                            Button(onClick = { UbuntuSandbox.startInstall(context) }) {
                                Text(stringResource(R.string.ubuntu_sandbox_install_button))
                            }
                        } else {
                            OutlinedButton(onClick = { UbuntuSandbox.startInstall(context) }) {
                                Text(stringResource(R.string.ubuntu_sandbox_reinstall_button))
                            }
                        }
                    }
                }
            }
        }

        if (installed) {
            SettingsSection(header = stringResource(R.string.ubuntu_sandbox_danger_header)) {
                SettingsRow(
                    title = stringResource(R.string.ubuntu_sandbox_delete_title),
                    subtitle = stringResource(R.string.ubuntu_sandbox_delete_subtitle, sizeMb),
                    icon = androidx.compose.material.icons.Icons.Filled.Delete,
                    iconColor = MaterialTheme.colorScheme.error,
                    onClick = { showDeleteConfirm = true },
                )
            }
        }

        SettingsSection(
            header = stringResource(R.string.ubuntu_sandbox_howto_header),
            footer = stringResource(R.string.ubuntu_sandbox_howto_footer),
        ) {
            SettingsRow(
                title = stringResource(R.string.ubuntu_sandbox_tool_title),
                subtitle = stringResource(R.string.ubuntu_sandbox_tool_subtitle),
            )
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.ubuntu_sandbox_delete_confirm_title)) },
            text = { Text(stringResource(R.string.ubuntu_sandbox_delete_confirm_text, sizeMb)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    UbuntuSandbox.delete(context)
                    installed = false
                    sizeMb = 0
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
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
