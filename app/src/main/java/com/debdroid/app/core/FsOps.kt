package com.debdroid.app.core

import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * 文件系统纯函数集（FR-F4 权限串、FR-F1 排序、大小格式化）。
 * 全部为纯函数/静态方法，便于单元测试。
 */
object FsOps {

    /** 文件信息（lstat 结果）。 */
    data class FileInfo(
        val name: String,
        val isDir: Boolean,
        val isLink: Boolean,
        val size: Long,
        val mtime: Long,
        val mode: Int,
        val path: String,
    ) {
        /** POSIX 权限串：drwxr-xr-x / -rw-r--r-- / lrwxrwxrwx（FR-F4） */
        val permissionString: String
            get() = buildPermissionString(mode, isDir, isLink)
    }

    /** 由 mode 位构造权限串（纯函数）。 */
    fun buildPermissionString(mode: Int, isDir: Boolean, isLink: Boolean): String = buildString {
        append(if (isLink) 'l' else if (isDir) 'd' else '-')
        for (shift in intArrayOf(6, 3, 0)) {
            val bits = (mode shr shift) and 7
            append(if (bits and 4 != 0) 'r' else '-')
            append(if (bits and 2 != 0) 'w' else '-')
            append(if (bits and 1 != 0) 'x' else '-')
        }
    }

    /** 读取目录（含 lstat 元数据），失败抛异常由调用方转 UiState.Error。 */
    fun listDir(dir: File): List<FileInfo> {
        val out = ArrayList<FileInfo>()
        dir.listFiles()?.forEach { f ->
            runCatching {
                val st = Os.lstat(f.path)
                out += FileInfo(
                    name = f.name,
                    isDir = (st.st_mode and OsConstants.S_IFMT) == OsConstants.S_IFDIR,
                    isLink = (st.st_mode and OsConstants.S_IFMT) == OsConstants.S_IFLNK,
                    size = st.st_size,
                    mtime = st.st_mtime,
                    mode = st.st_mode,
                    path = f.path,
                )
            }
        }
        return out
    }

    /** 排序（FR-F2）：目录优先 + 指定键。名称比较按本地化忽略大小写。 */
    fun sort(files: List<FileInfo>, by: SortBy, descending: Boolean): List<FileInfo> {
        val cmp = Comparator<FileInfo> { a, b ->
            // 目录永远排前
            if (a.isDir != b.isDir) return@Comparator if (a.isDir) -1 else 1
            val r = when (by) {
                SortBy.NAME -> a.name.compareTo(b.name, ignoreCase = true)
                SortBy.SIZE -> a.size.compareTo(b.size)
                SortBy.TIME -> a.mtime.compareTo(b.mtime)
            }
            if (descending) -r else r
        }
        return files.sortedWith(cmp)
    }

    enum class SortBy { NAME, SIZE, TIME }

    /** 人类可读大小：118M / 3.4K / 612B。 */
    fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1fK", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1fM", bytes / 1024.0 / 1024.0)
        else -> String.format(Locale.ROOT, "%.1fG", bytes / 1024.0 / 1024.0 / 1024.0)
    }

    /** 递归复制（IO 线程调用）。 */
    fun copyRecursive(src: File, dst: File) {
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.forEach { copyRecursive(it, File(dst, it.name)) }
        } else {
            dst.parentFile?.mkdirs()
            src.copyTo(dst, overwrite = true)
        }
    }

    /** 递归移动：先尝试 rename，失败退化为复制+删除。 */
    fun moveRecursive(src: File, dst: File) {
        if (src.renameTo(dst)) return
        copyRecursive(src, dst)
        src.deleteRecursively()
    }

    private val locale = Locale.ROOT
}
