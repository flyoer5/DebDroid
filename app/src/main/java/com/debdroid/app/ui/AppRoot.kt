package com.debdroid.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.debdroid.app.DebDroidApp
import com.debdroid.app.prefs.AppSettings
import com.debdroid.app.service.KeepAliveService
import com.debdroid.app.ui.editor.TextEditorScreen
import com.debdroid.app.ui.files.FileBrowserScreen
import com.debdroid.app.ui.settings.SettingsScreen
import com.debdroid.app.ui.terminal.TerminalScreen
import com.debdroid.app.ui.wizard.WizardScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Screen { WIZARD, TERMINAL, FILES, EDITOR, SETTINGS }

/**
 * 根导航（architecture.md §1.2）：屏少，用 enum 手切，不引入导航库。
 * 首启未装 rootfs → 向导；已装 → 终端。设置「恢复出厂」后回到向导。
 * [initialRoute] 为测试钩子（intent extra debdroid_route）。
 */
@Composable
fun AppRoot(initialRoute: String? = null) {
    val app = DebDroidApp.instance
    val context = LocalContext.current
    val settings by app.settingsRepository.settings.collectAsState(initial = AppSettings())
    val sessions by app.sessionManager.sessions.collectAsState()
    val sshStatus by app.sshManager.status.collectAsState()
    val activeIndex by app.sessionManager.activeIndex.collectAsState()
    val scope = rememberCoroutineScope()

    var screen by remember {
        mutableStateOf(
            when (initialRoute) {
                "files" -> Screen.FILES
                "editor" -> Screen.EDITOR
                "settings" -> Screen.SETTINGS
                else -> if (app.rootfsInstaller.isInstalled()) Screen.TERMINAL else Screen.WIZARD
            }
        )
    }
    var editorPath by remember { mutableStateOf<String?>(null) }

    fun startFirstSession() {
        scope.launch {
            val ok = runCatching { app.sessionManager.ensureSession(settings) }
                .onFailure { e ->
                    Log.e("DebDroid", "session start failed", e)
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            app, "启动失败：${e.message}", android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                .getOrDefault(false)
            if (ok && settings.keepForeground) KeepAliveService.start(app)
        }
    }

    // 重启后自动恢复会话（FR-S4 / keepRestore）：rootfs 已装、直接进终端屏时无会话则补建。
    // 仅组合首帧执行一次，避免与 wizard 完成后的手动调用双触发。
    LaunchedEffect(Unit) {
        if (screen == Screen.TERMINAL && sessions.isEmpty() && settings.keepRestore) {
            startFirstSession()
        }
    }

    // SSH 随会话自启（FR-H1）：首帧 LaunchedEffect 用默认设置（sshEnabled 默认 false，
    // DataStore 未加载完）建会话时可能错过自启；设置加载完成后补启动。幂等（isRunning 检查）。
    LaunchedEffect(settings.sshEnabled, settings.sshAutostart) {
        if (settings.sshEnabled && settings.sshAutostart &&
            sessions.isNotEmpty() && !app.sshManager.isRunning()
        ) {
            app.sshManager.startAsync(settings)
        }
    }

    // apt 镜像变化时立即重写 sources.list（设置页改镜像即时生效，真机调试定位：
    // 此前只写设置不重写配置，用户选 tuna 但 apt 仍走官方源）
    LaunchedEffect(settings.aptMirrorId) {
        if (app.rootfsInstaller.isInstalled()) {
            runCatching { app.rootfsInstaller.configure(settings) }
        }
    }

    when (screen) {
        Screen.WIZARD -> WizardScreen(
            settings = settings,
            install = { s, onProgress ->
                withContext(Dispatchers.IO) { app.rootfsInstaller.extract(onProgress) }
            },
            onFinished = {
                // 中文环境首次默认清华镜像（FR-W4）；随后写入 apt 源/resolv
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        if (java.util.Locale.getDefault().language.startsWith("zh") &&
                            settings.aptMirrorId == com.debdroid.app.prefs.AptMirror.OFFICIAL.id
                        ) {
                            app.settingsRepository.update {
                                it.copy(
                                    aptMirrorId = com.debdroid.app.rootfs.RootfsInstaller
                                        .defaultMirrorForLocale().id
                                )
                            }
                        }
                        app.rootfsInstaller.configure(
                            app.settingsRepository.settings.first()
                        )
                    }
                }
                screen = Screen.TERMINAL
                startFirstSession()
            },
        )

        Screen.TERMINAL -> TerminalScreen(
            settings = settings,
            sessions = sessions,
            activeIndex = activeIndex,
            onSelectSession = { app.sessionManager.selectSession(it) },
            onNewSession = {
                scope.launch {
                    runCatching { app.sessionManager.newSession(settings) }
                        .onFailure { e ->
                            Log.e("DebDroid", "new session failed", e)
                            withContext(Dispatchers.Main) {
                                android.widget.Toast.makeText(
                                    app, "新建会话失败：${e.message}", android.widget.Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                }
                if (settings.keepForeground) KeepAliveService.start(app)
            },
            onCloseSession = { index -> sessions.getOrNull(index)?.let { app.sessionManager.closeSession(it) } },
            onOpenSettings = { screen = Screen.SETTINGS },
            onOpenFiles = { screen = Screen.FILES },
            onOpenEditor = { path ->
                editorPath = path
                screen = Screen.EDITOR
            },
            onFontSizeDelta = { delta ->
                scope.launch {
                    app.settingsRepository.update {
                        val step = if (delta > 1.02f) 1 else -1
                        it.copy(fontSize = (it.fontSize + step).coerceIn(8, 32))
                    }
                }
            },
        )

        Screen.FILES -> FileBrowserScreen(
            onBack = { screen = Screen.TERMINAL },
            onOpenFile = { path ->
                editorPath = path
                screen = Screen.EDITOR
            },
        )

        Screen.EDITOR -> {
            BackHandler { screen = Screen.FILES }
            TextEditorScreen(
                filePath = editorPath,
                onBack = { screen = Screen.FILES },
            )
        }

        Screen.SETTINGS -> {
            BackHandler { screen = Screen.TERMINAL }
            SettingsScreen(
                settings = settings,
                sshStatus = sshStatus,
                onSettingsChange = { transformFn ->
                    // DataStore 写为挂起；设置屏每次变更把转换函数交回上层异步落盘（FR-C3）
                    scope.launch { app.settingsRepository.update(transformFn) }
                },
                onSshInstall = {
                    withContext(Dispatchers.IO) {
                        val result = app.sshManager.installBlocking(settings)
                        if (result.isSuccess) null
                        else result.output.ifBlank { "apt failed (exit ${result.exitCode})" }
                    }
                },
                onSshApply = {
                    withContext(Dispatchers.IO) { app.sshManager.applyConfigBlocking(settings).isSuccess }
                },
                onSshStart = {
                    withContext(Dispatchers.IO) { app.sshManager.startBlocking(settings) }
                },
                onSshStop = {
                    withContext(Dispatchers.IO) { app.sshManager.stopBlocking() }
                },
                onResetRootfs = {
                    app.sshManager.stopAsync()
                    app.sessionManager.closeAll()
                    screen = Screen.WIZARD
                },
                onExportDiagnostics = { exportDiagnostics(context, settings, app, sshStatus) },
                onBack = { screen = Screen.TERMINAL },
            )
        }
    }
}

/**
 * 生成诊断文本 → 复制到剪贴板 + 调起系统分享（协作调试通道 A）。
 * 纯应用层状态，不依赖 SSH；用户粘贴给开发者即可定位崩溃/启动/配置问题。
 */
private fun exportDiagnostics(
    context: Context,
    settings: AppSettings,
    app: DebDroidApp,
    sshStatus: com.debdroid.app.ssh.SshStatus,
) {
    val text = runCatching {
        com.debdroid.app.diag.Diagnostics.collect(
            context, settings, app.rootfsInstaller, sshStatus, app.sessionManager
        )
    }.getOrElse { "诊断收集失败: $it" }

    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("DebDroid 诊断信息", text))
    }
    runCatching {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "DebDroid 诊断信息 v${com.debdroid.app.BuildConfig.VERSION_NAME}")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "分享诊断信息"))
    }
}
