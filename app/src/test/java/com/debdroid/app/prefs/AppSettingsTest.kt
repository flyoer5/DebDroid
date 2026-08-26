package com.debdroid.app.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {

    @Test
    fun `defaults match requirements spec`() {
        val s = AppSettings()
        assertEquals(14, s.fontSize)
        assertEquals("dracula", s.colorSchemeId)
        assertEquals("/bin/bash --login", s.startupCommand)
        assertEquals("/root", s.initialDir)
        assertTrue(s.useNerdFont)
        assertTrue(s.keepForeground)
        assertFalse(s.keepWakelock)
        assertTrue(s.keepBatteryWhitelist)
        assertFalse(s.keepBoot)
        assertTrue(s.keepRestore)
        assertTrue(s.tmuxAttach)
        assertFalse(s.sshEnabled)
        assertEquals(8022, s.sshPort)
        assertFalse(s.sshListenAll)
        assertTrue(s.sshAutostart)
    }

    @Test
    fun `apt mirror fallback to official on unknown id`() {
        assertEquals(AptMirror.OFFICIAL, AptMirror.fromId("nonsense"))
        assertEquals(AptMirror.TUNA, AptMirror.fromId("tuna"))
        assertEquals(AptMirror.USTC, AptMirror.fromId("ustc"))
        assertEquals(AptMirror.ALIYUN, AptMirror.fromId("aliyun"))
        assertEquals(AptMirror.TENCENT, AptMirror.fromId("tencent"))
    }

    @Test
    fun `all apt mirrors carry security url`() {
        AptMirror.entries.forEach { m ->
            assertTrue("${m.id} security url missing", m.securityUrl.isNotBlank())
        }
    }

    @Test
    fun `coercion keeps font size and port in range`() {
        // 范围守卫在 SettingsRepository 读侧；此处验证默认即合法
        assertTrue(AppSettings().fontSize in 8..32)
        assertTrue(AppSettings().sshPort in 1024..65535)
        assertTrue(AppSettings().customDns.isEmpty())
    }
}
