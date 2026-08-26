package com.debdroid.app.ui.theme

import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSession

/** 终端配色方案（FR-T4）：16 ANSI 色 + 前景/背景/光标。 */
data class TerminalScheme(
    val id: String,
    val name: String,
    val colors16: IntArray,
    val foreground: Int,
    val background: Int,
    val cursor: Int,
)

/**
 * 8 套内置配色（Dracula/Nord/Gruvbox/Solarized/Catppuccin/Tokyo Night/One Dark/Termux）。
 * 应用方式：写入终端内核库的静态默认调色板，新会话自动使用；切换后对活会话 reset。
 */
object TerminalColors {

    private fun scheme(
        id: String, name: String,
        bg: Int, fg: Int, cursor: Int,
        c0: Int, c1: Int, c2: Int, c3: Int, c4: Int, c5: Int, c6: Int, c7: Int,
        c8: Int, c9: Int, c10: Int, c11: Int, c12: Int, c13: Int, c14: Int, c15: Int,
    ) = TerminalScheme(id, name, intArrayOf(c0, c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12, c13, c14, c15), fg, bg, cursor)

    val ALL: List<TerminalScheme> = listOf(
        scheme("dracula", "Dracula", 0xFF282A36.toInt(), 0xFFF8F8F2.toInt(), 0xFFF8F8F2.toInt(),
            0xFF000000.toInt(), 0xFFFF5555.toInt(), 0xFF50FA7B.toInt(), 0xFFF1FA8C.toInt(),
            0xFFBD93F9.toInt(), 0xFFFF79C6.toInt(), 0xFF8BE9FD.toInt(), 0xFFBFBFBF.toInt(),
            0xFF4D4D4D.toInt(), 0xFFFF6E67.toInt(), 0xFF5AF78E.toInt(), 0xFFF4F99D.toInt(),
            0xFFCAA9FA.toInt(), 0xFFFF92D0.toInt(), 0xFF9AEDFE.toInt(), 0xFFE6E6E6.toInt()),
        scheme("nord", "Nord", 0xFF2E3440.toInt(), 0xFFD8DEE9.toInt(), 0xFFD8DEE9.toInt(),
            0xFF3B4252.toInt(), 0xFFBF616A.toInt(), 0xFFA3BE8C.toInt(), 0xFFEBCB8B.toInt(),
            0xFF81A1C1.toInt(), 0xFFB48EAD.toInt(), 0xFF88C0D0.toInt(), 0xFFE5E9F0.toInt(),
            0xFF4C566A.toInt(), 0xFFBF616A.toInt(), 0xFFA3BE8C.toInt(), 0xFFEBCB8B.toInt(),
            0xFF81A1C1.toInt(), 0xFFB48EAD.toInt(), 0xFF8FBCBB.toInt(), 0xFFECEFF4.toInt()),
        scheme("gruvbox", "Gruvbox Dark", 0xFF282828.toInt(), 0xFFEBDBB2.toInt(), 0xFFEBDBB2.toInt(),
            0xFF282828.toInt(), 0xFFCC241D.toInt(), 0xFF98971A.toInt(), 0xFFD79921.toInt(),
            0xFF458588.toInt(), 0xFFB16286.toInt(), 0xFF689D6A.toInt(), 0xFFA89984.toInt(),
            0xFF928374.toInt(), 0xFFFB4934.toInt(), 0xFFB8BB26.toInt(), 0xFFFABD2F.toInt(),
            0xFF83A598.toInt(), 0xFFD3869B.toInt(), 0xFF8EC07C.toInt(), 0xFFEBDBB2.toInt()),
        scheme("solarized-dark", "Solarized Dark", 0xFF002B36.toInt(), 0xFF839496.toInt(), 0xFF93A1A1.toInt(),
            0xFF073642.toInt(), 0xFFDC322F.toInt(), 0xFF859900.toInt(), 0xFFB58900.toInt(),
            0xFF268BD2.toInt(), 0xFFD33682.toInt(), 0xFF2AA198.toInt(), 0xFFEEE8D5.toInt(),
            0xFF002B36.toInt(), 0xFFCB4B16.toInt(), 0xFF586E75.toInt(), 0xFF657B83.toInt(),
            0xFF839496.toInt(), 0xFF6C71C4.toInt(), 0xFF93A1A1.toInt(), 0xFFFDF6E3.toInt()),
        scheme("catppuccin", "Catppuccin Mocha", 0xFF1E1E2E.toInt(), 0xFFCDD6F4.toInt(), 0xFFF5E0DC.toInt(),
            0xFF45475A.toInt(), 0xFFF38BA8.toInt(), 0xFFA6E3A1.toInt(), 0xFFF9E2AF.toInt(),
            0xFF89B4FA.toInt(), 0xFFF5C2E7.toInt(), 0xFF94E2D5.toInt(), 0xFFBAC2DE.toInt(),
            0xFF585B70.toInt(), 0xFFF38BA8.toInt(), 0xFFA6E3A1.toInt(), 0xFFF9E2AF.toInt(),
            0xFF89B4FA.toInt(), 0xFFF5C2E7.toInt(), 0xFF94E2D5.toInt(), 0xFFA6ADC8.toInt()),
        scheme("tokyo-night", "Tokyo Night", 0xFF1A1B26.toInt(), 0xFFA9B1D6.toInt(), 0xFFC0CAF5.toInt(),
            0xFF15161E.toInt(), 0xFFF7768E.toInt(), 0xFF9ECE6A.toInt(), 0xFFE0AF68.toInt(),
            0xFF7AA2F7.toInt(), 0xFFBB9AF7.toInt(), 0xFF7DCFFF.toInt(), 0xFFA9B1D6.toInt(),
            0xFF414868.toInt(), 0xFFFF7A93.toInt(), 0xFFB9F27C.toInt(), 0xFFFF9E64.toInt(),
            0xFF7DA6FF.toInt(), 0xFFBB9AF7.toInt(), 0xFF0DB9D7.toInt(), 0xFFC0CAF5.toInt()),
        scheme("one-dark", "One Dark", 0xFF282C34.toInt(), 0xFFABB2BF.toInt(), 0xFF528BFF.toInt(),
            0xFF282C34.toInt(), 0xFFE06C75.toInt(), 0xFF98C379.toInt(), 0xFFE5C07B.toInt(),
            0xFF61AFEF.toInt(), 0xFFC678DD.toInt(), 0xFF56B6C2.toInt(), 0xFFABB2BF.toInt(),
            0xFF5C6370.toInt(), 0xFFE06C75.toInt(), 0xFF98C379.toInt(), 0xFFE5C07B.toInt(),
            0xFF61AFEF.toInt(), 0xFFC678DD.toInt(), 0xFF56B6C2.toInt(), 0xFFFFFFFF.toInt()),
        scheme("termux", "Termux Classic", 0xFF000000.toInt(), 0xFFEEEEEE.toInt(), 0xFFFFFFFF.toInt(),
            0xFF000000.toInt(), 0xFFCD0000.toInt(), 0xFF00CD00.toInt(), 0xFFCDCD00.toInt(),
            0xFF6495ED.toInt(), 0xFFCD00CD.toInt(), 0xFF00CDCD.toInt(), 0xFFE5E5E5.toInt(),
            0xFF7F7F7F.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFFFFFF00.toInt(),
            0xFF5C5CFF.toInt(), 0xFFFF00FF.toInt(), 0xFF00FFFF.toInt(), 0xFFFFFFFF.toInt()),
    )

    fun byId(id: String): TerminalScheme = ALL.firstOrNull { it.id == id } ?: ALL.first()

    /** 写入库级默认调色板；新会话自动使用。 */
    fun applyScheme(id: String) {
        val scheme = byId(id)
        val defaults = TerminalColors.COLOR_SCHEME.mDefaultColors
        scheme.colors16.forEachIndexed { i, c -> defaults[i] = c }
        defaults[IDX_FOREGROUND] = scheme.foreground
        defaults[IDX_BACKGROUND] = scheme.background
        defaults[IDX_CURSOR] = scheme.cursor
    }

    /** 切换配色后对活会话生效（reset 触发重绘）。 */
    fun reapplyTo(session: TerminalSession?) {
        session?.emulator?.mColors?.reset()
    }

    private const val IDX_FOREGROUND = 256
    private const val IDX_BACKGROUND = 257
    private const val IDX_CURSOR = 258
}
