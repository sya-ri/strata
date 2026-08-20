package dev.s7a.strata.node

import dev.s7a.strata.layout.ParentDataKey

/**
 * Active modifier behavior that supplies typed data to its logical parent layout.
 *
 * An implementation must be attached to an active [ModifierNode] and expose one stable, referential [parentDataKey] for the retained modifier description.
 * [parentData] returns an immutable, type-correct snapshot for that key.
 * The runtime invokes it on the owner thread during measure or layout and propagates its exact failure instance.
 * A provider update that changes its key or value must invalidate measurement; an equal update may leave measurement clean.
 *
 * @param D the parent-data value type supplied by this modifier.
 */
public interface ParentDataModifierNode<D : Any> {
    /**
     * Stable referential token owned by the retained parent-data description.
     */
    public val parentDataKey: ParentDataKey<D>

    /**
     * Returns the current immutable, type-correct parent-data snapshot.
     *
     * The runtime calls this on the owning tree thread only after selecting the innermost matching provider.
     * Any failure escapes unchanged and follows the surrounding measure or layout operation's failure rules.
     */
    public fun parentData(): D
}
