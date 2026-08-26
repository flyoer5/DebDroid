package com.debdroid.app.ui.terminal

import com.termux.view.TerminalView

/**
 * 全局终端视图引用桥。
 * SessionManager.onTextChanged（任意线程）→ [notifyTerminalViewScreenUpdated] → 当前 TerminalView。
 * 没有它，终端只能靠光标闪烁 ~500ms 重绘，输入回显明显卡顿（v1.0.8 修复，必须保留）。
 */
@Volatile
var activeTerminalView: TerminalView? = null

fun notifyTerminalViewScreenUpdated() {
    activeTerminalView?.onScreenUpdated()
}
