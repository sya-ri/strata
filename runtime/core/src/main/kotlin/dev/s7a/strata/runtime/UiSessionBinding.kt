package dev.s7a.strata.runtime

import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSubscription
import java.util.concurrent.locks.ReentrantLock
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
    private val lock = ReentrantLock()
    private var committed: StateSnapshot<T>? = null
    private var pending: StateSnapshot<T>? = null
    private var captured: StateSnapshot<T>? = null
    private var subscription: StateSubscription<T>? = null
    private var closeRequested: Boolean = false
    private var disabled: Boolean = false

    /**
     * Reads the currently committed value and never consumes a pending callback.
     */
    override fun getValue(
        thisRef: Any?,
        property: KProperty<*>,
    ): T {
        checkReadable()
        return checkNotNull(committed) { "A binding has not received its initial snapshot." }.value
    }

    /**
     * Enqueues the newest callback snapshot and returns normally on every lifecycle state.
     *
     * @param snapshot the source observation to coalesce.
     */
    fun enqueue(snapshot: StateSnapshot<T>) {
        lock.lock()
        try {
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
        } finally {
            lock.unlock()
        }
    }

    /**
     * Installs the source subscription, or closes it immediately when cleanup won a race.
     *
     * @param nextSubscription the handle returned by source subscription.
     */
    fun install(nextSubscription: StateSubscription<T>) {
        var closeNow = false
        lock.lock()
        try {
            if (closeRequested || disabled) {
                closeNow = true
            } else {
                subscription = nextSubscription
            }
        } finally {
            lock.unlock()
        }
        if (closeNow) {
            nextSubscription.close()
        }
    }

    /**
     * Commits the subscription's linearization snapshot while retaining a newer queued callback.
     *
     * @param initial the source-provided initial snapshot.
     */
    fun commitInitial(initial: StateSnapshot<T>) {
        lock.lock()
        try {
            val queued = pending
            if (queued == null || queued.revision <= initial.revision) {
                pending = null
            }
            committed = initial
        } finally {
            lock.unlock()
        }
    }

    /**
     * Takes the newest pending observation without committing or comparing caller-owned values.
     *
     * The session captures every binding and retained cutoff node before applying any captured observation.
     * Only one transaction-local snapshot is retained until [applyPending] or terminal cleanup.
     */
    fun capturePending() {
        lock.lock()
        try {
            captured = pending
            pending = null
        } finally {
            lock.unlock()
        }
    }

    /**
     * Commits only the snapshot captured before this frame began owner-thread value comparisons.
     *
     * @return true when the committed value changed by equality.
     */
    fun applyPending(): Boolean {
        var oldValue: T?
        var nextValue: T
        lock.lock()
        try {
            val next = captured ?: return false
            captured = null
            oldValue = committed?.value
            nextValue = next.value
            committed = next
        } finally {
            lock.unlock()
        }
        beginMutation()
        try {
            return oldValue != nextValue
        } finally {
            endMutation()
        }
    }

    /**
     * Disables callbacks and discards pending values during session cleanup.
     */
    fun disable() {
        lock.lock()
        try {
            disabled = true
            pending = null
            captured = null
        } finally {
            lock.unlock()
        }
    }

    /**
     * Claims and closes the subscription exactly once without holding the binding lock.
     *
     * @return the source cleanup failure, if any.
     */
    fun closeSubscription(): Throwable? {
        var toClose: StateSubscription<T>?
        lock.lock()
        try {
            closeRequested = true
            toClose = subscription
            subscription = null
        } finally {
            lock.unlock()
        }
        val subscriptionToClose = toClose
        return if (subscriptionToClose == null) {
            null
        } else {
            runCatching { subscriptionToClose.close() }.exceptionOrNull()
        }
    }
}
