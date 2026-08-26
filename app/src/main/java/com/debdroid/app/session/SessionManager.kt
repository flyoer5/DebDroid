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

    /** 当前终端屏展示的会话下标。 */
    val activeIndex = MutableStateFlow(0)

    private val sessionMutex = Mutex()
    private var counter = 0

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
            }
            // 会话就绪后按设置自启 SSH（FR-H1 顺带路径）
            if (settings.sshEnabled && settings.sshAutostart && !sshManager.isRunning()) {
                sshManager.startAsync(settings)
            }
            session
        }
    }

    /** 关闭单个会话（FR-S2）：先送 Ctrl+C，再后台 finish。 */
    fun closeSession(session: TerminalSession) {
        if (session.isRunning) {
            runCatching {
                val ctrlC = "\u0003".toByteArray()
                session.write(ctrlC, 0, ctrlC.size)
            }
            Thread { runCatching { session.finishIfRunning() } }.start()
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
        // 记录 transcript 尾部便于从 logcat 诊断 proot 失败原因（FR-S5 辅助）
        runCatching {
            val text = finishedSession.emulator?.mScreen?.getTranscriptText()
            if (!text.isNullOrBlank()) Log.i(TAG, "session finished tail:\n" + text.takeLast(1200))
        }
        runCatching { finishedSession.finishIfRunning() }
        _sessions.value = _sessions.value.filter { it !== finishedSession }
        if (activeIndex.value >= _sessions.value.size && _sessions.value.isNotEmpty()) {
            activeIndex.value = _sessions.value.size - 1
        }
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}
    override fun onPasteTextFromClipboard(session: TerminalSession) {}
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int = 1 // TERMUX_CURSOR_STYLE_BLINK_BLOCK

    override fun logError(tag: String, message: String) { Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception?) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception?) { Log.e(tag, "", e) }

    companion object {
        private const val TAG = "DebDroidSession"
    }
}
