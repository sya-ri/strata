@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.quality.benchmark

import com.google.gson.Gson
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.projection.BuiltinProjection
import dev.s7a.strata.projection.ProjectionInputCodec
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.runtime.remote.RemoteLifecycleEvent
import dev.s7a.strata.runtime.remote.RemoteMessage
import dev.s7a.strata.runtime.remote.RemoteSessionStatus
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.spi.RuntimeExecutionOwner
import dev.s7a.strata.ui.UiPresentation
import java.lang.ref.Reference
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Independent real-protocol callback, failure, admission and ownership gates.
 * Complete encoded logical message/event/failure prefixes are emitted for both actual runtime sides.
 * Actual negotiated addresses are recorded separately and validated before logical traces are compared.
 */
@Suppress("TooManyFunctions") // One exhaustive dispatcher owns the finite callback/failure registry.
internal object RemoteServiceGateEvidence {
    /**
     * Runs the finite registry before formal collection, independently of the timed case count.
     */
    fun verify() {
        verifyTerminalOracleControls()
        val cases = RemoteServiceGate.cases()
        check(cases.size == 44 && cases.toSet().size == cases.size)
        check(cases.map { it.gate }.toSet() == RemoteServiceGate.entries.toSet())
        cases.forEach { case ->
            val owner = RuntimeExecutionOwner()
            val trace = RemoteServiceTrace(case)
            val ownership = RemoteServiceOwnershipAudit()
            val fleet = owner.run { RemoteServiceFleet(case.topology) }
            owner.run {
                fleet.reporter = { trace.failure(it) }
                fleet.listener = { player, event -> trace.event(player, event) }
                fleet.peerJoined = { peer -> peer.inspect = { trace.message(peer.player, it) } }
                fleet.phaseInspector = {
                    ownership.capture(fleet)
                    trace.phase(fleet)
                }
                fleet.packetInspector = { player, direction, packet -> trace.packet(player, direction, packet) }
            }
            try {
                AutoCloseable {
                    runCatching {
                        owner.run {
                            trace.addresses = fleet.peers.values.map { it.player to it.address }
                            trace.work = listOf(fleet.ticks, fleet.publications, fleet.evaluations, fleet.peers.values.sumOf { it.packets }, fleet.peers.values.sumOf { it.bytes })
                            fleet.listener = { player, event -> trace.event(player, event) }
                            fleet.close()
                            fleet.verifyClosed()
                            inspectTerminalFields(fleet.service)
                            trace.phase(fleet)
                        }
                        ownership.verifyReleased()
                        trace.collectedOwners = ownership.observedCount
                    }.onFailure(trace::verificationFailure).getOrThrow()
                }.use { _ ->
                    runCatching {
                        if (case.gate == RemoteServiceGate.OwnerMigrationIsolationRelease) {
                            migrate(case, owner, fleet, trace)
                        } else {
                            owner.run { execute(case, fleet, trace) }
                        }
                    }.onFailure(trace::verificationFailure).getOrThrow()
                }
            } finally {
                println(Gson().toJson(trace))
            }
        }
    }

    private fun execute(
        case: RemoteServiceGate.Case,
        fleet: RemoteServiceFleet,
        trace: RemoteServiceTrace,
    ) {
        when (case.gate) {
            RemoteServiceGate.ReadyMutation -> ready(case, fleet, trace)

            RemoteServiceGate.TickContentOrReportMutation -> contentMutation(case, fleet, trace)

            RemoteServiceGate.OpenedMutation -> opened(fleet, trace)

            RemoteServiceGate.PresentationChangedMutation -> presentation(fleet, trace)

            RemoteServiceGate.ClosedMutation -> closed(fleet, trace)

            RemoteServiceGate.SamePlayerReplacement -> replacePeer(fleet, trace)

            RemoteServiceGate.TerminalMutation -> terminal(case, fleet, trace)

            RemoteServiceGate.NestedTick -> nested(case, fleet, trace)

            RemoteServiceGate.NestedOpenReceiveRefresh -> nestedRefresh(case, fleet, trace)

            RemoteServiceGate.CallbackReportSendFailure,
            RemoteServiceGate.SessionCloseFailure,
            RemoteServiceGate.AdmissionBudgets,
            RemoteServiceGate.TransitionBudget,
            -> RemoteServiceBoundaryEvidence.execute(case, fleet, trace)

            RemoteServiceGate.DuplicateStaleInput -> stale(fleet, trace)

            RemoteServiceGate.RemoveReadmitShrinkChurn -> shrink(fleet, trace)

            RemoteServiceGate.OwnerMigrationIsolationRelease -> error("Migration requires its physical thread boundary")
        }
    }

