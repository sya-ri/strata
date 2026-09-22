package dev.s7a.strata.runtime.remote

/**
 * Detached lifecycle handle for one host-owned screen.
 * Status can be read from any thread; close uses the host's execution contract (Paper primary thread or Velocity's UI queue).
 * Terminal handles retain no player, plugin, handler, or UI state references.
 */
public class RemoteScreenSession internal constructor(
    public val identity: Long,
) : AutoCloseable {
    @Volatile
    public var status: RemoteSessionStatus = RemoteSessionStatus.Opening
        private set

    @Volatile
    private var closeOperation: (() -> Unit)? = null

    /**
     * Installs the host's close operation before publishing the handle.
     */
    internal fun bind(close: () -> Unit) {
        check(closeOperation == null && status == RemoteSessionStatus.Opening)
        closeOperation = close
    }

    /**
     * Publishes the latest lifecycle status and releases ownership on termination.
     */
    internal fun update(status: RemoteSessionStatus) {
        if (status is RemoteSessionStatus.Closed) closeOperation = null
        this.status = status
    }

    override fun close() {
        closeOperation?.invoke()
    }
}
