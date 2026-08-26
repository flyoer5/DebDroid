package com.debdroid.app.ui.files

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.debdroid.app.core.FsOps
import com.debdroid.app.core.FsOps.FileInfo
import com.debdroid.app.core.FsOps.SortBy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 文件管理器（FR-F1~F5，architecture.md §3.7）：
 * 左栏导航（根/家/内部存储/书签），右栏文件列表（排序/多选/批操作/权限串/图标）。
 * 存储权限按需请求（避免启动时遮挡向导与终端）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    onBack: () -> Unit,
    onOpenFile: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val internalRoot = com.debdroid.app.DebDroidApp.instance.rootfsInstaller.rootfsDir()
    val externalRoot = remember { Environment.getExternalStorageDirectory() }

    var currentDir by remember { mutableStateOf(externalRoot) }
    var dirStack by remember { mutableStateOf(listOf<File>()) }
    var entries by remember { mutableStateOf<List<FileInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var sortBy by remember { mutableStateOf(SortBy.NAME) }
    var descending by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var deleteDlg by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var bookmarkDirs by remember { mutableStateOf(listOf(internalRoot)) }
    var toast by remember { mutableStateOf<String?>(null) }

    val hasPermission = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_EXTERNAL_STORAGE
    ) == PackageManager.PERMISSION_GRANTED

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) refreshTick++ }

    if (!hasPermission) {
        // 进入文件屏且未授权时请求一次（LaunchedEffect 防组合期重复触发）
        androidx.compose.runtime.LaunchedEffect(Unit) {
            permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    // 目录 / 排序 / 刷新标记变化时加载；首次进入也触发
    androidx.compose.runtime.LaunchedEffect(currentDir.path, sortBy, descending, refreshTick) {
        loading = true
        loadError = null
        val result = withContext(Dispatchers.IO) {
            runCatching { FsOps.listDir(currentDir) }
                .map { FsOps.sort(it, sortBy, descending) }
        }
        result.onSuccess { entries = it }
            .onFailure { e -> loadError = e.message }
        loading = false
    }

    if (toast != null) {
        android.widget.Toast.makeText(context, toast, android.widget.Toast.LENGTH_SHORT).show()
        toast = null
    }

    Column(Modifier.fillMaxSize()) {
        // 顶栏：返回 + 路径 + 排序 + 更多
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                if (dirStack.isNotEmpty()) {
                    currentDir = dirStack.last()
                    dirStack = dirStack.dropLast(1)
                    refreshTick++
                } else onBack()
            }) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                currentDir.path,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box {
                IconButton(onClick = { sortMenu = true }) {
                    Text("⇅", style = MaterialTheme.typography.titleMedium) // 排序
                }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    SortBy.entries.forEach { by ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    when (by) {
                                        SortBy.NAME -> "名称"
                                        SortBy.SIZE -> "大小"
                                        SortBy.TIME -> "修改时间"
                                    } + if (sortBy == by && !descending) " ↑" else if (sortBy == by) " ↓" else ""
                                )
                            },
                            onClick = {
                                sortMenu = false
                                if (sortBy == by) descending = !descending
                                else { sortBy = by; descending = false }
                                refreshTick++
                            },
                        )
                    }
                }
            }
            if (selection.isNotEmpty()) {
                IconButton(onClick = {
                    if (selection.size == entries.size) selection = emptySet()
                    else selection = entries.map { it.path }.toSet()
                }) {
                    Icon(Icons.Filled.Check, contentDescription = "全选/取消")
                }
            }
        }

        // 左栏导航
        Row(Modifier.weight(1f)) {
            Column(
                Modifier.width(96.dp).background(MaterialTheme.colorScheme.surfaceContainer),
            ) {
                NavItem("根 /", externalRoot) {
                    dirStack = dirStack + currentDir
                    currentDir = externalRoot
                    refreshTick++
                }
                NavItem("内部 rootfs", internalRoot) {
                    dirStack = dirStack + currentDir
                    currentDir = internalRoot
                    refreshTick++
                }
                NavItem("下载", File(externalRoot, "Download")) {
                    dirStack = dirStack + currentDir
                    currentDir = File(externalRoot, "Download")
                    refreshTick++
                }
                NavItem("书签", null, selected = false) {
                    toast = "书签功能：${bookmarkDirs.size} 个位置"
                }
                bookmarkDirs.take(3).forEach { dir ->
                    NavItem("★ ${dir.name}", dir) {
                        dirStack = dirStack + currentDir
                        currentDir = dir
                        refreshTick++
                    }
                }
            }

            // 右栏文件列表
            Box(Modifier.weight(1f)) {
                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("加载中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    loadError != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "无法读取：$loadError",
                            color = MaterialTheme.colorScheme.error,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }

                    entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("此文件夹为空", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    else -> LazyColumn {
                        items(entries, key = { it.path }) { f ->
                            FileRow(
                                info = f,
                                selected = f.path in selection,
                                inSelectionMode = selection.isNotEmpty(),
                                onToggleSelect = { path ->
                                    selection = if (path in selection) selection - path else selection + path
                                },
                                onClick = {
                                    if (selection.isNotEmpty()) {
                                        selection = if (f.path in selection) selection - f.path else selection + f.path
                                    } else if (f.isDir) {
                                        dirStack = dirStack + currentDir
                                        currentDir = File(f.path)
                                        refreshTick++
                                    } else {
                                        onOpenFile(f.path)
                                    }
                                },
                                onBookmark = {
                                    if (f.isDir) {
                                        bookmarkDirs = if (dir in bookmarkDirs) {
                                            toast = "已取消书签"
                                            bookmarkDirs - dir
                                        } else {
                                            toast = "已加书签 ★"
                                            bookmarkDirs + dir
                                        }
                                    }
                                    Unit
                                },
                            )
                        }
                    }
                }
            }
        }

        // 底部批操作栏
        if (selection.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("已选 ${selection.size}", style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = { deleteDlg = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = null, Modifier.size(18.dp))
                    Text(" 删除")
                }
                TextButton(onClick = {
                    val sel = entries.filter { it.path in selection }
                    scope.launch(Dispatchers.IO) {
                        val src = sel.firstOrNull()?.let { File(it.path).parentFile }
                        if (src != null) {
                            val dst = if (src == externalRoot) internalRoot else externalRoot
                            sel.forEach { info ->
                                val f = File(info.path)
                                runCatching { FsOps.copyRecursive(f, File(dst, f.name)) }
                            }
                        }
                    }
                    selection = emptySet()
                    toast = "已复制到对侧"
                }) { Text("复制到对侧") }
                TextButton(onClick = {
                    val sel = entries.filter { it.path in selection }
                    scope.launch(Dispatchers.IO) {
                        val src = sel.firstOrNull()?.let { File(it.path).parentFile }
                        if (src != null) {
                            val dst = if (src == externalRoot) internalRoot else externalRoot
                            sel.forEach { info ->
                                val f = File(info.path)
                                runCatching { FsOps.moveRecursive(f, File(dst, f.name)) }
                            }
                        }
                    }
                    selection = emptySet()
                    toast = "已移动到对侧"
                }) { Text("移动到对侧") }
                IconButton(onClick = { selection = emptySet() }) {
                    Icon(Icons.Filled.Close, contentDescription = "取消选择")
                }
            }
        }
    }

    if (deleteDlg) {
        AlertDialog(
            onDismissRequest = { deleteDlg = false },
            title = { Text("删除所选文件？") },
            text = { Text("将永久删除 ${selection.size} 个文件/目录，此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteDlg = false
                    val sel = selection
                    selection = emptySet()
                    scope.launch(Dispatchers.IO) {
                        sel.forEach { path -> runCatching { File(path).deleteRecursively() } }
                    }
                    refreshTick++
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteDlg = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun NavItem(label: String, dir: File?, selected: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
            .padding(vertical = 10.dp, horizontal = 8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (dir != null) {
            Text(
                dir.name,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun FileRow(
    info: FileInfo,
    selected: Boolean,
    inSelectionMode: Boolean,
    onToggleSelect: (String) -> Unit,
    onClick: () -> Unit,
    onBookmark: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (inSelectionMode || selected) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggleSelect(info.path) },
            )
        } else {
            Text(
                when {
                    info.isLink -> "🔗"
                    info.isDir -> "📁"
                    info.name.endsWith(".py") -> "🐍"
                    info.name.endsWith(".apk") -> "📦"
                    info.name.endsWith(".mp3") -> "🎵"
                    info.name.endsWith(".mp4") || info.name.endsWith(".mkv") -> "🎬"
                    info.name.endsWith(".jpg") || info.name.endsWith(".png") -> "🖼"
                    info.name.endsWith(".md") -> "📝"
                    info.name.endsWith(".tar.xz") || info.name.endsWith(".tar.gz") -> "🗜"
                    else -> "📄"
                },
                fontSize = androidx.compose.ui.unit.TextUnit(18f, androidx.compose.ui.unit.TextUnitType.Sp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                info.name,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${info.permissionString}  ${if (info.isDir) "" else FsOps.humanSize(info.size)}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (info.isDir) {
            Text("★", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
        }
    }
}
