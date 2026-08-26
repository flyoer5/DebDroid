package com.debdroid.app.debug

import android.content.Context
import com.debdroid.app.BuildConfig
import com.debdroid.app.core.FsOps
import com.debdroid.app.prefs.AppSettings
import com.debdroid.app.prefs.AptMirror
import com.debdroid.app.prefs.SettingsRepository
import com.debdroid.app.rootfs.RootfsInstaller
import com.debdroid.app.session.ProotLauncher
import com.debdroid.app.session.SessionManager
import com.debdroid.app.ssh.SshManager
import com.debdroid.app.ssh.SshStatus
import fi.iki.elonen.NanoHTTPD
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * 调试 HTTP 接口（协作调试通道）：把应用各功能暴露为局域网可达的 REST 接口，
 * 无需视觉即可定位问题。绑定 0.0.0.0（用户指定局域网可达，网络 adb 同网段访问）。
 *
 * 安全：默认关闭（[AppSettings.debugApiEnabled]）；无认证——用户明确要求不做安全设计。
 * 端口固定 8710；重活（command/install）在独立线程或协程执行，不阻塞其他请求。
 *
 * 接口一览（前缀 /api）：
 *   GET  ping / status / diagnostics / logs / settings
 *   PUT  settings            （JSON 部分字段更新）
 *   POST command             （proot 一次性执行，body {"cmd","timeout"}）
 *   POST install             （异步开始）；GET install/progress（轮询）
 *   POST reset
 *   POST session/new · session/write · GET session/screen
 *   GET  files?path= · files/read?path= · POST files/write
 *   POST ssh/start · ssh/stop · GET ssh/status
 */
