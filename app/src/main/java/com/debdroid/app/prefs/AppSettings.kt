package com.debdroid.app.prefs

/**
 * 应用设置数据类。字段表见 docs/requirements.md §7。
 * 全部字段有合理默认值；DataStore 缺键时回退默认（见 SettingsRepository）。
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.FOLLOW_SYSTEM,
    val fontSize: Int = 14,                     // 8–32
    val colorSchemeId: String = "dracula",      // 8 套配色，见 ui.theme.TerminalColors
    val useNerdFont: Boolean = true,
    val startupCommand: String = "/bin/bash --login",
    val initialDir: String = "/root",
    val aptMirrorId: String = AptMirror.OFFICIAL.id,
    val customDns: String = "",                 // 空 = 不覆盖 resolv.conf
    val keepForeground: Boolean = true,
    val keepWakelock: Boolean = false,
    val keepBatteryWhitelist: Boolean = true,
    val keepBoot: Boolean = false,
    val keepRestore: Boolean = true,            // 进程被杀后自动恢复会话（FR-S4）
    val tmuxAttach: Boolean = true,             // 默认 tmux new -A -s main（FR-S3）
    val sshEnabled: Boolean = false,
    val sshPort: Int = 8022,                    // 1024–65535
    val sshListenAll: Boolean = false,          // false = 仅局域网
    val sshPassword: String = "",               // 空 = 禁用密码登录
    val sshAuthorizedKeys: String = "",         // 多行公钥
    val sshAutostart: Boolean = true,
    val fileBookmarks: String = "",             // 序列化书签
)

enum class ThemeMode { FOLLOW_SYSTEM, DARK, LIGHT }

/** apt 镜像源（FR-C1）。URL 必须完整含 security 仓库（v1.0.24 修复不回退）。 */
enum class AptMirror(val id: String, val url: String, val securityUrl: String) {
    OFFICIAL("official", "https://deb.debian.org/debian", "https://deb.debian.org/debian-security/"),
    TUNA("tuna", "https://mirrors.tuna.tsinghua.edu.cn/debian", "https://mirrors.tuna.tsinghua.edu.cn/debian-security/"),
    USTC("ustc", "https://mirrors.ustc.edu.cn/debian", "https://mirrors.ustc.edu.cn/debian-security/"),
    ALIYUN("aliyun", "https://mirrors.aliyun.com/debian/", "https://mirrors.aliyun.com/debian-security/"),
    TENCENT("tencent", "https://mirrors.cloud.tencent.com/debian/", "https://mirrors.cloud.tencent.com/debian-security/"),
    ;

    companion object {
        fun fromId(id: String): AptMirror = entries.firstOrNull { it.id == id } ?: OFFICIAL
    }
}
