package dev.s7a.strata.runtime.semantics

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.semantics.Semantics

/**
 * An immutable flattened semantics entry with retained unresolved text.
 *
 * Entries are returned in parent-before-child and local emission order.
 * Their bounds combine the cached local payload with the current accumulated tree coordinates.
 * The [semantics] payload remains unresolved for the platform adapter.
 *
 * @property bounds the node's accumulated bounds in tree coordinates.
 * @property semantics the unresolved semantics payload.
 */
public data class SemanticsEntry(
    public val bounds: IntRect,
    public val semantics: Semantics,
)
