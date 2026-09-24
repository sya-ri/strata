package dev.s7a.strata.internal.platform

import dev.s7a.strata.spi.ExecutionOwnerId
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.spi.RuntimeExecutionOwner

/**
 * Captures the adapter's serial owner, preserving physical thread confinement outside explicit runtime scopes.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal fun currentOwner(): ExecutionOwnerId = RuntimeExecutionOwner.current()
