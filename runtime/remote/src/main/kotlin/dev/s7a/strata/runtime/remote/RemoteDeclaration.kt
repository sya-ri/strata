package dev.s7a.strata.runtime.remote

import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue

/**
 * Detached typed component or modifier properties for one stable retained identity.
 */
public data class RemoteDeclaration(
    public val identity: Long,
    public val type: ProjectionType,
    public val value: ProjectionValue,
) {
    init {
        require(0 < identity) { "Remote declaration identities must be positive." }
    }
}
