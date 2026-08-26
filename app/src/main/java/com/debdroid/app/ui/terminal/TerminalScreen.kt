package com.debdroid.app.ui.terminal

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import com.debdroid.app.R
import com.debdroid.app.prefs.AppSettings
import com.debdroid.app.ui.theme.TerminalColors
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val REPEAT_INITIAL_DELAY_MS = 350L
private const val REPEAT_INTERVAL_MS = 50L

/**
 * 终端主屏（FR-T1~T5 / FR-S1~S5）：
 * 会话抽屉 + 顶栏菜单 + TerminalView（AndroidView）+ 29 键扩展键。
 * 配色按当前设置动态套用（scheme 切换即重绘）。
 */
@Composable
fun TerminalScreen(
    settings: AppSettings,
    sessions: List<TerminalSession>,
    activeIndex: Int,
    onSelectSession: (Int) -> Unit,
    onNewSession: () -> Unit,
    onCloseSession: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenEditor: (String?) -> Unit,
    onFontSizeDelta: (Float) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activeSession = sessions.getOrNull(activeIndex)
    val extraKeysState = remember { ExtraKeysState() }
    var menuOpen by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val scheme = remember(settings.colorSchemeId) { TerminalColors.byId(settings.colorSchemeId) }

    val typeface = remember(settings.useNerdFont) {
        if (settings.useNerdFont) {
            ResourcesCompat.getFont(context, com.debdroid.app.R.font.jetbrainsmono_regular)
                ?: Typeface.MONOSPACE
        } else {
            Typeface.MONOSPACE
        }
    }

    BackHandler {
        if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else {
            (context as? Activity)?.moveTaskToBack(true)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = false, // 防捏合缩放误触发抽屉；入口仅顶栏 ☰
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surface) {
                Text(
                    stringResource(R.string.sessions_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
                Divider()
                sessions.forEachIndexed { i, session ->
                    NavigationDrawerItem(
                        label = {
                            Text(
                                session.mSessionName ?: stringResource(R.string.session_default_name, i + 1),
                                maxLines = 1,
                            )
                        },
                        selected = i == activeIndex,
                        onClick = {
                            onSelectSession(i)
                            scope.launch { drawerState.close() }
                        },
                        badge = {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clickable { onCloseSession(i) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.session_close),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        },
                    )
                }
                if (sessions.isNotEmpty()) Divider()
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.session_new)) },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    selected = false,
                    onClick = {
                        onNewSession()
                        scope.launch { drawerState.close() }
                    },
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.toggle_keyboard)) },
                    icon = { Text("⌨", fontSize = 18.sp) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        toggleKeyboard(lastTerminalView)
                    },
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.settings_title)) },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenSettings()
                    },
                )
            }
        },
    ) {
        Column(modifier = Modifier.fillMaxSize().background(Color(scheme.background))) {
            // 顶栏：抽屉 / 会话名 / 设置 / 更多
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                    Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.sessions_title))
                }
                Text(
                    text = activeSession?.mSessionName ?: stringResource(R.string.no_sessions),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.terminal_menu))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.nav_files)) },
                            onClick = { menuOpen = false; onOpenFiles() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.terminal_new)) },
                            onClick = { menuOpen = false; onNewSession() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.terminal_copy_all)) },
                            onClick = { menuOpen = false; copyTranscript(context, activeSession) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.terminal_paste)) },
                            onClick = { menuOpen = false; pasteFromClipboard(context, activeSession) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.terminal_share)) },
                            onClick = { menuOpen = false; shareTranscript(context, activeSession) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.terminal_reset)) },
                            onClick = { menuOpen = false; activeSession?.emulator?.reset() },
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(scheme.background)),
            ) {
                if (sessions.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            stringResource(R.string.no_sessions),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                        )
                        Button(onClick = onNewSession, modifier = Modifier.padding(top = 16.dp)) {
                            Text(stringResource(R.string.start_session))
                        }
                    }
                } else {
                    val held = remember { TerminalHeldState() }
                    AndroidView(
                        factory = { ctx ->
                            TerminalView(ctx, null).apply {
                                lastTerminalView = this
                                setTerminalViewClient(
                                    DroidTerminalViewClient(this, extraKeysState, onFontSizeDelta)
                                )
                                isFocusable = true
                                isFocusableInTouchMode = true
                                layoutParams = android.view.ViewGroup.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                            }
                        },
                        update = { view ->
                            // 只在实际变化时触碰昂贵属性，重组不得拖慢按键回显
                            if (held.fontSize != settings.fontSize) {
                                held.fontSize = settings.fontSize
                                view.setTextSize(settings.fontSize)
                            }
                            if (held.typeface !== typeface) {
                                held.typeface = typeface
                                view.setTypeface(typeface)
                            }
                            if (activeSession != null && view.mTermSession !== activeSession) {
                                view.attachSession(activeSession)
                                held.scheme = null
                                view.requestFocus()
                            }
                            if (activeSession != null && held.scheme != settings.colorSchemeId) {
                                held.scheme = settings.colorSchemeId
                                TerminalColors.reapplyTo(activeSession)
                                view.onScreenUpdated()
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            if (sessions.isNotEmpty()) {
                ExtraKeysRows(
                    state = extraKeysState,
                    backgroundColor = Color(scheme.background).copy(alpha = 0.40f),
                    foregroundColor = Color(scheme.foreground),
                    onView = { lastTerminalView },
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { lastTerminalView = null }
    }
}

// 终端视图实例（AndroidView 内创建），供扩展键分发
private var lastTerminalView: TerminalView? = null

/** 记录上次应用到 TerminalView 的属性，跳过冗余更新。 */
private class TerminalHeldState {
    var fontSize: Int = -1
    var typeface: Typeface? = null
    var scheme: String? = null
}

private fun toggleKeyboard(view: View?) {
    view ?: return
    val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    if (imm.isActive(view)) {
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    } else {
        view.requestFocus()
        imm.showSoftInput(view, 0)
    }
}

/**
 * Termux 式两行扩展键。可重复键按下即发并按住连发；每次按下带触感反馈。
 */
@Composable
private fun ExtraKeysRows(
    state: ExtraKeysState,
    backgroundColor: Color,
    foregroundColor: Color,
    onView: () -> TerminalView?,
) {
    val haptic = LocalHapticFeedback.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(horizontal = 2.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        EXTRA_KEYS_ROWS.forEach { rowKeys ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                rowKeys.forEach { key ->
                    ExtraKeyButton(
                        key = key,
                        state = state,
                        backgroundColor = backgroundColor,
                        foregroundColor = foregroundColor,
                        hapticEnabled = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        },
                        onPress = { performExtraKey(onView(), key, state) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ExtraKeyButton(
    key: ExtraKey,
    state: ExtraKeysState,
    backgroundColor: Color,
    foregroundColor: Color,
    hapticEnabled: () -> Unit,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val currentOnPress by rememberUpdatedState(onPress)

    LaunchedEffect(interaction) {
        interaction.interactions.collectLatest { event ->
            if (event is PressInteraction.Press) {
                hapticEnabled()
                currentOnPress()
                if (key in REPEATABLE_KEYS) {
                    delay(REPEAT_INITIAL_DELAY_MS)
                    while (true) {
                        currentOnPress()
                        delay(REPEAT_INTERVAL_MS)
                    }
                }
            }
            // Release/Cancel 到达即取消连发循环
        }
    }

    val active = (key is ExtraKey.Ctrl && state.ctrlActive) ||
        (key is ExtraKey.Alt && state.altActive)
    val shape = RectangleShape
    val accent = Color(0xFFBD93F9)
    Box(
        modifier = modifier
            .heightIn(min = 26.dp)
            .clip(shape)
            .background(backgroundColor)
            .border(
                0.5.dp,
                if (active) accent else foregroundColor.copy(alpha = 0.30f),
                shape,
            )
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
            ) { /* 由 interactions 处理 */ }
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            key.label,
            fontSize = 9.sp,
            textAlign = TextAlign.Center,
            color = if (active) accent else foregroundColor,
            maxLines = 1,
            fontWeight = if (key is ExtraKey.Enter) FontWeight.Bold else null,
        )
    }
}

private fun copyTranscript(context: Context, session: TerminalSession?) {
    val text = session?.emulator?.mScreen?.getTranscriptTextWithFullLinesJoined() ?: return
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("DebDroid", text))
}

private fun pasteFromClipboard(context: Context, session: TerminalSession?) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
    val bytes = text.toByteArray()
    session?.write(bytes, 0, bytes.size)
}

private fun shareTranscript(context: Context, session: TerminalSession?) {
    val text = session?.emulator?.mScreen?.getTranscriptTextWithFullLinesJoined() ?: return
    val intent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(intent, null))
}

/** 最小 TerminalViewClient：扩展键 + 捏合缩放 + 软键盘唤起。 */
private class DroidTerminalViewClient(
    private val view: TerminalView,
    private val extraKeys: ExtraKeysState,
    private val onFontSizeDelta: (Float) -> Unit,
) : TerminalViewClient {

    override fun onScale(scale: Float): Float {
        onFontSizeDelta(scale)
        return scale.coerceIn(0.5f, 3f)
    }

    override fun onSingleTapUp(e: MotionEvent?) {
        view.requestFocus()
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, 0)
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = true
    override fun isTerminalViewSelected(): Boolean = view.isFocused
    override fun copyModeChanged(newCopyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
    override fun onLongPress(event: MotionEvent?): Boolean = false
    override fun readControlKey(): Boolean = extraKeys.consumeCtrl()
    override fun readAltKey(): Boolean = extraKeys.consumeAlt()
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false

    override fun onEmulatorSet() {
        lastTerminalView = view
    }

    override fun logError(tag: String, message: String) { Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception?) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception?) { Log.e(tag, "", e) }
}
