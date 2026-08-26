package com.debdroid.app

import android.app.Application
import com.debdroid.app.prefs.SettingsRepository
import com.debdroid.app.rootfs.RootfsInstaller
import com.debdroid.app.session.SessionManager
import com.debdroid.app.ssh.SshManager

/**
 * 应用入口：持有全部单例仓库/管理器，经 [instance] 供 Compose 与后台组件取用。
 * 初始化均为轻量构造（不落盘）；重活由各管理器按需在 IO 线程执行。
 */
class DebDroidApp : Application() {

    lateinit var settingsRepository: SettingsRepository
    lateinit var rootfsInstaller: RootfsInstaller
    lateinit var sshManager: SshManager
    lateinit var sessionManager: SessionManager

    override fun onCreate() {
        super.onCreate()
        instance = this
        settingsRepository = SettingsRepository(this)
        rootfsInstaller = RootfsInstaller(this)
        sshManager = SshManager(this, rootfsInstaller)
        sessionManager = SessionManager(rootfsInstaller, sshManager)
        sshManager.refreshStatus() // 进程重启后恢复 SSH 状态展示（FR-H2）
    }

    companion object {
        lateinit var instance: DebDroidApp
            private set
    }
}
