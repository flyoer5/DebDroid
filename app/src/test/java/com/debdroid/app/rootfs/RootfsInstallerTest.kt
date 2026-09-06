package com.debdroid.app.rootfs

import com.debdroid.app.prefs.AptMirror
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `zh locale defaults to aliyun mirror`() {
        assertEquals(AptMirror.ALIYUN, RootfsInstaller.defaultMirrorForLocale(Locale("zh", "CN")))
        assertEquals(AptMirror.OFFICIAL, RootfsInstaller.defaultMirrorForLocale(Locale.US))
        assertEquals(AptMirror.OFFICIAL, RootfsInstaller.defaultMirrorForLocale(Locale.ENGLISH))
    }

    // ---- v2.1.9 guest 时区跟随（纯函数层） ----

    // ---- v2.1.12 resolv.conf 内容（纯函数层） ----

    @Test
    fun `resolvContent blank custom dns falls back to defaults`() {
        val out = RootfsInstaller.resolvContent("")
        assertTrue(out.contains("nameserver 8.8.8.8"))
        assertTrue(out.contains("nameserver 223.5.5.5"))
    }

    @Test
    fun `resolvContent carries custom dns verbatim`() {
        val out = RootfsInstaller.resolvContent("nameserver 223.5.5.5\nnameserver 1.1.1.1")
        assertTrue(out.contains("223.5.5.5"))
        assertTrue(out.contains("1.1.1.1"))
        assertTrue(!out.contains("8.8.8.8"))
    }

    @Test
    fun `timezoneTarget maps valid android tz id into zoneinfo`() {
        val root = java.nio.file.Files.createTempDirectory("dd-tz").toFile()
        try {
            val asia = File(root, "Asia").apply { mkdirs() }
            File(asia, "Shanghai").writeText("TZif") // 伪 zoneinfo 文件
            val hit = RootfsInstaller.timezoneTarget(root, "Asia/Shanghai")
            assertEquals(File(asia, "Shanghai"), hit)
            // 本地缺条目 → null（保持 UTC）
            assertNull(RootfsInstaller.timezoneTarget(root, "Europe/Paris"))
            // 目录而非文件 → null
            assertNull(RootfsInstaller.timezoneTarget(root, "Asia"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `timezoneTarget rejects path traversal and garbage ids`() {
        val root = java.nio.file.Files.createTempDirectory("dd-tz").toFile()
        try {
            assertNull(RootfsInstaller.timezoneTarget(root, "../etc/passwd"))
            assertNull(RootfsInstaller.timezoneTarget(root, ".."))
            assertNull(RootfsInstaller.timezoneTarget(root, "Asia/../../etc"))
            assertNull(RootfsInstaller.timezoneTarget(root, ""))
            assertNull(RootfsInstaller.timezoneTarget(root, "/"))
            assertNull(RootfsInstaller.timezoneTarget(root, "Asia Shanghai"))
            assertNull(RootfsInstaller.timezoneTarget(root, "Asia\\Shanghai"))
        } finally {
            root.deleteRecursively()
        }
    }
}
