package dev.s7a.strata.runtime

import dev.s7a.strata.runtime.platform.synchronized
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSubscription
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

/**
 * Revision-coalescing source binding owned by one [UiSession].
 *
 * Callbacks only update the lock-protected pending snapshot and never run session work.
 * Initial and callback-before-return observations are merged by revision.
 * Subscription close claims the handle under the lock and runs source cleanup after unlocking.
 *
 * @param T the source value type.
 * @param checkReadable validates owner thread and session lifecycle for property reads.
 * @param beginMutation claims the session-wide value comparison guard.
 * @param endMutation releases the session-wide value comparison guard.
 */
internal class UiSessionBinding<T>(
    private val checkReadable: () -> Unit,
    private val beginMutation: () -> Unit,
    private val endMutation: () -> Unit,
) : ReadOnlyProperty<Any?, T> {
    private val monitor = Any()
    private var committed: StateSnapshot<T>? = null
    private var pending: StateSnapshot<T>? = null
    private var captured: StateSnapshot<T>? = null
    private var subscription: StateSubscription<T>? = null
    private var closeRequested: Boolean = false
    private var disabled: Boolean = false

    /**
     * Returns the owner-thread committed value without consuming a pending notification.
     */
    val committedValue: T
        get() {
            checkReadable()
            return checkNotNull(committed) { "A binding has not received its initial snapshot." }.value
        }

    /**
     * Reads the currently committed value and never consumes a pending callback.
     */
    override fun getValue(
        thisRef: Any?,
        property: KProperty<*>,
    ): T = committedValue

    /**
     * Enqueues the newest callback snapshot and returns normally on every lifecycle state.
     *
     * @param snapshot the source observation to coalesce.
     */
    fun enqueue(snapshot: StateSnapshot<T>) {
        synchronized(monitor) {
            if (disabled) {
                return
            }
            val committedRevision = committed?.revision
            if (committedRevision != null && snapshot.revision <= committedRevision) {
                return
            }
            val pendingRevision = pending?.revision
            if (pendingRevision != null && snapshot.revision <= pendingRevision) {
                return
            }
            val capturedRevision = captured?.revision
            if (capturedRevision != null && snapshot.revision <= capturedRevision) {
                return
            }
            pending = snapshot
        }
    }

    /**
     * Installs the source subscription, or closes it immediately when cleanup won a race.
     *
     * @param nextSubscription the handle returned by source subscription.
     */
    fun install(nextSubscription: StateSubscription<T>) {
        val closeNow =
            synchronized(monitor) {
                if (closeRequested || disabled) {
                    true
                } else {
                    subscription = nextSubscription
                    false
                }
            }
        if (closeNow) nextSubscription.close()
    }

    /**
     * Commits the subscription's linearization snapshot while retaining a newer queued callback.
     *
     * @param initial the source-provided initial snapshot.
     */
    fun commitInitial(initial: StateSnapshot<T>) {
        synchronized(monitor) {
            val queued = pending
            if (queued == null || queued.revision <= initial.revision) {
                pending = null
            }
            committed = initial
        }
    }

    /**
     * Takes the newest pending observation without committing or comparing caller-owned values.
     *
     * The session captures every binding and retained cutoff node before applying any captured observation.
     * Only one transaction-local snapshot is retained until [applyPending] or terminal cleanup.
     */
    fun capturePending() {
        synchronized(monitor) {
            captured = pending
            pending = null
        }
    }

    /**
     * Commits only the snapshot captured before this frame began owner-thread value comparisons.
     *
     * @return true when the committed value changed by equality.
     */
    fun applyPending(): Boolean {
        val (previous, next) =
            synchronized(monitor) {
                val next = captured ?: return false
                val previous = committed
                captured = null
                committed = next
                previous to next
            }
        beginMutation()
        try {
            return previous?.value != next.value
        } finally {
            endMutation()
        }
    }

    /**
     * Disables callbacks and discards pending values during session cleanup.
     */
    fun disable() {
        synchronized(monitor) {
            disabled = true
            pending = null
            captured = null
        }
    }

    /**
     * Claims and closes the subscription exactly once without holding the binding lock.
     *
     * @return the source cleanup failure, if any.
     */
    fun closeSubscription(): Throwable? {
        val toClose =
            synchronized(monitor) {
                closeRequested = true
                subscription.also { subscription = null }
            }
        return runCatching { toClose?.close() }.exceptionOrNull()
    }
}
