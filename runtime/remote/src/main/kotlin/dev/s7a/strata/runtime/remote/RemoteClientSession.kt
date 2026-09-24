@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.runtime.spi.RuntimeUiControl
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.text.UiText
import dev.s7a.strata.ui.UiDefinition
import dev.s7a.strata.ui.UiInputPolicy
import dev.s7a.strata.ui.UiInteractionMode
import dev.s7a.strata.ui.UiOperationResult
import dev.s7a.strata.ui.UiPresentation
import dev.s7a.strata.ui.UiRejection
import dev.s7a.strata.ui.UiSession
import dev.s7a.strata.ui.UiSessionStatus

/**
 * One client-side retained declaration source bound to a server-issued session identity.
 * All calls run on the construction thread and network receivers must enqueue onto that thread.
 * Invalid patch bases request one snapshot; actions are never replayed during resynchronization.
 */
@Suppress("TooManyFunctions", "TooGenericExceptionCaught") // Owns the session lifecycle and releases extension resources after any callback failure.
public class RemoteClientSession(
    snapshot: RemoteMessage.Snapshot,
    private val registry: RemoteRegistry,
    private val limits: RemoteLimits = RemoteLimits(),
    send: (RemoteMessage) -> Unit,
) : AutoCloseable {
    private val owner = Thread.currentThread()
    public val identity: Long = snapshot.session
    private val pausesGame = snapshot.pausesGame
    private val settings = snapshot.settings
    private val initialControl = snapshot.control
    private val publicSession = ClientUiSession()

    /**
     * Receiver for synchronous client extension events; all controls are sequenced by the server.
     */
    public val uiSession: UiSession get() = publicSession

    /**
     * Current declaration count for connection-wide admission.
     */
    public val nodeCount: Int get() =
        current.value
            ?.tree
            ?.nodes
            ?.size ?: 0

    /**
     * Binds native ownership once opening has succeeded.
     */
    public fun bindUiSession(session: UiSession) {
        checkActive()
        check(publicSession.native == null)
        publicSession.native = session
        publicSession.observedInteraction = session.interactionMode
    }

    /**
     * Reports native interaction termination, including Escape, hidden HUDs, and focus loss, to the owning server.
     * Call after native lifecycle processing; unchanged modes never enqueue another request.
     */
    public fun synchronizeInteraction() {
        checkActive()
        val native = publicSession.native ?: return
        if (native.status is UiSessionStatus.Closed) return
        val mode = native.interactionMode
        if (publicSession.observedInteraction == mode) return
        publicSession.observedInteraction = mode
        publicSession.setInteractionMode(mode)
    }

    /**
     * Records native application without accepting older authoritative controls.
     */
    public fun controlApplied(
        state: RuntimeUiControl,
        rejection: UiRejection?,
    ) {
        checkActive()
        publicSession.applied(state, rejection)
    }

    /**
     * Completes only the matching client intent; authoritative application is reported separately.
     */
    public fun receive(receipt: RemoteMessage.ControlReceipt) {
        checkActive()
        require(receipt.session == identity) { "UI control receipt targets another session." }
        publicSession.received(receipt)
    }

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
    public fun definition(title: UiText): UiDefinition {
        checkActive()
        return UiDefinition(title, presentation = initialControl?.presentation ?: settings.presentation, category = settings.category, inputPolicy = initialControl?.inputPolicy ?: settings.inputPolicy, visibility = settings.visibility, hudOrder = settings.hudOrder, pausesGame = pausesGame) {
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
        publicSession.native = null
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

    /**
     * Client control facade retains only detached applied values after native ownership ends.
     */
    private inner class ClientUiSession : UiSession {
        var native: UiSession? = null
        var observedInteraction: UiInteractionMode? = null
        private var confirmed: RuntimeUiControl? = null
        private var desired: RuntimeUiControl? = null
        private var sequence = 0L
        private var appliedSequence = 0L
        private var rejection: UiRejection? = null

        override val presentation: UiPresentation? get() = confirmed?.presentation
        override val inputPolicy: UiInputPolicy get() = confirmed?.inputPolicy ?: settings.inputPolicy
        override val interactionMode: UiInteractionMode get() = if (status is UiSessionStatus.Closed) UiInteractionMode.None else confirmed?.interactionMode ?: UiInteractionMode.None
        override val status: UiSessionStatus
            get() {
                val terminal = this@RemoteClientSession.status as? RemoteSessionStatus.Closed
                if (terminal != null) return UiSessionStatus.Closed(terminal.reason.uiReason)
                val nativeStatus = native?.status
                if (nativeStatus is UiSessionStatus.Closed) return nativeStatus
                if (confirmed == null) return UiSessionStatus.Opening
                return desired?.let { UiSessionStatus.Switching(it.presentation) } ?: UiSessionStatus.Ready(rejection)
            }

        override fun switch(presentation: UiPresentation): UiOperationResult =
            request {
                if (it.presentation == presentation) it else it.copy(presentation = presentation, interactionMode = if (presentation == UiPresentation.Hud) UiInteractionMode.None else UiInteractionMode.Cursor)
            }

        override fun setInputPolicy(policy: UiInputPolicy): UiOperationResult = request { it.copy(inputPolicy = policy) }

        override fun setInteractionMode(mode: UiInteractionMode): UiOperationResult = request { it.copy(interactionMode = mode) }

        override fun close() {
            checkOwner()
            native?.close()
        }

        fun applied(
            state: RuntimeUiControl,
            failure: UiRejection?,
        ) {
            if (appliedSequence < state.sequence) {
                appliedSequence = state.sequence
                if (failure == null) {
                    confirmed = state
                    observedInteraction = state.interactionMode
                }
                rejection = failure
            }
        }

        fun received(receipt: RemoteMessage.ControlReceipt) {
            require(0 < receipt.sequence && receipt.sequence <= sequence) { "Invalid UI control receipt sequence." }
            if (desired?.sequence != receipt.sequence) return
            desired = null
            if (receipt.rejection != null) rejection = receipt.rejection
        }

        private fun request(change: (RuntimeUiControl) -> RuntimeUiControl): UiOperationResult {
            checkOwner()
            if (status is UiSessionStatus.Closed) return UiOperationResult.Rejected(UiRejection.Closed)
            val before = desired ?: confirmed ?: initialControl ?: RuntimeUiControl(0, settings.presentation, settings.inputPolicy, UiInteractionMode.None)
            val next = change(before)
            if (next == before) return UiOperationResult.Accepted
            check(sequence < Long.MAX_VALUE) { "Client UI control sequence is exhausted." }
            val request = next.copy(sequence = ++sequence)
            desired = request
            checkNotNull(outgoing)(RemoteMessage.ControlRequest(identity, request))
            return UiOperationResult.Accepted
        }
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
        check(Thread.currentThread() === owner) { "Remote screen belongs to another thread." }
    }
}
