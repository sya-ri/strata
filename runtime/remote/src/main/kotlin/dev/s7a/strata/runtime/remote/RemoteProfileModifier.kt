package dev.s7a.strata.runtime.remote

import dev.s7a.strata.modifier.ModifierElement
import dev.s7a.strata.modifier.ModifierNodeType
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.ModifierNode
import dev.s7a.strata.projection.DeclarationProjection
import dev.s7a.strata.projection.ProjectionValue

/**
 * Server-side active description for a profile-backed client modifier.
 * It preserves modifier order without loading any client-owned profile assets on the server.
 */
internal class RemoteProfileModifier(
    kind: RemoteProfileComponent,
    properties: ProjectionValue,
) : ModifierElement {
    override val type: ModifierNodeType<*, *> = TYPES.getValue(kind)
    override val projection = DeclarationProjection(kind.type, properties) { value, _ -> value }

    private class ServerNode : ModifierNode()

    private companion object {
        val TYPES =
            RemoteProfileComponent.entries.associateWith {
                ModifierNodeType(RemoteProfileModifier::class, ServerNode::class, { _ -> }, { _ -> ServerNode() }, { _, _, _ -> DirtyMask.None })
            }
    }
}
