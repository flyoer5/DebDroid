package com.debdroid.app.session

import android.util.Log
import com.debdroid.app.prefs.AppSettings
import com.debdroid.app.rootfs.RootfsInstaller
import com.debdroid.app.ssh.SshManager
import com.debdroid.app.ui.theme.TerminalColors
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 会话生命周期管理（architecture.md §3.2）。
 *
 * 并发防护（历史教训落点）：
 * - [ensureSession] 单飞：恢复路径与界面路径并发时只建一个首会话；**Mutex 只在顶层获取一次**
 *   （不可重入），[newSession] 内部绝不重复获取——v1.0.19 的嵌套死锁回归防在结构上。
 * - 会话列表变更用短 `synchronized` 临界区，不挂起（无死锁面）。
 * - [TerminalSession] 构造必须在有 Looper 的主线程（其内部初始化创建绑定调用线程的
 *   Handler，v1.0.18 教训）；重活（resolv/proot 解包）在 IO 线程。
 */
class SessionManager(
    private val rootfsInstaller: RootfsInstaller,
    private val sshManager: SshManager,
) : TerminalSessionClient {

    private val _sessions = MutableStateFlow<List<TerminalSession>>(emptyList())
    val sessions: StateFlow<List<TerminalSession>> = _sessions.asStateFlow()

    /** v2.1.17：末会话因进程退出而清空（非手动关闭/恢复出厂）——UI 据此自动补新会话。 */
    private val _lastSessionDied = MutableStateFlow(false)
    val lastSessionDied: StateFlow<Boolean> = _lastSessionDied.asStateFlow()

    /** v2.1.17：UI 消费自动补建意图后复位。 */
    fun clearLastSessionDied() {
        _lastSessionDied.value = false
    }

    /**
     * v2.1.18：手动关闭中的会话（closeSession 先登记再杀）——onSessionFinished
     * 据此区分手动关闭与进程自然退出，避免"用户关掉最后一个会话仍被强行补建"
     * （真机暴露：badge 关闭后 Session 5/6 连环重生）。
     */
    private val manualCloses = java.util.Collections.synchronizedSet(LinkedHashSet<TerminalSession>())

    /** 当前终端屏展示的会话下标。 */
    val activeIndex = MutableStateFlow(0)

    private val sessionMutex = Mutex()
    private var counter = 0

    /** 最近日志环形缓冲（诊断导出用，上限 [maxLogLines]）。 */
    private val recentLogs = ArrayDeque<String>()

    fun recentLogCount(): Int = synchronized(recentLogs) { recentLogs.size }

    fun recentLogSnapshot(): List<String> = synchronized(recentLogs) { recentLogs.toList() }

    private fun logLine(line: String) {
        synchronized(recentLogs) {
            while (recentLogs.size >= maxLogLines) recentLogs.removeFirst()
            recentLogs.addLast(line)
        }
    }

    /**
     * 创建首个会话（若尚无）。返回是否真的创建了。
     * 供：首次进入终端（FR-W3）、服务重启自动恢复（FR-S4）。
     */
    suspend fun ensureSession(settings: AppSettings): Boolean = sessionMutex.withLock {
        if (_sessions.value.isNotEmpty()) return false
        newSession(settings)
        true
    }

    /** 新建会话（FR-S1）。由 [ensureSession] 持锁调用或用户手动触发。 */
    suspend fun newSession(settings: AppSettings): TerminalSession {
        val (launcher, prootBin) = withContext(Dispatchers.IO) {
            rootfsInstaller.configure(settings) // sources.list + resolv.conf 幂等写入
            TerminalColors.applyScheme(settings.colorSchemeId)
            val pl = ProotLauncher(rootfsInstaller.context, settings)
            val bin = pl.ensureBootstrap()
                ?: throw java.io.IOException("proot runtime bundle missing from APK assets")
            pl to bin
        }
        return withContext(Dispatchers.Main) {
            val session = TerminalSession(
                prootBin.path,
                rootfsInstaller.context.filesDir.path,
                launcher.buildArgs().toTypedArray(),
                launcher.buildEnv(),
                null,
                this@SessionManager,
            )
            synchronized(this@SessionManager) {
                counter += 1
                session.mSessionName = "Session $counter"
                _sessions.value = _sessions.value + session
                if (activeIndex.value >= _sessions.value.size) {
                    activeIndex.value = _sessions.value.size - 1
                }
                _lastSessionDied.value = false
            }
            // v2.1.21：会话生命周期入诊断环形缓冲——此前 /api/logs 只捕获 TerminalSessionClient
            // 回调，健康运行一小时也近乎为空，诊断端点形同虚设（真机暴露）。
            logLine("[session-created] name=${session.mSessionName} handle=${session.mHandle.takeLast(8)} total=${_sessions.value.size}")
            // 会话就绪后按设置自启 SSH（FR-H1 顺带路径）
            if (settings.sshEnabled && settings.sshAutostart && !sshManager.isRunning()) {
                sshManager.startAsync(settings)
            }
            session
        }
    }

    /** 关闭单个会话（FR-S2）：先送 Ctrl+C，再后台 finish。 */
    fun closeSession(session: TerminalSession) {
        // v2.1.18：先登记手动关闭，onSessionFinished 不再置 lastSessionDied。
        manualCloses.add(session)
        logLine("[session-closed] name=${session.mSessionName} manual=true remaining=${_sessions.value.size - 1}")
        if (session.isRunning) {
            runCatching {
                val ctrlC = "\u0003".toByteArray()
                session.write(ctrlC, 0, ctrlC.size)
            }
            Thread { runCatching { session.finishIfRunning() } }.start()
        } else {
            // 已死的会话不会再来 onSessionFinished——直接清列表并清理登记。
            _sessions.value = _sessions.value.filter { it !== session }
            manualCloses.remove(session)
            if (activeIndex.value >= _sessions.value.size && _sessions.value.isNotEmpty()) {
                activeIndex.value = _sessions.value.size - 1
            }
        }
    }

    fun closeAll() {
        _sessions.value.forEach { closeSession(it) }
    }

    fun activeSession(): TerminalSession? {
        val list = _sessions.value
        val idx = activeIndex.value.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        return list.getOrNull(idx)
    }

    fun selectSession(index: Int) {
        if (index in _sessions.value.indices) activeIndex.value = index
    }

    // ---- TerminalSessionClient ----

    override fun onTextChanged(changedSession: TerminalSession) {
        // 即时转发屏幕更新给 TerminalView——否则只能靠光标闪烁 ~500ms 重绘，
        // 输入回显会明显卡顿（v1.0.8 修复，重写必须保留）。
        com.debdroid.app.ui.terminal.notifyTerminalViewScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {}

    override fun onSessionFinished(finishedSession: TerminalSession) {
        // v2.1.14：恒记录（多会话真机走查暴露：第二会话偶发死掉且零日志——原实现只记
        // 非空 transcript，空白会话死亡完全无痕）。退出码定位杀手：waitFor 对信号返回负值
        // （-9=SIGKILL/-1=SIGHUP），>0=exit code。pid 在 cleanupResources 后变 -1，先取。
        runCatching {
            val status = finishedSession.getExitStatus()
            val text = finishedSession.emulator?.mScreen?.getTranscriptText()
            val tail = if (text.isNullOrBlank()) "<blank>" else text.takeLast(800)
            val msg = "[session-finished] name=${finishedSession.mSessionName} handle=${finishedSession.mHandle.takeLast(8)} exit=$status tail:\n$tail"
            logLine(msg)
            Log.i(TAG, msg)
        }
        runCatching { finishedSession.finishIfRunning() }
        _sessions.value = _sessions.value.filter { it !== finishedSession }
        if (activeIndex.value >= _sessions.value.size && _sessions.value.isNotEmpty()) {
            activeIndex.value = _sessions.value.size - 1
        }
        // v2.1.17：最后一个会话因进程退出而清空（用户输 exit/Ctrl+D 或共享 tmux 销毁）——
        // 通知 UI 自动补新会话。真机暴露：此前清空后终端区空白无任何操作入口。
        // v2.1.18：手动关闭的会话（badge/恢复出厂）不置位——尊重用户意图，
        // 关掉最后一个会话后保持空列表（抽屉"新建会话"仍可用）。
        val wasManualClose = manualCloses.remove(finishedSession)
        if (_sessions.value.isEmpty() && !wasManualClose) {
            _lastSessionDied.value = true
        }
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}
    override fun onPasteTextFromClipboard(session: TerminalSession) {}
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int = 1 // TERMUX_CURSOR_STYLE_BLINK_BLOCK

    override fun logError(tag: String, message: String) { logLine("[$tag] $message"); Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { logLine("[$tag] $message"); Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { logLine("[$tag] $message"); Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { logLine("[$tag] $message"); Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { logLine("[$tag] $message"); Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception?) {
        logLine("[$tag] $message ${e?.stackTraceToString() ?: ""}")
        Log.e(tag, message, e)
    }
    override fun logStackTrace(tag: String, e: Exception?) {
        logLine("[$tag] ${e?.stackTraceToString() ?: "(null)"}")
        Log.e(tag, "", e)
    }

    companion object {
        private const val TAG = "DebDroidSession"
        private const val maxLogLines = 300
    }
}
