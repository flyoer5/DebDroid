package com.debdroid.app.rootfs

import com.debdroid.app.prefs.AptMirror
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootfsInstallerTest {

    @Test
    fun `sources list covers trixie suites and security`() {
        val out = RootfsInstaller.buildSourcesList(AptMirror.TUNA)
        assertTrue(out.contains("deb ${AptMirror.TUNA.url} trixie main contrib non-free non-free-firmware"))
        assertTrue(out.contains("deb ${AptMirror.TUNA.url} trixie-updates"))
        assertTrue(out.contains("deb ${AptMirror.TUNA.securityUrl} trixie-security"))
    }

    @Test
    fun `sources list uses given mirror urls`() {
        val out = RootfsInstaller.buildSourcesList(AptMirror.USTC)
        assertTrue(out.contains(AptMirror.USTC.url))
        assertTrue(!out.contains(AptMirror.TUNA.url))
    }

    @Test
    fun `zh locale defaults to tuna mirror`() {
        assertEquals(AptMirror.TUNA, RootfsInstaller.defaultMirrorForLocale(Locale("zh", "CN")))
        assertEquals(AptMirror.OFFICIAL, RootfsInstaller.defaultMirrorForLocale(Locale.US))
        assertEquals(AptMirror.OFFICIAL, RootfsInstaller.defaultMirrorForLocale(Locale.ENGLISH))
    }
}