    private fun ready(
        case: RemoteServiceGate.Case,
        fleet: RemoteServiceFleet,
        trace: RemoteServiceTrace,
    ) {
        fleet.listener = { player, event ->
            trace.event(player, event)
            if (event is RemoteLifecycleEvent.Ready) {
                repeat(minOf(case.targetSessions, 16)) { fleet.open(player, UiPresentation.Hud) }
                if (case.targetSessions == 17) fleet.open(player, UiPresentation.Screen)
            }
        }
        val target = RemoteServiceShape(1, minOf(case.targetSessions, 16), if (case.targetSessions == 17) 1 else 0)
        fleet.establish(deliveryTicks = target.deliveryTicks)
        check(fleet.handles.size == case.targetSessions)
        check(fleet.sources.values.all { it.subscriptions == 1 })
        check(fleet.handles.values.all { it.status == RemoteSessionStatus.Open })
        check(trace.failures.isEmpty())
    }

    private fun contentMutation(
        case: RemoteServiceGate.Case,
        fleet: RemoteServiceFleet,
        trace: RemoteServiceTrace,
    ) {
        if (case.trigger == RemoteServiceGate.Trigger.ReportClose || case.trigger == RemoteServiceGate.Trigger.ReportReplace) {
            reportMutation(case, fleet, trace)
            return
        }
        fleet.establish()
        var enabled = false
        fleet.handles.values
            .first()
            .close()
        val original =
            fleet.handles.values
                .drop(1)
                .first()
        val callback =
            fleet.open(0, UiPresentation.Hud) {
                if (enabled) {
                    enabled = false
                    when (case.trigger) {
                        RemoteServiceGate.Trigger.Close -> {
                            original.close()
                        }

                        RemoteServiceGate.Trigger.Open -> {
                            fleet.open(0, UiPresentation.Hud)
                        }

                        RemoteServiceGate.Trigger.Replace -> {
                            original.close()
                            fleet.open(0, UiPresentation.Hud)
                        }

                        else -> {
                            error("Invalid content mutation trigger")
                        }
                    }
                }
            }
        enabled = true
        checkNotNull(fleet.sources[callback.identity]).let { it.publish(it.value + 1) }
        fleet.tick()
        fleet.deliver()
        trace.prefix.addAll(fleet.handles.values.map { it.identity to it.status.toString() })
    }

    /**
     * Mutates real membership from the report callback reached by an actual declaration failure.
     * Removal and remove/readmit cover changed cardinality and distinct identities at equal cardinality.
     */
    private fun reportMutation(
        case: RemoteServiceGate.Case,
        fleet: RemoteServiceFleet,
        trace: RemoteServiceTrace,
    ) {
        fleet.establish()
        fleet.handles.values
            .first()
            .close()
        val original =
            fleet.handles.values
                .drop(1)
                .first()
        val primary = IllegalArgumentException("gate declaration report mutation")
        var enabled = false
        val callback =
            fleet.open(0, UiPresentation.Hud) {
                if (enabled) {
                    enabled = false
                    throw primary
                }
            }
        var mutated = false
        fleet.reporter = { failure ->
            trace.failure(failure)
            if (failure === primary && mutated.not()) {
                mutated = true
                original.close()
                if (case.trigger == RemoteServiceGate.Trigger.ReportReplace) fleet.open(0, UiPresentation.Hud)
            }
        }
        enabled = true
        checkNotNull(fleet.sources[callback.identity]).let { it.publish(it.value + 1) }
        fleet.tick()
        fleet.deliver()
        check(mutated && trace.throwables.any { it === primary })
        check(original.status is RemoteSessionStatus.Closed)
        trace.prefix.addAll(fleet.handles.values.map { it.identity to it.status.toString() })
    }

