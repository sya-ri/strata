package dev.s7a.strata.runtime.remote

import dev.s7a.strata.projection.ProjectionType
import java.util.Collections

/**
 * Agreed limits and supported declaration schemas for one live player connection.
 * A missing value at the adapter boundary means that negotiation has not completed.
 */
public class RemoteCapabilities(
    public val limits: RemoteLimits,
    types: Set<ProjectionType>,
) {
    public val types: Set<ProjectionType> = Collections.unmodifiableSet(types.toSet())
}
