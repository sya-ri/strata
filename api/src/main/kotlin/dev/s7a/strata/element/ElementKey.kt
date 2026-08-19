package dev.s7a.strata.element

/**
 * Immutable typed identity for a keyed element.
 *
 * The wrapped value must have stable equality and hash-code behavior for the entire time it is used as a sibling key.
 * Keys are compared only among direct children of one parent.
 *
 * @param T the application-owned key value type.
 * @property value the non-null key value.
 */
public data class ElementKey<T : Any>(
    public val value: T,
)
