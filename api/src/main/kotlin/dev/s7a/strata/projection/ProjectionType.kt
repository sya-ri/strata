package dev.s7a.strata.projection

import dev.s7a.strata.resource.ResourceId

/**
 * Versioned, platform-neutral declaration or event schema shared by both endpoints.
 * A name identifies a schema, never a JVM class or executable factory supplied by a peer.
 */
public data class ProjectionType(
    public val name: ResourceId,
    public val version: Int = 1,
) {
    init {
        require(0 < version) { "Projection schema versions must be positive." }
    }
}
