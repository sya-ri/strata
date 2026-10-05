package dev.s7a.strata.modifier

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Owns a shallow read-only snapshot of ordered modifier descriptions.
 * Construction detaches collection membership while preserving each immutable element reference.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class ModifierChain(
    elements: List<ModifierElement>,
) : Modifier {
    private val descriptions: List<ModifierElement> = elements.toList()

    @InternalStrataRuntimeApi
    override fun elements(): List<ModifierElement> = descriptions

    override fun equals(other: Any?): Boolean = other is Modifier && descriptions == other.elements()

    override fun hashCode(): Int = descriptions.hashCode()

    override fun toString(): String = "Modifier($descriptions)"
}
