package dev.s7a.strata.internal.platform

import dev.s7a.strata.spi.ExecutionOwnerId

/**
 * Returns the stable execution identity of the current JVM thread or JavaScript agent.
 */
internal expect fun currentThreadOwner(): ExecutionOwnerId
