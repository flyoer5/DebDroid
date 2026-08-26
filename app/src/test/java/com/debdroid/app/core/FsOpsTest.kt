package com.debdroid.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FsOpsTest {

    @Test
    fun `buildPermissionString renders POSIX bits`() {
        assertEquals("drwxr-xr-x", FsOps.buildPermissionString(0b111101101, true, false))
        assertEquals("-rw-r--r--", FsOps.buildPermissionString(0b100100100, false, false))
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
}
