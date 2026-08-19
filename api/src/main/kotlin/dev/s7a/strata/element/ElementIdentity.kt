package dev.s7a.strata.element

/**
 * Identity used when reconciling an element with a retained node.
 *
 * Positional identity is tied to an absolute sibling index.
 * Keyed identity is tied to one non-null value under one parent.
 */
public sealed interface ElementIdentity {
    /**
     * Positional identity.
     */
    public data object Positional : ElementIdentity

    /**
     * A non-null application key scoped to one direct-sibling list.
     */
    public data class Keyed(
        public val key: ElementKey<*>,
    ) : ElementIdentity
}
