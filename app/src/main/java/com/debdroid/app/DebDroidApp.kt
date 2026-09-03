package com.debdroid.app

import android.app.Application
import com.debdroid.app.prefs.SettingsRepository
import com.debdroid.app.rootfs.RootfsInstaller
import com.debdroid.app.session.SessionManager
import com.debdroid.app.ssh.SshManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 应用入口：持有全部单例仓库/管理器，经 [instance] 供 Compose 与后台组件取用。
 * 初始化均为轻量构造（不落盘）；重活由各管理器按需在 IO 线程执行。
 */
class DebDroidApp : Application() {

    lateinit var settingsRepository: SettingsRepository
    lateinit var rootfsInstaller: RootfsInstaller
    lateinit var sshManager: SshManager
    lateinit var sessionManager: SessionManager

    /**
     * 应用级协程作用域（进程存活即活）：供 UI 组件把"离开页面也不能丢"的持久化
     * 写入（如文件书签）脱离组合生命周期执行——rememberCoroutineScope/LaunchedEffect
     * 会在用户立即返回上一屏时取消未完成的写回。
     */
    val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
    )

    private var debugApiScope: kotlinx.coroutines.CoroutineScope? = null
    private var debugApi: com.debdroid.app.debug.DebugApiServer? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashHandler()
        settingsRepository = SettingsRepository(this)
        rootfsInstaller = RootfsInstaller(this)
        sshManager = SshManager(this, rootfsInstaller)
        sessionManager = SessionManager(rootfsInstaller, sshManager)
        sshManager.refreshStatus() // 进程重启后恢复 SSH 状态展示（FR-H2）
        watchDebugApi() // 调试接口随设置开关启停（默认关）
    }

    /**
     * 全局崩溃捕获：堆栈写入 crash.log，供设置页「导出诊断信息」带回（诊断通道 A）。
     * 不拦截崩溃（继续走原 handler），只做落盘。
     */
    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val f = File(filesDir, "crash.log")
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                f.appendText("=== $ts ===\n$throwable\n${throwable.stackTraceToString()}\n")
            }
            prev?.uncaughtException(thread, throwable)
        }
    }

    /**
     * 监听设置开关启停调试 HTTP 接口（默认关闭；局域网可达，端口 8710）。
     * 进程存活期间有效；重启后按设置自动恢复。
     */
    private fun watchDebugApi() {
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
        )
        debugApiScope = scope
        scope.launch {
            settingsRepository.settings.collect { s ->
                val enabled = s.debugApiEnabled
                val server = debugApi
                if (enabled && server == null) {
                    runCatching {
                        com.debdroid.app.debug.DebugApiServer(
                            this@DebDroidApp, settingsRepository, rootfsInstaller, sessionManager, sshManager
                        ).also { it.start() }.also { debugApi = it }
                    }.onFailure { android.util.Log.e(TAG, "debug api start failed", it) }
                } else if (!enabled && server != null) {
                    runCatching { server.stop() }
                    debugApi = null
                }
            }
        }
    }

    companion object {
        lateinit var instance: DebDroidApp
            private set
        private const val TAG = "DebDroidApp"
    }
}
