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
 */
@InternalStrataRuntimeApi
public class RuntimeDeclaration internal constructor(
    public val identity: Long,
    public val element: Element,
    public val projection: DeclarationProjection<*>?,
    modifiers: List<Modifier>,
    children: List<RuntimeDeclaration>,
) {
    public val modifiers: List<Modifier> = modifiers.toList()
    public val children: List<RuntimeDeclaration> = children.toList()

    /**
     * A stable active modifier identity and its current immutable description.
     */
    public class Modifier internal constructor(
        public val identity: Long,
        public val element: ModifierElement,
        public val projection: DeclarationProjection<*>?,
    )
}
