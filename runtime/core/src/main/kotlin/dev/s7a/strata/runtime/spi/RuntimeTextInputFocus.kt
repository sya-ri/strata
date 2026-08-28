package dev.s7a.strata.runtime.spi

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Opaque identity of one committed editable-text focus interval.
 *
 * The owning runtime retains only its current interval and replaces it when the logical owner or its accepting text-input targets change.
 * Detachment and focus loss end the interval; later acquisition creates a distinct identity even for the same retained node.
 * Tokens contain no node, session, callback, or native references and may be compared by identity on any thread.
 * Runtime adapters read the current token only outside owner-thread frame, input, and lifecycle operations.
 */
@InternalStrataRuntimeApi
public class RuntimeTextInputFocus private constructor() {
    /**
     * Creates identities only for the retained runtime's current focus state.
     */
    internal companion object {
        /**
         * Creates a detached identity for a newly committed editable focus interval.
         *
         * @return a fresh identity with no retained application references.
         */
        @JvmSynthetic
        internal fun create(): RuntimeTextInputFocus = RuntimeTextInputFocus()
    }
}
