package dev.s7a.strata.runtime.paper

import dev.s7a.strata.runtime.remote.RemoteFailure
import dev.s7a.strata.runtime.remote.RemoteSessionStatus

/**
 * Owner-thread handle for one server-owned screen.
 * Terminal handles retain only their typed result, not the player, plugin, handlers, or UI state.
 */
public class PaperScreenSession internal constructor(
    public val identity: Long,
) : AutoCloseable {
    public var status: RemoteSessionStatus = RemoteSessionStatus.Opening
        private set

    private var closeOperation: (() -> Unit)? = null

    /**
     * Installs the service's close operation after the session becomes owned.
     */
    internal fun bind(close: () -> Unit) {
        check(closeOperation == null && status == RemoteSessionStatus.Opening)
        closeOperation = close
    }

    /**
     * Publishes the latest remote lifecycle status and releases ownership on termination.
     */
    internal fun update(status: RemoteSessionStatus) {
        this.status = status
        if (status is RemoteSessionStatus.Closed) closeOperation = null
    }

    override fun close() {
        PaperScreens.checkThread()
        val close = closeOperation
        if (close != null) {
            close()
        } else if ((status is RemoteSessionStatus.Closed).not()) {
            update(RemoteSessionStatus.Closed(RemoteFailure.OwnerClosed))
        }
    }
}
