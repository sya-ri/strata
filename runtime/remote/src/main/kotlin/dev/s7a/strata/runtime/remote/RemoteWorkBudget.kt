package dev.s7a.strata.runtime.remote

/**
 * One owner-thread admission budget for decoding or reconstructing a single message.
 * Checks surround trusted extension callbacks; callbacks themselves must remain nonblocking.
 * Elapsed time uses a monotonic clock and is never transmitted as a peer-selected timestamp.
 */
internal class RemoteWorkBudget(
    private val limits: RemoteLimits,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val started = nanoTime()
    private var entries = 0

    /**
     * Accounts for one visited value or declaration before further traversal or allocation.
     */
    fun visit() {
        if (limits.valueEntries <= entries) throw RemoteProtocolException(RemoteFailure.ResourceLimit, "Remote structure exceeds its aggregate value budget.")
        entries++
        checkTime()
    }

    /**
     * Rejects work that exceeds the negotiated reconstruction deadline.
     */
    fun checkTime() {
        if (limits.reconstructionMillis * 1_000_000L < nanoTime() - started) throw RemoteProtocolException(RemoteFailure.TimedOut, "Remote reconstruction exceeded its deadline.")
    }
}
