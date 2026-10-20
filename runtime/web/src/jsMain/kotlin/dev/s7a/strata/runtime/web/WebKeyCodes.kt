package dev.s7a.strata.runtime.web

import dev.s7a.strata.input.KeyCode

/**
 * Maps the browser's physical code namespace to common retained keyboard identities.
 */
internal object WebKeyCodes {
    private val codes =
        mapOf(
            "Space" to KeyCode.Space,
            "Escape" to KeyCode.Escape,
            "Enter" to KeyCode.Enter,
            "NumpadEnter" to KeyCode.Enter,
            "Tab" to KeyCode.Tab,
            "Backspace" to KeyCode.Backspace,
            "Delete" to KeyCode.Delete,
            "Insert" to KeyCode.Insert,
            "ArrowLeft" to KeyCode.Left,
            "ArrowRight" to KeyCode.Right,
            "ArrowUp" to KeyCode.Up,
            "ArrowDown" to KeyCode.Down,
            "Home" to KeyCode.Home,
            "End" to KeyCode.End,
            "PageUp" to KeyCode.PageUp,
            "PageDown" to KeyCode.PageDown,
        )

    /**
     * Preserves letter identities independently of the active native keyboard layout.
     */
    fun decode(code: String): KeyCode = codes[code] ?: if (code.startsWith("Key") && code.length == 4) KeyCode(code.last().code) else KeyCode.Unknown
}