    private fun opened(
        fleet: RemoteServiceFleet,
        trace: RemoteServiceTrace,
    ) {
        var replaced = false
        fleet.listener = { player, event ->
            trace.event(player, event)
            if (event is RemoteLifecycleEvent.Opened && replaced.not()) {
                replaced = true
                event.session.close()
                fleet.open(player, UiPresentation.Hud)
            }
        }
        fleet.establish(verifyApplied = false)
        check(replaced)
        check(fleet.handles.values.count { it.status is RemoteSessionStatus.Closed } == 1)
        check(fleet.subscriptions == fleet.topology.sessions)
    }

    private fun presentation(
        fleet: RemoteServiceFleet,
        trace: RemoteServiceTrace,
    ) {
        fleet.establish()
        val foreground = fleet.handles.values.last()
        var changed = false
        fleet.listener = { player, event ->
            trace.event(player, event)
            if (event is RemoteLifecycleEvent.PresentationChanged && changed.not()) {
                changed = true
                event.session.close()
                fleet.open(player, UiPresentation.Screen)
            }
        }
        foreground.uiSession.switch(UiPresentation.Hud)
        fleet.tick()
        fleet.deliver()
        fleet.acknowledge()
        fleet.tick()
        fleet.deliver()
        check(changed && foreground.status is RemoteSessionStatus.Closed)
        check(
            fleet.handles.values
                .take(4)
                .all { it.status == RemoteSessionStatus.Open },
        )
    }

    private fun closed(
        fleet: RemoteServiceFleet,
        trace: RemoteServiceTrace,
    ) {
        fleet.establish()
        var replaced = false
        fleet.listener = { player, event ->
            trace.event(player, event)
            if (event is RemoteLifecycleEvent.Closed && replaced.not()) {
                replaced = true
                fleet.open(player, UiPresentation.Hud)
            }
        }
        fleet.handles.values
            .first()
            .close()
        fleet.tick()
        fleet.deliver()
        check(
            replaced && fleet.handles.values
                .last()
                .status == RemoteSessionStatus.Open,
        )
        check(fleet.subscriptions == 1)
    }

    private fun replacePeer(
        fleet: RemoteServiceFleet,
        trace: RemoteServiceTrace,
    ) {
        var replaced = false
        fleet.listener = { player, event ->
            trace.event(player, event)
            if (event is RemoteLifecycleEvent.Opened && player == 0 && replaced.not()) {
                replaced = true
                fleet.service.join(player)
            }
        }
        fleet.establish(verifyApplied = false)
        check(replaced && fleet.service.capabilities(0) == null)
        checkNotNull(fleet.service.capabilities(1))
        check(
            fleet.handles.values
                .take(fleet.topology.sessionsPerPeer)
                .all { it.status is RemoteSessionStatus.Closed },
        )
        check(
            fleet.handles.values
                .drop(fleet.topology.sessionsPerPeer)
                .all { it.status == RemoteSessionStatus.Open },
        )
    }

    private fun terminal(
        case: RemoteServiceGate.Case,
        fleet: RemoteServiceFleet,
        trace: RemoteServiceTrace,
    ) {
        var triggered = false
        fleet.listener = { player, event ->
            trace.event(player, event)
            if (event is RemoteLifecycleEvent.Opened && player == 0 && triggered.not()) {
                triggered = true
                when (case.trigger) {
                    RemoteServiceGate.Trigger.Disconnect -> fleet.service.disconnect(player)
                    RemoteServiceGate.Trigger.OwnerDisabled -> fleet.service.ownerDisabled(event.owner)
                    RemoteServiceGate.Trigger.ServiceClose -> fleet.service.close()
                    else -> error("Invalid terminal trigger")
                }
            }
        }
        fleet.establish(verifyApplied = false)
        check(triggered)
        check(
            fleet.handles.values
                .first()
                .status is RemoteSessionStatus.Closed,
        )
        if (case.trigger != RemoteServiceGate.Trigger.ServiceClose) checkNotNull(fleet.service.capabilities(1))
    }

