package dev.s7a.strata.runtime.diagnostics

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Retained identity category; modifiers are counted separately from their logical component.
 */
@InternalStrataRuntimeApi
public enum class UiRenderNodeKind {
    /**
     * Logical component.
     */
    Component,

    /**
     * Active modifier.
     */
    Modifier,
}
