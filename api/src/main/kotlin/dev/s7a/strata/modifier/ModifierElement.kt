package dev.s7a.strata.modifier

import dev.s7a.strata.projection.DeclarationProjection

/**
 * Immutable value description for one active modifier node.
 *
 * Implementations preserve every property used by the runtime for the lifetime of the containing element description.
 * The stable [type] token selects typed validation, creation, and update behavior without a registry.
 * Description construction must not acquire resources.
 * The runtime invokes token hooks on the owning tree thread and propagates their failures unchanged.
 */
public interface ModifierElement {
    /**
     * Optional typed export; the receiving runtime reconstructs this modifier's active behavior.
     */
    public val projection: DeclarationProjection<*>? get() = null

    /**
     * The stable referential token that owns this description and its retained node type.
     */
    public val type: ModifierNodeType<*, *>
}