    private fun nested(
        case: RemoteServiceGate.Case,
        fleet: RemoteServiceFleet,
        trace: RemoteServiceTrace,
    ) {
        fleet.establish()
        var depth = 1
        var maximum = 1
        var enabled = false
        fleet.reporter = { failure ->
            trace.failure(failure)
            if (depth < case.depth) {
                depth++
                maximum = maxOf(maximum, depth)
                try {
                    fleet.tick()
                } finally {
                    depth--
                }
            }
        }
        val original = fleet.handles.values.first()
        original.close()
        val callback =
            fleet.open(0, if (fleet.topology.huds == 0) UiPresentation.Screen else UiPresentation.Hud) {
                if (enabled) {
                    enabled = false
                    fleet.tick()
                }
            }
        enabled = true
        checkNotNull(fleet.sources[callback.identity]).let { it.publish(it.value + 1) }
        fleet.tick()
        fleet.deliver()
        check(maximum == case.depth)
        check(depth == 1)
    }

    private fun nestedRefresh(
        case: RemoteServiceGate.Case,
        fleet: RemoteServiceFleet,
        trace: RemoteServiceTrace,
    ) {
        fleet.establish()
        var invoked = false
        fleet.listener = { player, event ->
            trace.event(player, event)
            if (event is RemoteLifecycleEvent.Opened && invoked.not()) {
                invoked = true
                when (case.trigger) {
                    RemoteServiceGate.Trigger.OpenRefresh -> {
                        fleet.open(player, UiPresentation.Hud)
                    }

                    RemoteServiceGate.Trigger.ReceiveRefresh -> {
                        val peer = checkNotNull(fleet.peers[player])
                        peer.connection.send(RemoteMessage.Applied(event.identity, checkNotNull(peer.revisions[event.identity])))
                        fleet.flushIncoming()
                        fleet.tick()
                    }

                    else -> {
                        error("Invalid nested refresh trigger")
                    }
                }
            }
        }
        fleet.open(0, UiPresentation.Hud)
        fleet.tick()
        fleet.deliver()
        fleet.acknowledge()
        fleet.tick()
        fleet.deliver()
        check(invoked)
    }

    private fun stale(
        fleet: RemoteServiceFleet,
        trace: RemoteServiceTrace,
    ) {
        fleet.establish()
        fleet.handles.values
            .first()
            .close()
        var actions = 0
        val handle = fleet.openAction(0, UiPresentation.Hud) { actions++ }
        fleet.tick()
        fleet.deliver()
        fleet.acknowledge()
        fleet.tick()
        val peer = checkNotNull(fleet.peers[0])
        val press =
            checkNotNull(peer.trees[handle.identity])
                .nodes.values
                .flatMap { it.modifiers }
                .single { it.type == BuiltinProjection.PointerPress.type }
        val endpoint = ((press.value as ProjectionValue.Sequence).values[1] as ProjectionValue.Integer).value
        val action = RemoteMessage.Action(handle.identity, 1, endpoint, press.type, ProjectionInputCodec.pointer(PointerEvent.Press(IntOffset.Zero, PointerButton.Primary), IntOffset.Zero))
        peer.connection.send(action)
        peer.connection.send(action)
        peer.connection.flush()
        val oldPackets = peer.inbound.map(ByteArray::copyOf)
        fleet.flushIncoming()
        fleet.tick()
        oldPackets.forEach { fleet.enqueue(0, it) }
        fleet.tick()
        check(actions == 1)
        val oldAddress = peer.address
        fleet.join(0)
        fleet.tick()
        fleet.deliver()
        fleet.flushIncoming()
        fleet.tick()
        check(checkNotNull(fleet.peers[0]).address != oldAddress)
        oldPackets.forEach { fleet.enqueue(0, it) }
        fleet.tick()
        check(actions == 1)
        check(fleet.service.capabilities(0) != null && fleet.service.capabilities(1) != null)
        peer.close()
        trace.prefix.addAll(listOf("legitimate action executed once", "duplicate and retired address rejected"))
    }

