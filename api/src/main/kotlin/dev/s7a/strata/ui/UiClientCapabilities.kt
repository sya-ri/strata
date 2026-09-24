package dev.s7a.strata.ui

import dev.s7a.strata.projection.ProjectionType

/**
 * Detached client support advertised by a successfully negotiated Strata connection.
 * [types] contains exact component, modifier, and action schemas; [hudLimit] is the negotiated HUD capacity.
 * Transport framing and implementation classes are outside this application contract.
 */
public class UiClientCapabilities(
    types: Set<ProjectionType>,
    public val hudLimit: Int,
) {
    public val types: Set<ProjectionType> = types.toSet()

    init {
        require(0 <= hudLimit) { "HUD capacity must not be negative." }
    }
}
