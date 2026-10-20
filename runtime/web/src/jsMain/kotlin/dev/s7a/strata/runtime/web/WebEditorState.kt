@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.web

import dev.s7a.strata.component.ScrollState
import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Adapts caller-owned editing state without moving its ownership into the DOM.
 */
internal sealed class WebEditorState {
    /**
     * Current canonical text, updated only by input transactions.
     */
    abstract var value: String

    /**
     * UTF-16 limit, also applied to paste and composition commits.
     */
    abstract val maxLength: Int

    /**
     * Optional caller-owned vertical position.
     */
    open val scroll: ScrollState? = null

    /**
     * Acquires the state's exclusive retained observer.
     */
    abstract fun observe(changed: () -> Unit): AutoCloseable

    /**
     * Single-line state adapter.
     */
    class Field(
        val state: TextFieldState,
    ) : WebEditorState() {
        override var value: String
            get() = state.value
            set(value) {
                state.value = value
            }
        override val maxLength: Int get() = state.maxLength

        override fun observe(changed: () -> Unit): AutoCloseable = state.observe { changed() }
    }

    /**
     * Multiline state adapter with a stable scroll owner.
     */
    class Area(
        val state: TextAreaState,
    ) : WebEditorState() {
        override var value: String
            get() = state.value
            set(value) {
                state.value = value
            }
        override val maxLength: Int get() = state.maxLength
        override val scroll: ScrollState get() = state.scrollState

        override fun observe(changed: () -> Unit): AutoCloseable = state.observe { changed() }
    }

    /**
     * Filters unsupported native input before writing the public state contract.
     */
    fun commit(nativeValue: String) {
        val normalized = if (this is Area) nativeValue.replace("\r\n", "\n").replace(Regex("[\r\u000B\u000C\u0085\u2028\u2029]"), "\n") else nativeValue
        val accepted = StringBuilder()
        var index = 0
        while (index < normalized.length) {
            val first = normalized[index]
            val count = if (first.isHighSurrogate() && index + 1 < normalized.length && normalized[index + 1].isLowSurrogate()) 2 else 1
            val valid = accepted(first, count)
            if (valid && accepted.length + count <= maxLength) accepted.append(normalized, index, index + count)
            index += count
        }
        value = accepted.toString()
    }

    private fun accepted(
        first: Char,
        count: Int,
    ): Boolean =
        when {
            count == 2 -> true
            first.isSurrogate() -> false
            first == '\n' && this is Area -> true
            first.code < 32 || first.code in listOf(127, 133, 167, 8232, 8233) -> false
            else -> true
        }
}
