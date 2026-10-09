package dev.s7a.strata.runtime.spi

import dev.s7a.strata.element.Element
import dev.s7a.strata.modifier.ModifierElement
import dev.s7a.strata.projection.DeclarationProjection
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Owner-thread declaration snapshot taken after the shared source cutoff and reconciliation.
 * Identities survive compatible reconciliation and are never reused within the owning tree.
 * The snapshot retains descriptions, including their local callbacks, and must never be sent as an object graph.
 * A remote adapter projects it to detached data inside [RuntimeUiSession.projectDeclarations].
 * Modifier and child lists are fresh tree-owned snapshots or immutable current snapshots shared by this tree.
 * The internal constructor never accepts a caller-owned mutable list.
 */
@InternalStrataRuntimeApi
public class RuntimeDeclaration internal constructor(
    public val identity: Long,
    public val element: Element,
    public val projection: DeclarationProjection<*>?,
    public val modifiers: List<Modifier>,
    public val children: List<RuntimeDeclaration>,
    /**
     * Positive owner-tree projection revision, separate from declaration identity and remote wire revision.
     * Equal root revisions prove the complete current fixed subtree is unchanged after preparation and reconciliation.
     * An arbitrary projection encoder always receives a fresh revision, regardless of its previous detached output.
     */
    public val revision: Long,
) {
    /**
     * A stable active modifier identity and its current immutable description.
     */
    public class Modifier internal constructor(
        public val identity: Long,
        public val element: ModifierElement,
        public val projection: DeclarationProjection<*>?,
    )
}