class DebugApiServer(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val rootfsInstaller: RootfsInstaller,
    private val sessionManager: SessionManager,
    private val sshManager: SshManager,
) : NanoHTTPD(DEBUG_API_PORT) {

    companion object {
        const val DEBUG_API_PORT = 8710
        private const val MAX_SCREEN_CHARS = 32 * 1024
        private const val MAX_FILE_READ = 256 * 1024
    }

    /** 安装进度（跨请求可见）。 */
    @Volatile
    var installState: InstallState = InstallState.Idle
        private set

    sealed class InstallState {
        data object Idle : InstallState()
        data class Running(val fraction: Float, val stage: String) : InstallState()
        data class Done(val ok: Boolean, val message: String) : InstallState()
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun serve(session: IHTTPSession): Response {
        return try {
            route(session)
        } catch (e: Exception) {
            json(500, JSONObject().put("error", e.message ?: e.toString()))
        }
    }

    // ------------------------------------------------------------------
    // 路由
    // ------------------------------------------------------------------

    private fun route(session: IHTTPSession): Response {
        val method = session.method.name
        val path = session.uri.trimEnd('/')
        val q = session.parms

        return when {
            // ---- 只读状态 ----
            method == "GET" && path == "/api/ping" -> json(
                200, JSONObject()
                    .put("ok", true)
                    .put("app", BuildConfig.VERSION_NAME)
                    .put("build", BuildConfig.VERSION_CODE)
                    .put("apiPort", DEBUG_API_PORT)
            )

            method == "GET" && path == "/api/status" -> json(200, statusJson())

            method == "GET" && path == "/api/diagnostics" -> {
                val settings = settingsSnapshot()
                val text = com.debdroid.app.diag.Diagnostics.collect(
                    context, settings, rootfsInstaller, sshManager.status.value, sessionManager
                )
                json(200, JSONObject().put("text", text))
            }

            method == "GET" && path == "/api/logs" -> json(
                200, JSONObject().put("logs", JSONArray(sessionManager.recentLogSnapshot()))
            )

            // ---- 设置 ----
            method == "GET" && path == "/api/settings" -> json(200, settingsJson(settingsSnapshot()))

            method == "PUT" && path == "/api/settings" -> {
                val body = readBody(session)
                val obj = JSONObject(body)
                runBlocking {
                    settingsRepository.update { it.applyJson(obj) }
                }
                json(200, JSONObject().put("ok", true))
            }

            // ---- Debian 一次性命令 ----
            method == "POST" && path == "/api/command" -> {
                val obj = JSONObject(readBody(session))
                val cmd = obj.optString("cmd").ifBlank { return json(400, JSONObject().put("error", "cmd required")) }
                val timeout = obj.optLong("timeout", 60)
                val settings = settingsSnapshot()
                val result = runBlocking {
                    kotlinx.coroutines.withContext(Dispatchers.IO) {
                        ProotLauncher(context, settings).runOnce(cmd, timeout)
                    }
                }
                json(
                    200, JSONObject()
                        .put("exitCode", result.exitCode)
                        .put("output", result.output)
                )
            }

            // ---- 安装 / 重置 ----
            method == "POST" && path == "/api/install" -> {
                if (installState is InstallState.Running) {
                    return json(409, JSONObject().put("error", "install already running"))
                }
                installState = InstallState.Running(0f, "开始")
                scope.launch {
                    val settings = settingsSnapshot()
                    val result = runCatching {
                        rootfsInstaller.wipe()
                        rootfsInstaller.extract { p -> installState = InstallState.Running(p.fraction, p.stage) }
                        rootfsInstaller.configure(settings)
                        rootfsInstaller.isInstalled()
                    }
                    installState = result.fold(
                        onSuccess = { ok -> InstallState.Done(ok, if (ok) "OK" else "rootfs 缺失") },
                        onFailure = { e -> InstallState.Done(false, e.message ?: e.toString()) },
                    )
                }
                json(200, JSONObject().put("started", true))
            }

            method == "GET" && path == "/api/install/progress" -> {
                val j = JSONObject()
                when (val s = installState) {
                    is InstallState.Idle -> j.put("state", "idle")
                    is InstallState.Running -> j.put("state", "running").put("fraction", s.fraction).put("stage", s.stage)
                    is InstallState.Done -> j.put("state", "done").put("ok", s.ok).put("message", s.message)
                }
                json(200, j)
            }

            method == "POST" && path == "/api/reset" -> {
                sshManager.stopAsync()
                sessionManager.closeAll()
                runBlocking { rootfsInstaller.wipe() }
                json(200, JSONObject().put("ok", true))
            }

            // ---- 终端会话 ----
            method == "POST" && path == "/api/session/new" -> {
                val settings = settingsSnapshot()
                val session = runBlocking { sessionManager.newSession(settings) }
                json(200, JSONObject().put("ok", true).put("index", sessionManager.activeIndex.value))
            }

            method == "POST" && path == "/api/session/write" -> {
                val obj = JSONObject(readBody(session))
                val text = obj.optString("text")
                val active = sessionManager.activeSession()
                    ?: return json(404, JSONObject().put("error", "no active session"))
                val bytes = text.toByteArray(Charsets.UTF_8)
                runCatching { active.write(bytes, 0, bytes.size) }
                    .onFailure { return json(500, JSONObject().put("error", it.message)) }
                json(200, JSONObject().put("ok", true))
            }

            method == "GET" && path == "/api/session/screen" -> {
                val active = sessionManager.activeSession()
                    ?: return json(404, JSONObject().put("error", "no active session"))
                val text = runCatching { active.emulator?.mScreen?.getTranscriptText() ?: "" }.getOrDefault("")
                json(200, JSONObject().put("screen", text.takeLast(MAX_SCREEN_CHARS)))
            }

            // ---- 文件 ----
            method == "GET" && path == "/api/files" -> {
                val p = q?.get("path") ?: "/"
                val dir = File(p)
                if (!dir.exists() || !dir.isDirectory) {
                    return json(404, JSONObject().put("error", "not a dir: $p"))
                }
                val entries = FsOps.listDir(dir)
                val arr = JSONArray()
                entries.forEach { f ->
                    arr.put(
                        JSONObject()
                            .put("name", f.name)
                            .put("path", f.path)
                            .put("isDir", f.isDir)
                            .put("isLink", f.isLink)
                            .put("size", f.size)
                            .put("mode", f.mode)
                    )
                }
                json(200, JSONObject().put("path", dir.path).put("entries", arr))
            }

            method == "GET" && path == "/api/files/read" -> {
                val p = q?.get("path") ?: return json(400, JSONObject().put("error", "path required"))
                val f = File(p)
                if (!f.exists() || !f.isFile) return json(404, JSONObject().put("error", "not a file: $p"))
                if (f.length() > MAX_FILE_READ) return json(413, JSONObject().put("error", "file too large"))
                val content = f.readText(Charsets.UTF_8)
                json(200, JSONObject().put("path", f.path).put("content", content))
            }

            method == "POST" && path == "/api/files/write" -> {
                val obj = JSONObject(readBody(session))
                val p = obj.optString("path").ifBlank { return json(400, JSONObject().put("error", "path required")) }
                val content = obj.optString("content")
                runCatching {
                    val f = File(p)
                    f.parentFile?.mkdirs()
                    f.writeText(content, Charsets.UTF_8)
                }.onFailure { return json(500, JSONObject().put("error", it.message)) }
                json(200, JSONObject().put("ok", true))
            }

            // ---- SSH ----
            method == "POST" && path == "/api/ssh/start" -> {
                val settings = settingsSnapshot()
                val err = runBlocking { sshManager.startBlocking(settings) }
                if (err == null) json(200, JSONObject().put("ok", true))
                else json(500, JSONObject().put("error", err))
            }

            method == "POST" && path == "/api/ssh/stop" -> {
                runBlocking { sshManager.stopBlocking() }
                json(200, JSONObject().put("ok", true))
            }

            method == "GET" && path == "/api/ssh/status" -> json(
                200, JSONObject().put("status", sshStatusJson(sshManager.status.value))
            )

            else -> json(404, JSONObject().put("error", "no such api: $method $path"))
        }
    }

    // ------------------------------------------------------------------
    // JSON 辅助
    // ------------------------------------------------------------------

    private fun json(code: Int, obj: JSONObject): Response =
        NanoHTTPD.newFixedLengthResponse(
            Response.Status.lookup(code), "application/json; charset=utf-8", obj.toString()
        )

    /**
     * 读取请求体。必须用 NanoHTTPD 的 parseBody——其内部预读缓冲会消费 body，
     * 直接读 session.inputStream 会读到空/阻塞（GET 无 body 不受影响，POST/PUT 全挂）。
     */
    private fun readBody(session: IHTTPSession): String {
        val files = HashMap<String, String>()
        session.parseBody(files)
        return files["postData"] ?: ""
    }

    private fun settingsSnapshot(): AppSettings = runBlocking { settingsRepository.settings.first() }

    private fun statusJson(): JSONObject {
        val settings = settingsSnapshot()
        return JSONObject()
            .put("rootfsInstalled", rootfsInstaller.isInstalled())
            .put("prootPresent", File(rootfsInstaller.context.filesDir, "proot").exists())
            .put("sessions", sessionManager.sessions.value.size)
            .put("ssh", sshStatusJson(sshManager.status.value))
            .put("settings", settingsJson(settings))
    }

    private fun settingsJson(s: AppSettings): JSONObject = JSONObject()
        .put("themeMode", s.themeMode.name)
        .put("fontSize", s.fontSize)
        .put("colorSchemeId", s.colorSchemeId)
        .put("useNerdFont", s.useNerdFont)
        .put("startupCommand", s.startupCommand)
        .put("initialDir", s.initialDir)
        .put("aptMirrorId", s.aptMirrorId)
        .put("customDns", s.customDns)
        .put("keepForeground", s.keepForeground)
        .put("keepWakelock", s.keepWakelock)
        .put("keepBatteryWhitelist", s.keepBatteryWhitelist)
        .put("keepBoot", s.keepBoot)
        .put("keepRestore", s.keepRestore)
        .put("tmuxAttach", s.tmuxAttach)
        .put("sshEnabled", s.sshEnabled)
        .put("sshPort", s.sshPort)
        .put("sshListenAll", s.sshListenAll)
        .put("sshPassword", s.sshPassword)
        .put("sshAuthorizedKeys", s.sshAuthorizedKeys)
        .put("sshAutostart", s.sshAutostart)
        .put("debugApiEnabled", s.debugApiEnabled)

    private fun sshStatusJson(s: SshStatus): JSONObject = when (s) {
        is SshStatus.NotInstalled -> JSONObject().put("state", "not_installed")
        is SshStatus.Stopped -> JSONObject().put("state", "stopped")
        is SshStatus.Running -> JSONObject().put("state", "running").put("port", s.port).put("listenAll", s.listenAll)
    }

    // ------------------------------------------------------------------
    // JSON → AppSettings 部分更新见文件级 applyJson（可单测）
    // ------------------------------------------------------------------
}

/**
 * PUT /api/settings 的部分字段更新（文件级 internal，便于单元测试）。
 * 只覆盖 JSON 里出现的字段，其余保持不变；非法值回退原值。
 */
internal fun AppSettings.applyJson(obj: JSONObject): AppSettings {
    var s = this
    if (obj.has("themeMode")) s = s.copy(themeMode = runCatching { com.debdroid.app.prefs.ThemeMode.valueOf(obj.getString("themeMode")) }.getOrDefault(s.themeMode))
    if (obj.has("fontSize")) s = s.copy(fontSize = obj.getInt("fontSize").coerceIn(8, 32))
    if (obj.has("colorSchemeId")) s = s.copy(colorSchemeId = obj.getString("colorSchemeId"))
    if (obj.has("useNerdFont")) s = s.copy(useNerdFont = obj.getBoolean("useNerdFont"))
    if (obj.has("startupCommand")) s = s.copy(startupCommand = obj.getString("startupCommand"))
    if (obj.has("initialDir")) s = s.copy(initialDir = obj.getString("initialDir"))
    if (obj.has("aptMirrorId")) s = s.copy(aptMirrorId = runCatching { AptMirror.fromId(obj.getString("aptMirrorId")).id }.getOrDefault(s.aptMirrorId))
    if (obj.has("customDns")) s = s.copy(customDns = obj.getString("customDns"))
    if (obj.has("keepForeground")) s = s.copy(keepForeground = obj.getBoolean("keepForeground"))
    if (obj.has("keepWakelock")) s = s.copy(keepWakelock = obj.getBoolean("keepWakelock"))
    if (obj.has("keepBatteryWhitelist")) s = s.copy(keepBatteryWhitelist = obj.getBoolean("keepBatteryWhitelist"))
    if (obj.has("keepBoot")) s = s.copy(keepBoot = obj.getBoolean("keepBoot"))
    if (obj.has("keepRestore")) s = s.copy(keepRestore = obj.getBoolean("keepRestore"))
    if (obj.has("tmuxAttach")) s = s.copy(tmuxAttach = obj.getBoolean("tmuxAttach"))
    if (obj.has("sshEnabled")) s = s.copy(sshEnabled = obj.getBoolean("sshEnabled"))
    if (obj.has("sshPort")) s = s.copy(sshPort = obj.getInt("sshPort").coerceIn(1024, 65535))
    if (obj.has("sshListenAll")) s = s.copy(sshListenAll = obj.getBoolean("sshListenAll"))
    if (obj.has("sshPassword")) s = s.copy(sshPassword = obj.getString("sshPassword"))
    if (obj.has("sshAuthorizedKeys")) s = s.copy(sshAuthorizedKeys = obj.getString("sshAuthorizedKeys"))
    if (obj.has("sshAutostart")) s = s.copy(sshAutostart = obj.getBoolean("sshAutostart"))
    if (obj.has("debugApiEnabled")) s = s.copy(debugApiEnabled = obj.getBoolean("debugApiEnabled"))
    return s
}
