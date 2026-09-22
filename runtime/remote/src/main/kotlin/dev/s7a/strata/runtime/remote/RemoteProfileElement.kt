package dev.s7a.strata.runtime.remote

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.Node
import dev.s7a.strata.projection.DeclarationProjection
import dev.s7a.strata.projection.ProjectionScope
import dev.s7a.strata.projection.ProjectionValue

/**
 * Server-only profile primitive retaining typed projection logic and ordinary declarative children.
 * The shared core owns reconciliation and lifecycle; this node never resolves fonts, measures, or draws.
 */
internal class RemoteProfileElement(
    kind: RemoteProfileComponent,
    modifier: Modifier,
    key: ElementKey<*>?,
    children: List<Element> = emptyList(),
    enabled: Boolean = true,
    properties: (ProjectionScope) -> ProjectionValue,
) : Element(key?.let(ElementIdentity::Keyed) ?: ElementIdentity.Positional, TYPES.getValue(kind), children, modifier) {
    override val projection = DeclarationProjection(kind.type, properties, enabled) { encode, scope -> encode(scope) }

    private class ServerNode : Node()

    private companion object {
        val TYPES =
            RemoteProfileComponent.entries.associateWith {
                ElementType(RemoteProfileElement::class, ServerNode::class, { _ -> }, { _ -> ServerNode() }, { _, _, _ -> DirtyMask.None })
            }
    }
}