    private fun shrink(
        fleet: RemoteServiceFleet,
        trace: RemoteServiceTrace,
    ) {
        fleet.establish()
        val transport = RemoteServiceOwnershipAudit()
        repeat(1_000) { cycle ->
            fleet.handles.values.filter { it.status is RemoteSessionStatus.Closed }.map { it.identity }.forEach {
                check(checkNotNull(fleet.sources.remove(it)).subscriptions == 0)
                fleet.handles.remove(it)
            }
            val original = fleet.handles.values.first()
            original.close()
            fleet.open(0, UiPresentation.Hud)
            fleet.tick()
            fleet.deliver()
            transport.verifyTransportDrained(fleet)
            check(fleet.subscriptions == 17)
            fleet.handles.values
                .toList()
                .forEach { it.close() }
            repeat(fleet.topology.deliveryTicks) {
                fleet.tick()
                fleet.deliver()
            }
            transport.verifyTransportDrained(fleet)
            check(fleet.subscriptions == 0)
            fleet.open(0, UiPresentation.Hud)
            fleet.tick()
            fleet.deliver()
            transport.verifyTransportDrained(fleet)
            check(fleet.subscriptions == 1)
            repeat(15) { fleet.open(0, UiPresentation.Hud) }
            fleet.open(0, UiPresentation.Screen)
            repeat(RemoteServiceShape(1, 15, 1).deliveryTicks) {
                fleet.tick()
                fleet.deliver()
            }
            transport.verifyTransportDrained(fleet)
            check(fleet.subscriptions == 17)
            fleet.discardMessages()
            trace.prefix += cycle
        }
        check(fleet.peers.size == 1)
    }

