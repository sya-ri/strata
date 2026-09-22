package dev.s7a.strata.runtime.remote

/**
 * Optional owner-thread value behavior for flushing committed edits before a business action or client tick.
 * Implementations compare their local value with their last sent value and preserve unacknowledged newer edits.
 */
public fun interface RemoteEditableValue {
    /**
     * Sends pending committed edits through the current screen's ordered operation stream.
     */
    public fun flushEdits(actions: RemoteClientActions)
}
