package com.debdroid.app.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.debdroid.app.ui.theme.TerminalColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 文本编辑器（FR-E1~E4，architecture.md §3.8）：
 * 行号同步列 + 编辑区；未保存拦截（三按钮）；撤销/重做；只读；状态栏（行/字符）。
 * 文件读写走 IO 线程；新文件（filePath=null）先编辑、保存时落盘。
 */
@Composable
fun TextEditorScreen(
    filePath: String?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 编辑器配色跟随全局设置（L5：此前硬编码 dracula，用户在设置选配色后编辑器仍不变）
    var scheme by remember { mutableStateOf(TerminalColors.byId("dracula")) }
    LaunchedEffect(Unit) {
        val id = com.debdroid.app.DebDroidApp.instance.settingsRepository.settings.first().colorSchemeId
        scheme = TerminalColors.byId(id)
    }

    var text by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var saveErrorMsg by remember { mutableStateOf<String?>(null) }
    var readOnly by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    var pendingBack by remember { mutableStateOf(false) }
    var savedToast by remember { mutableStateOf(false) }
    var undoStack by remember { mutableStateOf(ArrayDeque<String>()) }
    var redoStack by remember { mutableStateOf(ArrayDeque<String>()) }
    // 磁盘/加载基准内容：undo/redo 撤回到与之一致时 dirty 复位（M7）
    var lastSavedText by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf(filePath?.substringAfterLast('/') ?: "untitled.txt") }

    LaunchedEffect(filePath) {
        if (filePath != null) {
            val result = withContext(Dispatchers.IO) {
                runCatching { File(filePath!!).readText() }
            }
            result.onSuccess {
                text = it
                lastSavedText = it
                loaded = true
            }.onFailure { e -> loadError = e.message }
        } else {
            loaded = true
        }
    }

    fun markDirty(newText: String) {
        if (newText != text) {
            undoStack = undoStack.plus(text).takeLast(100).let { ArrayDeque(it) }
            redoStack = ArrayDeque()
            text = newText
            dirty = true
        }
    }

    fun save() {
        if (readOnly) {
            saveErrorMsg = "只读模式，无法保存"
            return
        }
        if (filePath != null && !loaded) {
            // 读失败/未加载完成：禁止保存，防空内容覆盖原文件（审查定位 M5）
            saveErrorMsg = "文件读取失败，无法保存"
            return
        }
        if (filePath == null) {
            // 无路径时展示保存对话框简化版：直接按文件名存到 Download
            val target = File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            ), fileName)
            scope.launch(Dispatchers.IO) {
                runCatching { target.parentFile?.mkdirs(); target.writeText(text) }
                    .onSuccess { dirty = false; lastSavedText = text; savedToast = true }
                    .onFailure { e -> saveErrorMsg = e.message }
            }
        } else {
            scope.launch(Dispatchers.IO) {
                runCatching { File(filePath!!).writeText(text) }
                    .onSuccess { dirty = false; lastSavedText = text; savedToast = true }
                    .onFailure { e -> saveErrorMsg = e.message }
            }
        }
    }

    BackHandler {
        if (dirty) pendingBack = true else onBack()
    }

    if (savedToast) {
        android.widget.Toast.makeText(context, "已保存 ✓", android.widget.Toast.LENGTH_SHORT).show()
        savedToast = false
    }
    if (loadError != null) {
        android.widget.Toast.makeText(context, "读取失败：$loadError", android.widget.Toast.LENGTH_LONG).show()
        loadError = null
    }
    if (saveErrorMsg != null) {
        android.widget.Toast.makeText(context, "保存失败：$saveErrorMsg", android.widget.Toast.LENGTH_LONG).show()
        saveErrorMsg = null
    }

    val lineCount = text.count { it == '\n' } + 1

    Column(
        Modifier.fillMaxSize().background(Color(scheme.background)),
    ) {
        // 顶栏
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { if (dirty) pendingBack = true else onBack() }) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                fileName + (if (dirty) " ●" else ""),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = {
                if (!readOnly && undoStack.isNotEmpty()) {
                    redoStack = redoStack.plus(text).let { ArrayDeque(it) }
                    text = undoStack.last()
                    undoStack = undoStack.dropLast(1).let { ArrayDeque(it) }
                    dirty = text != lastSavedText // 全撤回到与磁盘一致时复位（M7）
                }
            }, enabled = !readOnly && undoStack.isNotEmpty()) {
                Text("↶", style = MaterialTheme.typography.titleMedium) // 撤销
            }
            IconButton(onClick = {
                if (!readOnly && redoStack.isNotEmpty()) {
                    undoStack = undoStack.plus(text).let { ArrayDeque(it) }
                    text = redoStack.last()
                    redoStack = redoStack.dropLast(1).let { ArrayDeque(it) }
                    dirty = text != lastSavedText
                }
            }, enabled = !readOnly && redoStack.isNotEmpty()) {
                Text("↷", style = MaterialTheme.typography.titleMedium) // 重做
            }
            IconButton(onClick = { readOnly = !readOnly }) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = "只读",
                    tint = if (readOnly) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = { save() }) {
                Icon(Icons.Filled.Check, contentDescription = "保存")
            }
        }

        // 编辑区：行号列 + BasicTextField
        if (loaded) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                // 行号列
                Column(
                    Modifier
                        .width(44.dp)
                        .background(Color(scheme.background).copy(alpha = 0.6f))
                        .padding(top = 12.dp, bottom = 12.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    repeat((lineCount).coerceAtMost(5000)) { i ->
                        Text(
                            "${i + 1}",
                            fontSize = 12.sp,
                            color = Color(scheme.foreground).copy(alpha = 0.35f),
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                Box(Modifier.weight(1f)) {
                    BasicTextField(
                        value = text,
                        onValueChange = { if (!readOnly) markDirty(it) },
                        textStyle = TextStyle(
                            color = Color(scheme.foreground),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 16.sp,
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(scheme.cursor)),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                    )
                }
            }
        }

        // 状态栏：行 / 字符 / 只读
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Ln $lineCount · ${text.length} 字符",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (readOnly) {
                Text("只读", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            } else if (dirty) {
                Text("未保存", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            } else {
                Text("已保存", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    // 未保存拦截（FR-E3 三按钮）
    if (pendingBack) {
        AlertDialog(
            onDismissRequest = { pendingBack = false },
            title = { Text("有未保存的修改") },
            text = { Text("离开将丢失对 $fileName 的修改。") },
            confirmButton = {
                TextButton(onClick = {
                    save()
                    pendingBack = false
                    onBack()
                }) { Text("保存并离开") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { pendingBack = false; onBack() }) { Text("不保存") }
                    TextButton(onClick = { pendingBack = false }) { Text("取消") }
                }
            },
        )
    }
}
