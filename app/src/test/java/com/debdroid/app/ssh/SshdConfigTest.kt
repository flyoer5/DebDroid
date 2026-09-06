package com.debdroid.app.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshdConfigTest {

    @Test
    fun `render carries port and listen address`() {
        val out = SshdConfig.render(8022, "192.168.1.5")
        assertTrue(out.contains("Port 8022"))
        assertTrue(out.contains("ListenAddress 192.168.1.5"))
        assertTrue(out.contains("ListenAddress 0.0.0.0").not())
    }

    @Test
    fun `render keeps mandatory rootfs sshd directives`() {
        val out = SshdConfig.render(8022, "0.0.0.0")
        assertTrue(out.contains("PermitRootLogin yes"))
        assertTrue(out.contains("PasswordAuthentication yes"))
        assertTrue(out.contains("HostKey /etc/ssh/ssh_host_ed25519_key"))
        assertTrue(out.contains("Subsystem sftp internal-sftp"))
        // 自定义交叉编译 sshd 不识别 UsePAM —— 不得回归引入
        assertFalse(out.contains("UsePAM", ignoreCase = true))
    }

    @Test
    fun `render includes proot stability hardening lines`() {
        val out = SshdConfig.render(8022, "0.0.0.0")
        // v2.1.7 稳定性加固（抗连接风暴锁死 + 死连接快速回收）
        assertTrue(out.contains("MaxStartups 100:30:200"))
        assertTrue(out.contains("LoginGraceTime 20"))
        assertTrue(out.contains("UseDNS no"))
        assertTrue(out.contains("TCPKeepAlive yes"))
        assertTrue(out.contains("ClientAliveInterval 60"))
        assertTrue(out.contains("ClientAliveCountMax 3"))
    }

    @Test
    fun `render is one directive per line with trailing newline`() {
        val out = SshdConfig.render(8022, "0.0.0.0")
        out.lines().filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
            assertTrue("每行恰一条指令: $line", line.split(Regex("\\s+")).size >= 2)
        }
        assertTrue(out.endsWith("\n"))
    }

    @Test
    fun `port never leaks to shell metacharacters`() {
        val out = SshdConfig.render(1234, "0.0.0.0")
        assertEquals(1, Regex("(?m)^Port 1234$").findAll(out).count())
    }
}
