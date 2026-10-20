package dev.s7a.strata.runtime.semantics

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.semantics.Semantics

/**
 * An immutable flattened semantics entry with retained unresolved text.
 *
 * Entries are returned in parent-before-child and local emission order.
 * Their bounds combine the cached local payload with the current accumulated tree coordinates.
 * The [SemanticsEntry.semantics] payload remains unresolved for the platform adapter.
 *
 * @property bounds the node's accumulated bounds in tree coordinates.
 * @property semantics the unresolved semantics payload.
 */
public data class SemanticsEntry(
    public val bounds: IntRect,
    public val semantics: Semantics,
) {
    private var ownership: SemanticsOwner? = null

    /**
     * Detached native metadata supplied by session frames; direct tree semantics omit it.
     * Logical equality, destructuring and copy retain their bounds/payload contract across backends.
     * A logical copy omits ownership because changed payload or geometry cannot reuse native placement.
     */
    public val owner: SemanticsOwner? get() = ownership

    /**
     * Constructs one detached native snapshot, initializing metadata only during construction.
     */
    public constructor(bounds: IntRect, semantics: Semantics, owner: SemanticsOwner) : this(bounds, semantics) {
        ownership = owner
    }
}
