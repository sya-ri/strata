@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.element.Element
import dev.s7a.strata.projection.DeclarationProjection
import dev.s7a.strata.projection.ProjectionAction
import dev.s7a.strata.projection.ProjectionBinding
import dev.s7a.strata.projection.ProjectionScope
import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.spi.RuntimeDeclaration
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import java.util.Collections

/**
 * One authoritative screen bound by its transport owner to one authenticated connection.
 * All calls run on the construction thread; source callbacks remain owned by the shared core session.
 * Only the current tree and current action table are retained, and terminal cleanup releases both.
 */
@Suppress("TooManyFunctions") // Owns the complete declaration, action, resynchronization, and terminal session lifecycle.
public class RemoteServerSession(
    public val identity: Long,
    private val title: ProjectionValue,
    supportedTypes: Set<ProjectionType>,
    private val limits: RemoteLimits = RemoteLimits(),
    send: (RemoteMessage) -> Unit,
    private val pausesGame: Boolean = false,
    content: () -> Element,
) : AutoCloseable {
    private val owner = Thread.currentThread()
    private var outgoing: ((RemoteMessage) -> Unit)? = send
    private val supported = supportedTypes.toSet()
    private val session = createRuntimeUiSession(content)
    private var previous: RemoteTree? = null
    private val projectingTypes = mutableSetOf<ProjectionType>()

    /**
     * Exact component, modifier, and nested behavior schemas required by the currently committed screen.
     * The transport owner can withdraw extension ownership and close affected screens before unloading its plugin.
     */
    public var requiredTypes: Set<ProjectionType> = emptySet()
        private set
    private var actions: Map<Long, Endpoint> = emptyMap()
    private var slots: Map<ActionSlot, Long> = emptyMap()
    private var nextAction: Long = 1
    private var revision: Long = 0
    private var processedSequence: Long = 0
    private var executingSequence: Long = 0
    private val bindings = RemoteServerBindings(limits) { executingSequence }
    private var acknowledgedSequence: Long = 0
    private var started: Boolean = false
    private var busy: Boolean = false

    public var status: RemoteSessionStatus = RemoteSessionStatus.Opening
        private set

    /**
     * Largest revision the authenticated client has confirmed installing, or zero before its first confirmation.
     * A confirmation does not establish that native rendering or human observation has occurred.
     */
    public var appliedRevision: Long = 0
        private set

    init {
        require(0 < identity) { "Remote session identity must be positive." }
    }

    /**
     * Attaches once, commits the declaration cutoff, and sends an initial snapshot or changed records.
     */
    public fun tick() {
        operation {
            if (started.not()) {
                session.attach()
                started = true
            }
            val projected = session.projectDeclarations(::project)
            val old = previous
            if (old != projected) {
                check(revision < Long.MAX_VALUE) { "Remote revision space is exhausted." }
                val base = revision++
                previous = projected
                if (old == null) {
                    send(RemoteMessage.Snapshot(identity, revision, title, projected, pausesGame))
                    status = RemoteSessionStatus.Open
                } else {
                    send(RemoteMessage.Update(identity, base, revision, RemotePatch.between(old, projected)))
                }
            }
            acknowledge()
        }
    }

    /**
     * Validates an ordered peer action before invoking it through the core input boundary.
     * Duplicate or retired endpoints never execute callbacks; their sequences are still acknowledged.
     */
    public fun receive(action: RemoteMessage.Action) {
        if (started && processedSequence < action.sequence) tick()
        operation {
            protocol(action.session == identity && started && 0 < action.sequence) { "Action targets an unavailable session." }
            if (action.sequence <= processedSequence) {
                send(RemoteMessage.Acknowledgement(identity, processedSequence, revision))
                return@operation
            }
            protocol(action.sequence == processedSequence + 1) { "Out-of-order remote action." }
            val endpoint = actions[action.endpoint]
            if (endpoint != null && endpoint.enabled) {
                val handler = endpoint.action
                protocol(handler.type == action.type) { "Remote event schema does not match its endpoint." }
                val operation =
                    runCatching { handler.prepare(action.value) }.getOrElse {
                        throw RemoteProtocolException(RemoteFailure.InvalidMessage, "Invalid remote event value.", it)
                    }
                executingSequence = action.sequence
                try {
                    session.dispatchAction(operation)
                } finally {
                    executingSequence = 0
                }
            }
            processedSequence = action.sequence
        }
    }

    /**
     * Accepts a typed action or declaration acknowledgement from this session's authenticated connection.
     * Future or cross-session acknowledgements fail the session; duplicates and older confirmations are harmless.
     */
    public fun receive(message: RemoteMessage) {
        when (message) {
            is RemoteMessage.Action -> {
                receive(message)
            }

            is RemoteMessage.Applied -> {
                operation {
                    protocol(message.session == identity && started && 0 < message.revision && message.revision <= revision) { "Invalid applied revision acknowledgement." }
                    appliedRevision = maxOf(appliedRevision, message.revision)
                }
            }

            else -> {
                operation { throw RemoteProtocolException(RemoteFailure.InvalidMessage, "Unexpected server session message.") }
            }
        }
    }

    /**
     * Sends the last committed complete snapshot without replaying any operations.
     */
    public fun resynchronize() {
        operation {
            previous?.let { send(RemoteMessage.Snapshot(identity, revision, title, it, pausesGame)) }
        }
    }

    /**
     * Ends ownership and optionally notifies the peer; notification failure cannot skip cleanup.
     */
    public fun close(
        reason: RemoteFailure,
        notifyPeer: Boolean = true,
    ) {
        checkOwner()
        if (status is RemoteSessionStatus.Closed) return
        check(busy.not()) { "Remote session operations cannot reenter." }
        finish(reason, notifyPeer)
    }

    override fun close() {
        close(RemoteFailure.OwnerClosed)
    }

    private fun project(root: RuntimeDeclaration): RemoteTree {
        bindings.begin()
        projectingTypes.clear()
        val nextSlots = mutableMapOf<ActionSlot, Long>()
        val nextActions = mutableMapOf<Long, Endpoint>()
        val nodes = ArrayList<RemoteNode>()

        fun export(
            identity: Long,
            projection: DeclarationProjection<*>?,
            enabled: Boolean,
        ): RemoteDeclaration {
            val declaration = projection ?: throw RemoteProtocolException(RemoteFailure.UnsupportedType, "A declaration does not support remote projection.")
            if ((declaration.type in supported).not()) {
                throw RemoteProtocolException(RemoteFailure.UnsupportedType, "The client does not support ${declaration.type}.")
            }
            projectingTypes.add(declaration.type)
            val scope = ActionScope(identity, enabled && declaration.inputEnabled, nextSlots, nextActions)
            val value =
                try {
                    declaration.encode(scope)
                } finally {
                    scope.active = false
                }
            return RemoteDeclaration(identity, declaration.type, value)
        }

        fun visit(
            declaration: RuntimeDeclaration,
            depth: Int,
            enabled: Boolean,
        ) {
            require(depth < limits.valueDepth && nodes.size < limits.treeNodes) { "Projected tree exceeds its limit." }
            val projection = declaration.projection
            val inputEnabled = enabled && projection?.inputEnabled != false
            val component = export(declaration.identity, projection, inputEnabled)
            val modifiers = declaration.modifiers.map { export(it.identity, it.projection, inputEnabled) }
            nodes.add(RemoteNode(component, modifiers, declaration.children.map { it.identity }))
            declaration.children.forEach { visit(it, depth + 1, inputEnabled) }
        }
        visit(root, 0, true)
        val tree = RemoteTree(root.identity, nodes, limits)
        slots = nextSlots
        actions = nextActions
        bindings.commit()
        requiredTypes = Collections.unmodifiableSet(projectingTypes.toSet())
        projectingTypes.clear()
        return tree
    }

    private fun acknowledge() {
        if (acknowledgedSequence < processedSequence) {
            send(RemoteMessage.Acknowledgement(identity, processedSequence, revision))
            acknowledgedSequence = processedSequence
        }
    }

    private fun operation(block: () -> Unit) {
        checkOwner()
        check((status is RemoteSessionStatus.Closed).not()) { "Remote session is closed." }
        check(busy.not()) { "Remote session operations cannot reenter." }
        busy = true
        try {
            runCatching(block).getOrElse { failure ->
                val reason = (failure as? RemoteProtocolException)?.reason ?: RemoteFailure.HandlerFailed
                runCatching { finish(reason, true) }.exceptionOrNull()?.let { cleanup ->
                    if (failure !== cleanup) failure.addSuppressed(cleanup)
                }
                throw failure
            }
        } finally {
            busy = false
        }
    }

    private fun finish(
        reason: RemoteFailure,
        notifyPeer: Boolean,
    ) {
        val notify = outgoing
        outgoing = null
        status = RemoteSessionStatus.Closed(reason)
        previous = null
        requiredTypes = emptySet()
        projectingTypes.clear()
        actions = emptyMap()
        slots = emptyMap()
        bindings.close()
        val cleanup = runCatching(session::close)
        val notification = runCatching { if (notifyPeer) notify?.invoke(RemoteMessage.Close(identity, reason)) }
        val primary = cleanup.exceptionOrNull()
        if (primary != null) {
            notification.exceptionOrNull()?.let { if (it !== primary) primary.addSuppressed(it) }
            throw primary
        }
        notification.getOrThrow()
    }

    private fun send(message: RemoteMessage) {
        checkNotNull(outgoing) { "Remote transport is closed." }(message)
    }

    private fun checkOwner() {
        check(Thread.currentThread() === owner) { "Remote session belongs to another thread." }
    }

    private inline fun protocol(
        valid: Boolean,
        message: () -> String,
    ) {
        if (valid.not()) throw RemoteProtocolException(RemoteFailure.InvalidMessage, message())
    }

    /**
     * One declared event position under a retained component or modifier.
     */
    private data class ActionSlot(
        val owner: Long,
        val index: Int,
        val type: ProjectionType,
        val key: ProjectionValue,
    )

    private class Endpoint(
        val action: ProjectionAction<*>,
        val enabled: Boolean,
    )

    /**
     * Enforces callback lifetime and reuses endpoint identities only for current compatible declarations.
     */
    private inner class ActionScope(
        private val identity: Long,
        private val enabled: Boolean,
        private val nextSlots: MutableMap<ActionSlot, Long>,
        private val nextActions: MutableMap<Long, Endpoint>,
    ) : ProjectionScope {
        override fun requireType(type: ProjectionType) {
            check(active) { "Projection scope is no longer active." }
            if ((type in supported).not()) throw RemoteProtocolException(RemoteFailure.UnsupportedType, "The client does not support $type.")
            projectingTypes.add(type)
        }

        override fun text(text: UiText): ProjectionValue {
            check(active) { "Projection scope is no longer active." }
            return RemoteTextCodec.encode(text)
        }

        override fun image(image: DrawImage): ProjectionValue {
            check(active) { "Projection scope is no longer active." }
            return RemoteImageCodec(limits.messageBytes).encode(ImageSource.Pixels(image))
        }

        var active: Boolean = true
        private var position: Int = 0

        override fun <T : Any> binding(binding: ProjectionBinding<T>): ProjectionValue {
            checkOwner()
            check(active) { "Projection scope has expired." }
            return bindings.project(binding, this)
        }

        override fun action(
            action: ProjectionAction<*>,
            key: ProjectionValue,
        ): Long {
            checkOwner()
            check(active) { "Projection scope has expired." }
            require(nextActions.size < limits.collectionEntries) { "Too many remote actions." }
            val slot = ActionSlot(identity, position++, action.type, key)
            val endpoint =
                slots[slot] ?: run {
                    check(nextAction < Long.MAX_VALUE) { "Remote endpoint identity space is exhausted." }
                    nextAction++
                }
            nextSlots[slot] = endpoint
            nextActions[endpoint] = Endpoint(action, enabled)
            return endpoint
        }
    }
}
