@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.spi.RuntimeExecutionOwner
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.text.UiText

/**
 * One client-side retained declaration source bound to a server-issued session identity.
 * All calls run under the construction execution owner; network receivers must enqueue work for that owner.
 * Invalid patch bases request one snapshot; actions are never replayed during resynchronization.
 */
@Suppress("TooManyFunctions", "TooGenericExceptionCaught") // Owns the session lifecycle and releases extension resources after any callback failure.
public class RemoteClientSession(
    snapshot: RemoteMessage.Snapshot,
    private val registry: RemoteRegistry,
    private val limits: RemoteLimits = RemoteLimits(),
    send: (RemoteMessage) -> Unit,
) : AutoCloseable {
    private val owner = RuntimeExecutionOwner.current()
    public val identity: Long = snapshot.session
    private val pausesGame = snapshot.pausesGame
    private val current = mutableStateOf<RemotePreparedTree?>(registry.prepare(snapshot.tree, limits))
    private val states = RemoteClientStates(limits)
    private var outgoing: ((RemoteMessage) -> Unit)? = send
    private var revision: Long = snapshot.revision
    private var nextSequence: Long = 1
    private var acknowledged: Long = 0
    private var awaitingSnapshot: Boolean = false
    private val actions = RemoteClientActions(::sendAction)

    public var status: RemoteSessionStatus = RemoteSessionStatus.Open
        private set

    init {
        require(0 < identity && 0 < revision) { "Invalid remote screen identity or revision." }
        guarded {
            checkNotNull(current.value).prepare(states, limits)
            acknowledgeRevision()
        }
    }

    /**
     * Creates one ordinary screen whose content observes committed remote declarations.
     * The enclosing platform owns presentation and must close this session when that screen terminates.
     */
    public fun definition(title: UiText): ScreenDefinition {
        checkActive()
        return ScreenDefinition(title, pausesGame = pausesGame) {
            element(checkNotNull(current.value) { "Remote screen is closed." }.build(actions, states, limits))
        }
    }

    /**
     * Applies an atomic validated patch, or requests a snapshot when its base is unavailable.
     */
    public fun receive(update: RemoteMessage.Update) {
        checkActive()
        guarded {
            states.flushEdits(actions)
            require(update.session == identity) { "Remote update targets another screen." }
            if (update.revision <= revision) return
            if (update.baseRevision != revision || awaitingSnapshot) {
                requestSnapshot()
                return
            }
            val candidate = update.patch.apply(checkNotNull(current.value).tree, limits)
            val prepared = registry.prepare(candidate, limits, current.value)
            prepared.prepare(states, limits)
            current.value = prepared
            revision = update.revision
            acknowledgeRevision()
        }
    }

    /**
     * Accepts a complete newer snapshot without replacing the screen host or replaying actions.
     */
    public fun receive(snapshot: RemoteMessage.Snapshot) {
        checkActive()
        guarded {
            states.flushEdits(actions)
            require(snapshot.session == identity) { "Remote snapshot targets another screen." }
            require(snapshot.pausesGame == pausesGame) { "Remote screen metadata cannot change during resynchronization." }
            if (snapshot.revision < revision) return
            val prepared = registry.prepare(snapshot.tree, limits, current.value)
            prepared.prepare(states, limits)
            current.value = prepared
            revision = snapshot.revision
            awaitingSnapshot = false
            acknowledgeRevision()
        }
    }

    /**
     * Records an ordered acknowledgement without acknowledging unsent client operations.
     */
    public fun receive(acknowledgement: RemoteMessage.Acknowledgement) {
        checkActive()
        require(acknowledgement.session == identity && acknowledgement.sequence < nextSequence) { "Invalid remote acknowledgement." }
        acknowledged = maxOf(acknowledged, acknowledgement.sequence)
    }

    /**
     * Ends this screen and releases its tree and transport captures, even if sending close fails.
     */
    public fun close(
        reason: RemoteFailure,
        notifyPeer: Boolean = true,
    ) {
        checkOwner()
        if (status is RemoteSessionStatus.Closed) return
        status = RemoteSessionStatus.Closed(reason)
        val send = outgoing
        outgoing = null
        current.value = null
        val release = runCatching(states::close)
        val notification = runCatching { if (notifyPeer) send?.invoke(RemoteMessage.Close(identity, reason)) }
        val failure = release.exceptionOrNull()
        if (failure != null) {
            notification.exceptionOrNull()?.let { if (it !== failure) failure.addSuppressed(it) }
            throw failure
        }
        notification.getOrThrow()
    }

    override fun close() {
        close(RemoteFailure.PeerClosed)
    }

    /**
     * Flushes committed text edits before the adapter flushes its bounded outgoing transport.
     */
    public fun flushEdits() {
        checkActive()
        states.flushEdits(actions)
    }

    private fun sendAction(
        endpoint: Long,
        type: ProjectionType,
        value: ProjectionValue,
    ): Long {
        checkActive()
        states.flushEdits(actions)
        require(0 < endpoint) { "Invalid remote event endpoint." }
        check(nextSequence < Long.MAX_VALUE) { "Remote action sequence space is exhausted." }
        val sequence = nextSequence++
        checkNotNull(outgoing)(RemoteMessage.Action(identity, sequence, endpoint, type, value))
        return sequence
    }

    private fun requestSnapshot() {
        if (awaitingSnapshot.not()) {
            awaitingSnapshot = true
            checkNotNull(outgoing)(RemoteMessage.Resynchronize(identity))
        }
    }

    private fun acknowledgeRevision() {
        checkNotNull(outgoing)(RemoteMessage.Applied(identity, revision))
    }

    private fun checkActive() {
        checkOwner()
        check((status is RemoteSessionStatus.Closed).not()) { "Remote screen is closed." }
    }

    private inline fun <T> guarded(block: () -> T): T =
        try {
            block()
        } catch (failure: Throwable) {
            val reason = (failure as? RemoteProtocolException)?.reason ?: RemoteFailure.InvalidMessage
            runCatching { close(reason) }.exceptionOrNull()?.let { if (it !== failure) failure.addSuppressed(it) }
            throw failure
        }

    private fun checkOwner() {
        check(RuntimeExecutionOwner.current() === owner) { "Remote screen belongs to another execution owner." }
    }
}
