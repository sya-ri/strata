package dev.s7a.strata.internal.platform

import dev.s7a.strata.spi.ExecutionOwnerId

private val agentOwner = ExecutionOwnerId.create()

/**
 * Returns the stable execution identity of this JavaScript agent.
 */
internal actual fun currentThreadOwner(): ExecutionOwnerId = agentOwner
