package com.debdroid.app.ssh

import com.debdroid.app.prefs.AppSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.1.11：sshd 运行中设置变更需重启判定（纯函数）。 */
class SshConfigChangeTest {

    private val base = AppSettings()

    @Test
    fun `same settings need no restart`() {
        assertFalse(SshManager.sshConfigChanged(base, base))
        assertFalse(SshManager.sshConfigChanged(base, base.copy(sshEnabled = !base.sshEnabled)))
    }

    @Test
    fun `port password keys listen changes need restart`() {
        assertTrue(SshManager.sshConfigChanged(base, base.copy(sshPort = 8023)))
        assertTrue(SshManager.sshConfigChanged(base, base.copy(sshListenAll = true)))
        assertTrue(SshManager.sshConfigChanged(base, base.copy(sshPassword = "secret")))
        assertTrue(SshManager.sshConfigChanged(base, base.copy(sshAuthorizedKeys = "ssh-ed25519 AAA")))
    }

    @Test
    fun `unrelated settings need no restart`() {
        assertFalse(SshManager.sshConfigChanged(base, base.copy(sshAutostart = false)))
        assertFalse(SshManager.sshConfigChanged(base, base.copy(startupCommand = "htop")))
        assertFalse(SshManager.sshConfigChanged(base, base.copy(aptMirrorId = "aliyun")))
        assertFalse(SshManager.sshConfigChanged(base, base.copy(customDns = "1.1.1.1")))
    }
}
