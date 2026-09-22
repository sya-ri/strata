package dev.s7a.strata.runtime.remote

import java.util.Collections

/**
 * Detached logical component with ordered active modifiers and child identities.
 * Both collections are defensive immutable snapshots.
 */
public class RemoteNode(
    public val declaration: RemoteDeclaration,
    modifiers: List<RemoteDeclaration> = emptyList(),
    children: List<Long> = emptyList(),
) {
    public val modifiers: List<RemoteDeclaration> = Collections.unmodifiableList(modifiers.toList())
    public val children: List<Long> = Collections.unmodifiableList(children.toList())

    override fun equals(other: Any?): Boolean = other is RemoteNode && declaration == other.declaration && modifiers == other.modifiers && children == other.children

    override fun hashCode(): Int = 31 * (31 * declaration.hashCode() + modifiers.hashCode()) + children.hashCode()
}
