package com.debdroid.app.ui.terminal

import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.KeyHandler
import com.termux.view.TerminalView

/**
 * 扩展键行的 CTRL/ALT 粘滞状态。被终端视图消费一次（下一字符到达时查询）。
 */
class ExtraKeysState {
    var ctrlActive by mutableStateOf(false)
        private set
    var altActive by mutableStateOf(false)
        private set

    fun consumeCtrl(): Boolean {
        val v = ctrlActive
        ctrlActive = false
        return v
    }

    fun consumeAlt(): Boolean {
        val v = altActive
        altActive = false
        return v
    }

    fun toggleCtrl() {
        ctrlActive = !ctrlActive
        if (ctrlActive) altActive = false
    }

    fun toggleAlt() {
        altActive = !altActive
        if (altActive) ctrlActive = false
    }

    fun clearToggles() {
        ctrlActive = false
        altActive = false
    }
}

/** 一个扩展键按钮定义。 */
sealed class ExtraKey(val label: String) {
    data object Esc : ExtraKey("ESC")
    data object Tab : ExtraKey("TAB")
    data object Ctrl : ExtraKey("CTRL")
    data object Alt : ExtraKey("ALT")
    data object Up : ExtraKey("↑")
    data object Down : ExtraKey("↓")
    data object Left : ExtraKey("←")
    data object Right : ExtraKey("→")
    data object Dash : ExtraKey("-")
    data object Slash : ExtraKey("/")
    data object Pipe : ExtraKey("|")
    data object Backslash : ExtraKey("\\")
    data object Tilde : ExtraKey("~")
    data object Dollar : ExtraKey("$")
    data object DQuote : ExtraKey("\"")
    data object SQuote : ExtraKey("'")
    data object Colon : ExtraKey(":")
    data object Semi : ExtraKey(";")
    data object Backtick : ExtraKey("`")
    data object Amp : ExtraKey("&")
    data object Gt : ExtraKey(">")
    data object Star : ExtraKey("*")
    data object Eq : ExtraKey("=")
    data object Enter : ExtraKey("ENTER")
    data object Home : ExtraKey("HOME")
    data object End : ExtraKey("END")
    data object PgUp : ExtraKey("PGUP")
    data object PgDn : ExtraKey("PGDN")
    data object Del : ExtraKey("DEL")
}

/**
 * 29 键三行布局（用户调校紧凑预设）：导航+编辑 / 修饰+箭头+标点 / shell 符号。
 */
val EXTRA_KEYS_ROWS: List<List<ExtraKey>> = listOf(
    listOf(
        ExtraKey.Esc, ExtraKey.Tab, ExtraKey.Home, ExtraKey.Up, ExtraKey.End,
        ExtraKey.PgUp, ExtraKey.PgDn, ExtraKey.Del, ExtraKey.Enter,
    ),
    listOf(
        ExtraKey.Ctrl, ExtraKey.Alt, ExtraKey.Left, ExtraKey.Down, ExtraKey.Right,
        ExtraKey.Colon, ExtraKey.Semi, ExtraKey.DQuote, ExtraKey.SQuote, ExtraKey.Backtick,
    ),
    listOf(
        ExtraKey.Dash, ExtraKey.Slash, ExtraKey.Pipe, ExtraKey.Backslash,
        ExtraKey.Tilde, ExtraKey.Dollar, ExtraKey.Amp, ExtraKey.Gt,
        ExtraKey.Star, ExtraKey.Eq,
    ),
)

/** 按住连发的键（导航/删除类，同 Termux）。 */
val REPEATABLE_KEYS: Set<ExtraKey> = setOf(
    ExtraKey.Up, ExtraKey.Down, ExtraKey.Left, ExtraKey.Right,
    ExtraKey.Del, ExtraKey.PgUp, ExtraKey.PgDn,
)

/** 把按键发送到终端视图。 */
fun performExtraKey(view: TerminalView?, key: ExtraKey, state: ExtraKeysState) {
    view ?: return
    when (key) {
        ExtraKey.Esc -> view.inputCodePoint(27, false, false)
        ExtraKey.Tab -> view.inputCodePoint(9, false, false)
        ExtraKey.Ctrl -> state.toggleCtrl()
        ExtraKey.Alt -> state.toggleAlt()
        ExtraKey.Up -> view.handleKeyCode(KeyEvent.KEYCODE_DPAD_UP, keyModifier(state))
        ExtraKey.Down -> view.handleKeyCode(KeyEvent.KEYCODE_DPAD_DOWN, keyModifier(state))
        ExtraKey.Left -> view.handleKeyCode(KeyEvent.KEYCODE_DPAD_LEFT, keyModifier(state))
        ExtraKey.Right -> view.handleKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT, keyModifier(state))
        ExtraKey.Dash -> view.inputCodePoint('-'.code, state.ctrlActive, state.altActive)
        ExtraKey.Slash -> view.inputCodePoint('/'.code, state.ctrlActive, state.altActive)
        ExtraKey.Pipe -> view.inputCodePoint('|'.code, state.ctrlActive, state.altActive)
        ExtraKey.Backslash -> view.inputCodePoint('\\'.code, state.ctrlActive, state.altActive)
        ExtraKey.Tilde -> view.inputCodePoint('~'.code, state.ctrlActive, state.altActive)
        ExtraKey.Dollar -> view.inputCodePoint('$'.code, state.ctrlActive, state.altActive)
        ExtraKey.DQuote -> view.inputCodePoint('"'.code, state.ctrlActive, state.altActive)
        ExtraKey.SQuote -> view.inputCodePoint('\''.code, state.ctrlActive, state.altActive)
        ExtraKey.Colon -> view.inputCodePoint(':'.code, state.ctrlActive, state.altActive)
        ExtraKey.Semi -> view.inputCodePoint(';'.code, state.ctrlActive, state.altActive)
        ExtraKey.Backtick -> view.inputCodePoint('`'.code, state.ctrlActive, state.altActive)
        ExtraKey.Amp -> view.inputCodePoint('&'.code, state.ctrlActive, state.altActive)
        ExtraKey.Gt -> view.inputCodePoint('>'.code, state.ctrlActive, state.altActive)
        ExtraKey.Star -> view.inputCodePoint('*'.code, state.ctrlActive, state.altActive)
        ExtraKey.Eq -> view.inputCodePoint('='.code, state.ctrlActive, state.altActive)
        ExtraKey.Enter -> view.inputCodePoint('\r'.code, false, false)
        ExtraKey.Home -> view.handleKeyCode(KeyEvent.KEYCODE_MOVE_HOME, keyModifier(state))
        ExtraKey.End -> view.handleKeyCode(KeyEvent.KEYCODE_MOVE_END, keyModifier(state))
        ExtraKey.PgUp -> view.handleKeyCode(KeyEvent.KEYCODE_PAGE_UP, keyModifier(state))
        ExtraKey.PgDn -> view.handleKeyCode(KeyEvent.KEYCODE_PAGE_DOWN, keyModifier(state))
        ExtraKey.Del -> view.handleKeyCode(KeyEvent.KEYCODE_FORWARD_DEL, keyModifier(state))
    }
    if (key !is ExtraKey.Ctrl && key !is ExtraKey.Alt) {
        state.clearToggles()
    }
}

private fun keyModifier(state: ExtraKeysState): Int {
    var mod = 0
    if (state.ctrlActive) mod = mod or KeyHandler.KEYMOD_CTRL
    if (state.altActive) mod = mod or KeyHandler.KEYMOD_ALT
    return mod
}
