@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.runtime.spi.RuntimeUiControl
import dev.s7a.strata.runtime.spi.RuntimeUiController
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiSession

/**
 * Owner-thread handle for one server-owned screen.
 * Terminal handles retain only their typed result, not the player, plugin, handlers, or UI state.
 */
public class RemoteScreenSession internal constructor(
    public val identity: Long,
    settings: RemoteUiSettings = RemoteUiSettings(),
) : AutoCloseable {
    @Volatile
    public var status: RemoteSessionStatus = RemoteSessionStatus.Opening
        private set

    private var controlOperation: ((RuntimeUiControl) -> Unit)? = null
    internal val controls = RuntimeUiController(settings.presentation, settings.inputPolicy, apply = { checkNotNull(controlOperation)(it) }, close = { closeOwned() })

    /**
     * Common handle used by both opening APIs and server event callbacks.
     */
    public val uiSession: UiSession get() = controls

    /**
     * Installs the sequenced control transport before the first server tick.
     */
    internal fun bindControls(operation: (RuntimeUiControl) -> Unit) {
        controlOperation = operation
    }

    @Volatile
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
        if (status is RemoteSessionStatus.Closed) {
            closeOperation = null
            controlOperation = null
            controls.terminate(status.reason.uiReason)
        }
    }

    override fun close() {
        closeOperation?.invoke()
    }

    private fun closeOwned() {
        val close = closeOperation
        if (close != null) {
            close()
        } else if ((status is RemoteSessionStatus.Closed).not()) {
            update(RemoteSessionStatus.Closed(RemoteFailure.OwnerClosed))
        }
    }
}
