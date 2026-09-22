package dev.s7a.strata.node

import dev.s7a.strata.projection.DeclarationProjection

/**
 * Optional retained projection for resources whose committed state belongs to a node attachment.
 * Read after the shared frame cutoff on the owner thread, without measurement or painting.
 * Returning null declines remote support; reading this property must not mutate state or acquire resources.
 */
public interface DeclarationProjectionNode {
    /**
     * Prepares derived declaration metadata before dynamic children are reconciled.
     * This may publish geometry implied by explicit component properties, but must not measure, draw, invoke business handlers, or mutate session state.
     */
    public fun prepareDeclaration(): Unit = Unit

    public val declarationProjection: DeclarationProjection<*>?
}
