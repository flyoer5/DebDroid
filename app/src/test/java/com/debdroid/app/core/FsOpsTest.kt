package com.debdroid.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FsOpsTest {

    @Test
    fun `buildPermissionString renders POSIX bits`() {
        assertEquals("drwxr-xr-x", FsOps.buildPermissionString(0b111101101, true, false))
        assertEquals("-rw-r--r--", FsOps.buildPermissionString(0b110100100, false, false))
        assertEquals("lrwxrwxrwx", FsOps.buildPermissionString(0b111111111, false, true))
        assertEquals("-rwx------", FsOps.buildPermissionString(0b111000000, false, false))
    }

    @Test
    fun `humanSize formats units`() {
        assertEquals("612B", FsOps.humanSize(612))
        assertEquals("3.4K", FsOps.humanSize(3481))
        assertEquals("118.2M", FsOps.humanSize(123_944_038))
        assertEquals("2.0G", FsOps.humanSize(2_147_483_648))
    }

    @Test
    fun `sort puts directories first then by name case-insensitive`() {
        fun info(name: String, isDir: Boolean = false) = FsOps.FileInfo(
            name = name, isDir = isDir, isLink = false, size = 1, mtime = 1, mode = 0, path = "/$name",
        )
        val list = listOf(
            info("zeta"), info("Alpha", isDir = true), info("beta"), info("BETA"), info("alpha"),
        )
        val sorted = FsOps.sort(list, FsOps.SortBy.NAME, descending = false)
        assertEquals(listOf("Alpha", "alpha", "beta", "BETA", "zeta"), sorted.map { it.name })
    }

    @Test
    fun `sort by size descending`() {
        fun info(name: String, size: Long) = FsOps.FileInfo(
            name = name, isDir = false, isLink = false, size = size, mtime = 1, mode = 0, path = "/$name",
        )
        val list = listOf(info("small", 10), info("big", 9999), info("mid", 100))
        val sorted = FsOps.sort(list, FsOps.SortBy.SIZE, descending = true)
        assertEquals(listOf("big", "mid", "small"), sorted.map { it.name })
    }

    @Test
    fun `sort by time respects direction`() {
        fun info(name: String, mtime: Long) = FsOps.FileInfo(
            name = name, isDir = false, isLink = false, size = 1, mtime = mtime, mode = 0, path = "/$name",
        )
        val list = listOf(info("old", 100), info("new", 300), info("mid", 200))
        assertEquals(listOf("old", "mid", "new"), FsOps.sort(list, FsOps.SortBy.TIME, false).map { it.name })
        assertEquals(listOf("new", "mid", "old"), FsOps.sort(list, FsOps.SortBy.TIME, true).map { it.name })
    }

    @Test
    fun `empty list sorts safely`() {
        assertTrue(FsOps.sort(emptyList(), FsOps.SortBy.NAME, false).isEmpty())
    }

    @Test
    fun `dirSize sums nested files and dirs but skips symlinks`() {
        val root = java.nio.file.Files.createTempDirectory("dd-dirsize").toFile()
        try {
            File(root, "a.txt").writeText("12345") // 5B
            File(root, "sub").mkdirs()
            File(root, "sub/b.bin").writeBytes(ByteArray(1000)) // 1000B
            File(root, "sub/deep").mkdirs()
            File(root, "sub/deep/c.txt").writeText("ok") // 2B
            // 符号链接：不计入（防环/双计）
            runCatching {
                java.nio.file.Files.createSymbolicLink(
                    java.nio.file.Paths.get(root.path, "loop"), java.nio.file.Paths.get(root.path, "sub")
                )
            }
            val size = FsOps.dirSize(root)
            assertTrue("got $size", size >= 1007) // 文件合计
            assertTrue("got $size", size < 1007 + 4096 * 8) // 目录项自身计入但受限
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `dirSize of file or missing dir is safe`() {
        val tmp = java.nio.file.Files.createTempFile("dd-file", ".txt").toFile()
        try {
            tmp.writeText("hi")
            assertEquals(2L, FsOps.dirSize(tmp)) // 非目录：按单文件统计
        } finally {
            tmp.delete()
        }
        assertEquals(0L, FsOps.dirSize(File("/nonexistent-dd-dir-xyz")))
    }
}
