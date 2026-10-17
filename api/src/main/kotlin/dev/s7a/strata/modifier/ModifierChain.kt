package dev.s7a.strata.modifier

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Owns internally created read-only membership without retaining a mutable alias.
 * Callers transfer a fresh list so composition does not copy its new membership twice.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class ModifierChain(
    private val descriptions: List<ModifierElement>,
) : Modifier {
    @InternalStrataRuntimeApi
    override fun elements(): List<ModifierElement> = descriptions

    override fun equals(other: Any?): Boolean = other is Modifier && descriptions == other.elements()

    override fun hashCode(): Int = descriptions.hashCode()

    override fun toString(): String = "Modifier($descriptions)"
}
