package dev.s7a.strata.runtime.remote

import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue

/**
 * Owner-thread action sender captured by locally installed input behavior.
 * An expired screen rejects calls without sending an event to a subsequent screen.
 */
public fun interface RemoteClientActions {
    /**
     * Sends one declared event and returns its positive monotonic sequence for editing acknowledgements.
     */
    public fun send(
        endpoint: Long,
        type: ProjectionType,
        value: ProjectionValue,
    ): Long
}
