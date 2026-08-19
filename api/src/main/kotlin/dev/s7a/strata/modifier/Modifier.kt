package dev.s7a.strata.modifier

import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.util.Collections

/**
 * Immutable ordered active modifier descriptions.
 *
 * The first description is the outermost node and the last description is nearest the component.
 * Composition is value based when its descriptions obey the immutable modifier contract.
 * The runtime reconciles modifier positions by referential [ModifierNodeType] token.
 *
 * @param elements the immutable ordered descriptions owned by this value.
 */
public class Modifier private constructor(
    elements: List<ModifierElement>,
) {
    private val descriptions: List<ModifierElement> = Collections.unmodifiableList(elements.toList())

    /**
     * Appends one description inside the existing chain.
     *
     * @param element the immutable description to append.
     * @return a new value whose existing descriptions remain outermost.
     */
    public fun then(element: ModifierElement): Modifier =
        if (descriptions.isEmpty()) {
            Modifier(listOf(element))
        } else {
            Modifier(descriptions + element)
        }

    /**
     * Appends another chain inside the existing chain.
     *
     * @param modifier the immutable chain to append.
     * @return a new value with this chain outermost.
     */
    public fun then(modifier: Modifier): Modifier =
        when {
            modifier.descriptions.isEmpty() -> this
            descriptions.isEmpty() -> modifier
            else -> Modifier(descriptions + modifier.descriptions)
        }

    /**
     * Returns the immutable description snapshot to the retained runtime.
     *
     * @return the stable ordered snapshot owned by this value.
     */
    @InternalStrataRuntimeApi
    public fun elements(): List<ModifierElement> = descriptions

    override fun equals(other: Any?): Boolean = other is Modifier && descriptions == other.descriptions

    override fun hashCode(): Int = descriptions.hashCode()

    override fun toString(): String = "Modifier($descriptions)"

    /**
     * Common modifier values.
     */
    public companion object {
        /**
         * An empty chain that leaves a component description unchanged.
         */
        public val Empty: Modifier = Modifier(emptyList())
    }
}
