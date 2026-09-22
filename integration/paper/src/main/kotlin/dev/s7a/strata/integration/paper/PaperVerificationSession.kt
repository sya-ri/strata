package dev.s7a.strata.integration.paper

import java.util.UUID

/**
 * Primary-thread ownership shared by automated protocol and manual operating-system input fixtures.
 */
internal interface PaperVerificationSession : AutoCloseable {
    val playerId: UUID

    /**
     * Advances assertions and returns true when the fixture has completed or terminated.
     */
    fun tick(): Boolean
}
