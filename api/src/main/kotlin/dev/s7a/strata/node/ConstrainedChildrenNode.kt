package dev.s7a.strata.node

import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Materializes direct children after modifiers and the parent resolve this node's measurement constraints.
 *
 * Local measurement uses the constrained callback instead of the ordinary pre-measure callback.
 * Declaration-only hosts still use [DynamicChildrenNode.dynamicChildren]; implementations must reject unsupported geometry there.
 * Children follow the ownership, stable-key and side-effect contracts of [DynamicChildrenNode].
 */
@InternalStrataRuntimeApi
public interface ConstrainedChildrenNode : DynamicChildrenNode {
    /**
     * Returns the children needed under the incoming constraints before their measurement scopes are created.
     *
     * @param constraints resolved constraints supplied to this node, including active modifiers.
     * @return immutable direct-child descriptions in logical sibling order.
     */
    public fun dynamicChildren(constraints: Constraints): List<Element>
}
