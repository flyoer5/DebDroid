package com.debdroid.app.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "debdroid_settings")

/**
 * 设置持久化仓库（FR-C3）。
 *
 * 并发安全：写入统一走 [update]，内部 read-transform-write 在单个 edit{} 内完成，
 * 并发更新（如捏合缩放字号 vs 开关）不丢写。
 * 新增字段：加 Key + toAppSettings 默认值兜底即可，无需版本迁移（Preferences 天然向后兼容）。
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val FONT_SIZE = intPreferencesKey("font_size")
        val COLOR_SCHEME = stringPreferencesKey("color_scheme")
        val USE_NERD_FONT = booleanPreferencesKey("use_nerd_font")
        val STARTUP_COMMAND = stringPreferencesKey("startup_command")
        val INITIAL_DIR = stringPreferencesKey("initial_dir")
        val APT_MIRROR = stringPreferencesKey("apt_mirror")
        val CUSTOM_DNS = stringPreferencesKey("custom_dns")
        val KEEP_FOREGROUND = booleanPreferencesKey("keep_foreground")
        val KEEP_WAKELOCK = booleanPreferencesKey("keep_wakelock")
        val KEEP_BATTERY = booleanPreferencesKey("keep_battery_whitelist")
        val KEEP_BOOT = booleanPreferencesKey("keep_boot")
        val KEEP_RESTORE = booleanPreferencesKey("keep_restore")
        val TMUX_ATTACH = booleanPreferencesKey("tmux_attach")
        val SSH_ENABLED = booleanPreferencesKey("ssh_enabled")
        val SSH_PORT = intPreferencesKey("ssh_port")
        val SSH_LISTEN_ALL = booleanPreferencesKey("ssh_listen_all")
        val SSH_PASSWORD = stringPreferencesKey("ssh_password")
        val SSH_AUTHORIZED_KEYS = stringPreferencesKey("ssh_authorized_keys")
        val SSH_AUTOSTART = booleanPreferencesKey("ssh_autostart")
        val FILE_BOOKMARKS = stringPreferencesKey("file_bookmarks")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toAppSettings() }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p -> p.writeSettings(transform(p.toAppSettings())) }
    }

    private fun Preferences.toAppSettings(): AppSettings = AppSettings(
        themeMode = this[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.FOLLOW_SYSTEM,
        fontSize = (this[Keys.FONT_SIZE] ?: 14).coerceIn(8, 32),
        colorSchemeId = this[Keys.COLOR_SCHEME] ?: "dracula",
        useNerdFont = this[Keys.USE_NERD_FONT] ?: true,
        startupCommand = this[Keys.STARTUP_COMMAND] ?: "/bin/bash --login",
        initialDir = this[Keys.INITIAL_DIR] ?: "/root",
        aptMirrorId = this[Keys.APT_MIRROR] ?: AptMirror.OFFICIAL.id,
        customDns = this[Keys.CUSTOM_DNS] ?: "",
        keepForeground = this[Keys.KEEP_FOREGROUND] ?: true,
        keepWakelock = this[Keys.KEEP_WAKELOCK] ?: false,
        keepBatteryWhitelist = this[Keys.KEEP_BATTERY] ?: true,
        keepBoot = this[Keys.KEEP_BOOT] ?: false,
        keepRestore = this[Keys.KEEP_RESTORE] ?: true,
        tmuxAttach = this[Keys.TMUX_ATTACH] ?: true,
        sshEnabled = this[Keys.SSH_ENABLED] ?: false,
        sshPort = (this[Keys.SSH_PORT] ?: 8022).coerceIn(1024, 65535),
        sshListenAll = this[Keys.SSH_LISTEN_ALL] ?: false,
        sshPassword = this[Keys.SSH_PASSWORD] ?: "",
        sshAuthorizedKeys = this[Keys.SSH_AUTHORIZED_KEYS] ?: "",
        sshAutostart = this[Keys.SSH_AUTOSTART] ?: true,
        fileBookmarks = this[Keys.FILE_BOOKMARKS] ?: "",
    )

    private fun androidx.datastore.preferences.core.MutablePreferences.writeSettings(s: AppSettings) {
        this[Keys.THEME_MODE] = s.themeMode.name
        this[Keys.FONT_SIZE] = s.fontSize
        this[Keys.COLOR_SCHEME] = s.colorSchemeId
        this[Keys.USE_NERD_FONT] = s.useNerdFont
        this[Keys.STARTUP_COMMAND] = s.startupCommand
        this[Keys.INITIAL_DIR] = s.initialDir
        this[Keys.APT_MIRROR] = s.aptMirrorId
        this[Keys.CUSTOM_DNS] = s.customDns
        this[Keys.KEEP_FOREGROUND] = s.keepForeground
        this[Keys.KEEP_WAKELOCK] = s.keepWakelock
        this[Keys.KEEP_BATTERY] = s.keepBatteryWhitelist
        this[Keys.KEEP_BOOT] = s.keepBoot
        this[Keys.KEEP_RESTORE] = s.keepRestore
        this[Keys.TMUX_ATTACH] = s.tmuxAttach
        this[Keys.SSH_ENABLED] = s.sshEnabled
        this[Keys.SSH_PORT] = s.sshPort
        this[Keys.SSH_LISTEN_ALL] = s.sshListenAll
        this[Keys.SSH_PASSWORD] = s.sshPassword
        this[Keys.SSH_AUTHORIZED_KEYS] = s.sshAuthorizedKeys
        this[Keys.SSH_AUTOSTART] = s.sshAutostart
        this[Keys.FILE_BOOKMARKS] = s.fileBookmarks
    }
}
