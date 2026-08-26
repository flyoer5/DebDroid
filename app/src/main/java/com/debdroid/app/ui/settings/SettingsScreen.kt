package com.debdroid.app.ui.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.debdroid.app.R
import com.debdroid.app.prefs.AppSettings
import com.debdroid.app.prefs.AptMirror
import com.debdroid.app.prefs.ThemeMode
import com.debdroid.app.ssh.SshStatus
import com.debdroid.app.ui.theme.TerminalColors
import kotlinx.coroutines.launch

/**
 * 设置屏（FR-C1~C4 / FR-H1~H3 / FR-K1~K3，architecture.md §3.6）。
 * 所有修改即时落 DataStore（toast「设置已保存」，FR-C3）；SSH 启动/停止走挂起回调。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    sshStatus: SshStatus,
    onSettingsChange: (AppSettings) -> Unit,
    onSshInstall: suspend () -> String?,
    onSshApply: suspend () -> Boolean,
    onSshStart: suspend () -> String?,
    onSshStop: suspend () -> Unit,
    onResetRootfs: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var toast by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val toastText = toast

    // 对话框状态
    var portDlg by remember { mutableStateOf(false) }
    var passwdDlg by remember { mutableStateOf(false) }
    var keysDlg by remember { mutableStateOf(false) }
    var startupDlg by remember { mutableStateOf(false) }
    var initialDlg by remember { mutableStateOf(false) }
    var mirrorDlg by remember { mutableStateOf(false) }
    var dnsDlg by remember { mutableStateOf(false) }
    var resetDlg by remember { mutableStateOf(false) }

    fun change(transform: (AppSettings) -> AppSettings) {
        onSettingsChange(transform)
        toast = "设置已保存 ✓"
    }

    if (toastText != null) {
        // 设置变更即时可见（DataStore 已落盘），Toast 短提示（FR-C3）
        android.widget.Toast.makeText(
            com.debdroid.app.DebDroidApp.instance, toastText, android.widget.Toast.LENGTH_SHORT,
        ).show()
        toast = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.set_appearance).replace("外观", "设置")) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("‹ 返回") }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // ---------- 外观 ----------
            item { SectionTitle(stringResource(R.string.set_appearance)) }
            item {
                SettingsRow(
                    title = stringResource(R.string.set_theme_mode),
                    subtitle = settings.themeMode.name,
                    onClick = {
                        change {
                            it.copy(themeMode = when (it.themeMode) {
                                ThemeMode.FOLLOW_SYSTEM -> ThemeMode.DARK
                                ThemeMode.DARK -> ThemeMode.LIGHT
                                ThemeMode.LIGHT -> ThemeMode.FOLLOW_SYSTEM
                            })
                        }
                    },
                )
            }
            item {
                SettingsRow(
                    title = "终端配色",
                    subtitle = TerminalColors.byId(settings.colorSchemeId).name,
                    onClick = {
                        change {
                            val all = TerminalColors.ALL
                            val idx = all.indexOfFirst { s -> s.id == it.colorSchemeId }
                            it.copy(colorSchemeId = all[(idx + 1) % all.size].id)
                        }
                    },
                )
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("字号", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${settings.fontSize}sp（捏合终端屏可调）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("${settings.fontSize}", fontFamily = FontFamily.Monospace)
                }
            }
            item {
                SettingsRow(
                    title = "Nerd Font 图标字体",
                    subtitle = "终端内图标/特殊字符渲染",
                    trailing = {
                        Switch(
                            checked = settings.useNerdFont,
                            onCheckedChange = { change { it.copy(useNerdFont = it.useNerdFont) } },
                        )
                    },
                )
            }

            // ---------- 终端 ----------
            item { SectionTitle(stringResource(R.string.set_terminal)) }
            item {
                SettingsRow(
                    title = "启动命令",
                    subtitle = settings.startupCommand,
                    onClick = { startupDlg = true },
                )
            }
            item {
                SettingsRow(
                    title = "初始目录",
                    subtitle = settings.initialDir,
                    onClick = { initialDlg = true },
                )
            }
            item {
                SettingsRow(
                    title = "tmux 会话保持",
                    subtitle = "默认 tmux new -A -s main",
                    trailing = {
                        Switch(
                            checked = settings.tmuxAttach,
                            onCheckedChange = { change { it.copy(tmuxAttach = it.tmuxAttach) } },
                        )
                    },
                )
            }

            // ---------- SSH ----------
            item { SectionTitle(stringResource(R.string.set_ssh)) }
            item {
                SettingsRow(
                    title = stringResource(R.string.ssh_enabled),
                    subtitle = sshStatusText(sshStatus),
                    trailing = {
                        Switch(
                            checked = settings.sshEnabled,
                            onCheckedChange = { enabled ->
                                change { it.copy(sshEnabled = enabled) }
                                if (enabled) {
                                    scope.launch {
                                        busy = true
                                        val err = onSshInstall()
                                        if (err != null) {
                                            toast = err
                                            change { it.copy(sshEnabled = false) }
                                        } else {
                                            onSshStart()?.let { toast = it }
                                        }
                                        busy = false
                                    }
                                } else {
                                    scope.launch { onSshStop() }
                                }
                            },
                        )
                    },
                )
            }
            if (settings.sshEnabled) {
                item {
                    SettingsRow(
                        title = "端口",
                        subtitle = settings.sshPort.toString(),
                        onClick = { portDlg = true },
                    )
                }
                item {
                    SettingsRow(
                        title = stringResource(R.string.ssh_listen),
                        subtitle = if (settings.sshListenAll) "0.0.0.0（所有网卡）" else "127.0.0.1（仅局域网）",
                        trailing = {
                            Switch(
                                checked = settings.sshListenAll,
                                onCheckedChange = { change { it.copy(sshListenAll = it.sshListenAll) } },
                            )
                        },
                    )
                }
                item {
                    SettingsRow(
                        title = stringResource(R.string.ssh_password_login),
                        subtitle = if (settings.sshPassword.isEmpty()) "未设置密码" else "已设置 root 密码",
                        onClick = { passwdDlg = true },
                    )
                }
                item {
                    SettingsRow(
                        title = stringResource(R.string.ssh_pubkey),
                        subtitle = if (settings.sshAuthorizedKeys.isBlank()) "未添加公钥" else "已配置 ${settings.sshAuthorizedKeys.lines().size} 个公钥",
                        onClick = { keysDlg = true },
                    )
                }
                item {
                    SettingsRow(
                        title = stringResource(R.string.ssh_autostart),
                        subtitle = "随终端会话自动启动 SSH",
                        trailing = {
                            Switch(
                                checked = settings.sshAutostart,
                                onCheckedChange = { change { it.copy(sshAutostart = it.sshAutostart) } },
                            )
                        },
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                scope.launch {
                                    busy = true
                                    onSshApply()
                                    onSshStart()?.let { toast = it }
                                    busy = false
                                }
                            },
                            enabled = !busy,
                        ) { Text("应用并启动") }
                        OutlinedButton(onClick = { scope.launch { onSshStop() } }) { Text("停止") }
                    }
                }
            }

            // ---------- 保活 ----------
            item { SectionTitle(stringResource(R.string.set_keepalive)) }
            item {
                SettingsRow(
                    title = stringResource(R.string.keep_foreground),
                    subtitle = "常驻通知，防进程被杀",
                    trailing = {
                        Switch(
                            checked = settings.keepForeground,
                            onCheckedChange = { change { it.copy(keepForeground = it.keepForeground) } },
                        )
                    },
                )
            }
            item {
                SettingsRow(
                    title = stringResource(R.string.keep_wakelock),
                    subtitle = "屏幕关闭后 CPU 不休眠",
                    trailing = {
                        Switch(
                            checked = settings.keepWakelock,
                            onCheckedChange = { change { it.copy(keepWakelock = it.keepWakelock) } },
                        )
                    },
                )
            }
            item {
                SettingsRow(
                    title = stringResource(R.string.keep_battery),
                    subtitle = "引导加入系统电池优化白名单（FR-K3）",
                    trailing = {
                        Switch(
                            checked = settings.keepBatteryWhitelist,
                            onCheckedChange = { change { it.copy(keepBatteryWhitelist = it.keepBatteryWhitelist) } },
                        )
                    },
                )
            }
            item {
                SettingsRow(
                    title = stringResource(R.string.keep_boot),
                    subtitle = "开机后自动启动保活服务",
                    trailing = {
                        Switch(
                            checked = settings.keepBoot,
                            onCheckedChange = { change { it.copy(keepBoot = it.keepBoot) } },
                        )
                    },
                )
            }
            item {
                SettingsRow(
                    title = stringResource(R.string.keep_restore),
                    subtitle = "进程被杀后自动恢复会话",
                    trailing = {
                        Switch(
                            checked = settings.keepRestore,
                            onCheckedChange = { change { it.copy(keepRestore = it.keepRestore) } },
                        )
                    },
                )
            }

            // ---------- 系统 ----------
            item { SectionTitle(stringResource(R.string.set_system)) }
            item {
                SettingsRow(
                    title = stringResource(R.string.set_mirror),
                    subtitle = AptMirror.fromId(settings.aptMirrorId).url,
                    onClick = { mirrorDlg = true },
                )
            }
            item {
                SettingsRow(
                    title = "自定义 DNS",
                    subtitle = settings.customDns.ifBlank { "默认 8.8.8.8 / 223.5.5.5" },
                    onClick = { dnsDlg = true },
                )
            }
            item {
                SettingsRow(
                    title = stringResource(R.string.set_reset),
                    subtitle = "删除 rootfs 并回到安装向导",
                    titleColor = MaterialTheme.colorScheme.error,
                    onClick = { resetDlg = true },
                )
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(R.string.set_version),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "v${com.debdroid.app.BuildConfig.VERSION_NAME} (build ${com.debdroid.app.BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }

    // ---------- 对话框 ----------
    if (portDlg) {
        NumberDialog(
            title = "SSH 端口",
            initial = settings.sshPort.toString(),
            range = 1024..65535,
            onDismiss = { portDlg = false },
            onConfirm = { value -> change { it.copy(sshPort = value) }; portDlg = false },
        )
    }
    if (passwdDlg) {
        TextDialog(
            title = "root 密码（留空禁用密码登录）",
            initial = settings.sshPassword,
            isPassword = true,
            onDismiss = { passwdDlg = false },
            onConfirm = { value -> change { it.copy(sshPassword = value.trim()) }; passwdDlg = false },
        )
    }
    if (keysDlg) {
        TextDialog(
            title = "公钥（每行一个）",
            initial = settings.sshAuthorizedKeys,
            multiLine = true,
            onDismiss = { keysDlg = false },
            onConfirm = { value -> change { it.copy(sshAuthorizedKeys = value.trim()) }; keysDlg = false },
        )
    }
    if (startupDlg) {
        TextDialog(
            title = "启动命令",
            initial = settings.startupCommand,
            onDismiss = { startupDlg = false },
            onConfirm = { value ->
                change { it.copy(startupCommand = value.trim().ifBlank { "/bin/bash --login" }) }
                startupDlg = false
            },
        )
    }
    if (initialDlg) {
        TextDialog(
            title = "初始目录",
            initial = settings.initialDir,
            onDismiss = { initialDlg = false },
            onConfirm = { value ->
                change { it.copy(initialDir = value.trim().ifBlank { "/root" }) }
                initialDlg = false
            },
        )
    }
    if (dnsDlg) {
        TextDialog(
            title = "自定义 DNS（每行一个 nameserver）",
            initial = settings.customDns,
            multiLine = true,
            onDismiss = { dnsDlg = false },
            onConfirm = { value -> change { it.copy(customDns = value.trim()) }; dnsDlg = false },
        )
    }
    if (mirrorDlg) {
        AlertDialog(
            onDismissRequest = { mirrorDlg = false },
            title = { Text(stringResource(R.string.set_mirror)) },
            text = {
                Column {
                    AptMirror.entries.forEach { m ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                change { it.copy(aptMirrorId = m.id) }
                                mirrorDlg = false
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = settings.aptMirrorId == m.id,
                                onClick = {
                                    change { it.copy(aptMirrorId = m.id) }
                                    mirrorDlg = false
                                },
                            )
                            Text(m.url, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { mirrorDlg = false }) { Text("关闭") } },
        )
    }
    if (resetDlg) {
        AlertDialog(
            onDismissRequest = { resetDlg = false },
            title = { Text(stringResource(R.string.set_reset)) },
            text = { Text("将删除整个 Debian rootfs 与全部会话。此操作不可撤销，确定继续吗？") },
            confirmButton = {
                TextButton(onClick = {
                    resetDlg = false
                    onResetRootfs()
                }) { Text("删除并重装", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { resetDlg = false }) { Text(stringResource(R.string.fs_cancel)) }
            },
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing?.invoke()
    }
    Divider(color = MaterialTheme.colorScheme.outlineVariant)
}

private fun sshStatusText(status: SshStatus): String = when (status) {
    is SshStatus.NotInstalled -> "未安装 openssh-server"
    is SshStatus.Stopped -> "已停止"
    is SshStatus.Running -> {
        val ip = com.debdroid.app.DebDroidApp.instance.sshManager.localIpAddress() ?: "<本机IP>"
        "运行中 · ssh root@$ip -p ${status.port}"
    }
}

@Composable
private fun NumberDialog(
    title: String,
    initial: String,
    range: IntRange,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter { c -> c.isDigit() } },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val v = text.toIntOrNull()
                if (v != null && v in range) onConfirm(v)
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.fs_cancel)) } },
    )
}

@Composable
private fun TextDialog(
    title: String,
    initial: String,
    isPassword: Boolean = false,
    multiLine: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = !multiLine,
                minLines = if (multiLine) 4 else 1,
                visualTransformation = if (isPassword) {
                    androidx.compose.ui.text.input.PasswordVisualTransformation()
                } else {
                    androidx.compose.ui.text.input.VisualTransformation.None
                },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = if (isPassword) {
                        androidx.compose.ui.text.input.KeyboardType.Password
                    } else {
                        androidx.compose.ui.text.input.KeyboardType.Text
                    },
                ),
                modifier = if (multiLine) Modifier.fillMaxWidth() else Modifier,
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.fs_cancel)) } },
    )
}
