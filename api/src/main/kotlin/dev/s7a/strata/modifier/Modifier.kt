package dev.s7a.strata.modifier

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Immutable ordered active modifier descriptions.
 *
 * The first description is the outermost node and the last description is nearest the component.
 * Composition is value based when its descriptions obey the immutable modifier contract.
 * The runtime reconciles modifier positions by referential [ModifierNodeType] token.
 *
 * The companion object is the empty chain and the starting receiver for modifier extensions.
 * Implementations must expose a stable read-only snapshot and compare by their ordered descriptions.
 */
@OptIn(InternalStrataRuntimeApi::class)
public interface Modifier {
    /**
     * Appends one description inside the existing chain.
     *
     * @param element the immutable description to append.
     * @return a new value whose existing descriptions remain outermost.
     */
    public fun then(element: ModifierElement): Modifier = ModifierChain(elements() + element)

    /**
     * Appends another chain inside the existing chain.
     *
     * @param modifier the immutable chain to append.
     * @return a new value with this chain outermost.
     */
    public fun then(modifier: Modifier): Modifier {
        val inner = modifier.elements()
        val outer = elements()
        return when {
            inner.isEmpty() -> this
            outer.isEmpty() -> modifier
            else -> ModifierChain(outer + inner)
        }
    }

    /**
     * Returns the read-only description snapshot to the retained runtime.
     *
     * @return the stable ordered snapshot owned by this value.
     */
    @InternalStrataRuntimeApi
    public fun elements(): List<ModifierElement>

    /**
     * An empty chain that leaves a component description unchanged and starts modifier extensions.
     */
    public companion object : Modifier {
        /**
         * Compatibility alias for [Modifier], deprecated in 0.3.0 and scheduled for removal in 1.0.0.
         */
        @Deprecated(
            message = "Use Modifier instead. Deprecated in 0.3.0 and scheduled for removal in 1.0.0.",
            replaceWith = ReplaceWith("Modifier"),
            level = DeprecationLevel.WARNING,
        )
        public val Empty: Modifier get() = this

        @InternalStrataRuntimeApi
        override fun elements(): List<ModifierElement> = emptyList()

        override fun equals(other: Any?): Boolean = other is Modifier && other.elements().isEmpty()

        override fun hashCode(): Int = emptyList<ModifierElement>().hashCode()

        override fun toString(): String = "Modifier([])"
    }
}