    private fun migrate(
        case: RemoteServiceGate.Case,
        owner: RuntimeExecutionOwner,
        fleet: RemoteServiceFleet,
        trace: RemoteServiceTrace,
    ) {
        owner.run { fleet.establish() }
        val releaseFailure = if (case.trigger == RemoteServiceGate.Trigger.ReleaseFailure) IllegalStateException("retired owner subscription release") else null
        val retired = owner.run { retiredOwner(fleet, releaseFailure, trace) }
        val independent = RuntimeExecutionOwner()
        independent.run { check(runCatching(fleet.service::tick).exceptionOrNull() is IllegalStateException) }
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor
                .submit {
                    check(runCatching(fleet.service::tick).exceptionOrNull() is IllegalStateException)
                    owner.run {
                        fleet.idle()
                        if (fleet.sources.isNotEmpty()) fleet.oneSource()
                        fleet.close()
                        fleet.verifyClosed()
                    }
                }.get(30, TimeUnit.SECONDS)
            owner.run {
                fleet.verifyClosed()
                repeat(40) { if (retired.get() != null) System.gc() }
                check(retired.get() == null) { "A retired plugin owner remains reachable while its closed public handle is retained" }
            }
        } finally {
            executor.shutdownNow()
            check(executor.awaitTermination(30, TimeUnit.SECONDS))
        }
        trace.prefix += "same logical owner migrated; foreign owners rejected; closed handles retained"
    }

    private fun retiredOwner(
        fleet: RemoteServiceFleet,
        releaseFailure: Throwable?,
        trace: RemoteServiceTrace,
    ): WeakReference<Any> {
        val owner = Any()
        if (fleet.topology.huds == 16) {
            fleet.handles.values
                .first()
                .close()
        }
        val handle = fleet.open(0, UiPresentation.Hud, owner = owner, released = { releaseFailure?.let { throw it } })
        check(handle.status == RemoteSessionStatus.Open)
        val failure = runCatching(handle::close).exceptionOrNull()
        check(failure === releaseFailure)
        failure?.let(trace::failure)
        return WeakReference(owner)
    }

    /**
     * Exercises the same terminal predicate with bounded probes before the unchanged real-protocol registry.
     * Negative controls require the exact rejection reason so unrelated reflection or lifecycle failures cannot pass.
     */
    private fun verifyTerminalOracleControls() {
        val empty =
            object {
                val peers = emptyMap<Int, Any>()
                val transitions = ArrayDeque<() -> Unit>()
            }
        check(empty.peers.isEmpty() && empty.transitions.isEmpty())
        inspectTerminalFields(empty)
        empty.transitions.addLast({})
        check(empty.transitions.size == 1)
        expectTerminalRejection("Terminal dispatch queue retains callbacks.") { inspectTerminalFields(empty) }

        val wrongQueue =
            object {
                val peers = emptyMap<Int, Any>()
                val transitions = mutableListOf<Any>()
            }
        check(wrongQueue.peers.isEmpty() && wrongQueue.transitions.isEmpty())
        expectTerminalRejection("Terminal dispatch queue type changed.") { inspectTerminalFields(wrongQueue) }

        val list =
            object {
                val peers = emptyMap<Int, Any>()
                val transitions = ArrayDeque<() -> Unit>()
                val snapshot = emptyList<Any>()
            }
        check(list.peers.isEmpty() && list.transitions.isEmpty() && list.snapshot.isEmpty())
        expectTerminalRejection("Terminal service retains membership storage.") { inspectTerminalFields(list) }

        val holder = TerminalHolderControl()
        check(holder.peers.isEmpty() && holder.transitions.isEmpty())
        check(holder.retained.owner === holder.owner)
        expectTerminalRejection("Terminal service retains membership storage.") { inspectTerminalFields(holder) }
        verifyRetainedOwnerControl()
        println(Gson().toJson(mapOf("terminalOracleControls" to "Passed", "positiveControls" to 1, "negativeControls" to 5)))
    }

    private fun verifyRetainedOwnerControl() {
        val owner = RuntimeExecutionOwner()
        val pluginOwner = Any()
        val ownership = RemoteServiceOwnershipAudit()
        val fleet = owner.run { RemoteServiceFleet(RemoteServiceShape(1, 0, 0)) }
        try {
            owner.run {
                fleet.establish()
                fleet.open(0, UiPresentation.Hud, owner = pluginOwner)
                check(fleet.handles.size == 1)
                ownership.capture(fleet)
                check(ownership.observedCount == 3)
                fleet.close()
                fleet.verifyClosed()
                inspectTerminalFields(fleet.service)
            }
            expectTerminalRejection("A retired production Peer, Active or plugin owner remains reachable") {
                ownership.verifyReleased()
            }
        } finally {
            try {
                owner.run(fleet::close)
            } finally {
                // Keep the actual captured plugin owner alive throughout the unchanged weak-release audit.
                Reference.reachabilityFence(pluginOwner)
            }
        }
    }

    private fun expectTerminalRejection(
        reason: String,
        action: () -> Unit,
    ) {
        val failure = runCatching(action).exceptionOrNull()
        check(failure is IllegalStateException && failure.message == reason) { "Terminal control did not reject for the expected reason: $reason" }
    }

    /**
     * Rejects membership retention while permitting only the existing exact, empty dispatch deque.
     * Reflection reads production fields without injecting, replacing or draining their contents.
     */
    private fun inspectTerminalFields(service: Any) {
        val field = service.javaClass.getDeclaredField("peers")
        field.isAccessible = true
        check((field.get(service) as Map<*, *>).isEmpty()) { "Terminal service retains peers." }
        // Kotlin's dispatch deque implements List, so inspect its terminal contents separately from snapshot fields.
        val transitions = service.javaClass.getDeclaredField("transitions")
        check(transitions.type == ArrayDeque::class.java) { "Terminal dispatch queue type changed." }
        transitions.isAccessible = true
        check((transitions.get(service) as ArrayDeque<*>).isEmpty()) { "Terminal dispatch queue retains callbacks." }
        val nested =
            service.javaClass.declaredClasses
                .toSet()
        check(
            service.javaClass.declaredFields
                .none { it.type in nested || (it != transitions && List::class.java.isAssignableFrom(it.type)) },
        ) { "Terminal service retains membership storage." }
    }

    /**
     * A deliberate nested owner holder that the production structural predicate must reject.
     */
    private class TerminalHolderControl {
        val peers = emptyMap<Int, Any>()
        val transitions = ArrayDeque<() -> Unit>()
        val owner = Any()
        val retained = Holder(owner)

        /**
         * Retains one deliberate strong owner for the structural rejection control.
         */
        class Holder(
            val owner: Any,
        )
    }
}
