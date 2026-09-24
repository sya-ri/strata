package dev.s7a.strata.internal.platform

import dev.s7a.strata.spi.ExecutionOwnerId

// A plain marker cannot retain Strata's class loader through a long-lived host thread.
private val threadOwner = ThreadLocal.withInitial { Any() }

/**
 * Retains one opaque identity per physical thread without depending on Thread equality.
 */
internal actual fun currentThreadOwner(): ExecutionOwnerId = ExecutionOwnerId.create(threadOwner.get())
