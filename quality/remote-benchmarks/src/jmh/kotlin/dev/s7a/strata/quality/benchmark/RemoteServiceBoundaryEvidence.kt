@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.Spacer
import dev.s7a.strata.runtime.remote.RemoteFailure
import dev.s7a.strata.runtime.remote.RemoteLifecycleEvent
import dev.s7a.strata.runtime.remote.RemoteLimits
import dev.s7a.strata.runtime.remote.RemoteProtocolException
import dev.s7a.strata.runtime.remote.RemoteSessionStatus
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiDefinition
import dev.s7a.strata.ui.UiPresentation

/**
 * Independent real-route failure injection, resource admission and transition-budget gates.
 * Each callback still injects its original failure at the same protocol/lifecycle boundary.
 */
internal object RemoteServiceBoundaryEvidence {
    /**
     * Dispatches only the four boundary gate categories assigned by the complete registry.
     */
    fun execute(
        case: RemoteServiceGate.Case,
        fleet: RemoteServiceFleet,
        trace: RemoteServiceTrace,
    ) {
        when (case.gate) {
            RemoteServiceGate.CallbackReportSendFailure -> failure(case, fleet, trace)
            RemoteServiceGate.SessionCloseFailure -> closeFailure(fleet, trace)
            RemoteServiceGate.AdmissionBudgets -> admission(case, fleet, trace)
            RemoteServiceGate.TransitionBudget -> transitions(fleet, trace)
            else -> error("Invalid boundary gate")
        }
    }

    private fun failure(
        case: RemoteServiceGate.Case,
        fleet: RemoteServiceFleet,
        trace: RemoteServiceTrace,
    ) {
        fleet.establish()
        val primary = IllegalArgumentException("gate primary failure")
        val reporting = IllegalStateException("gate reporting failure")
        var injected = false
        when (case.trigger) {
            RemoteServiceGate.Trigger.NotifyFailure -> {
                fleet.listener = { player, event ->
                    trace.event(player, event)
                    if (event is RemoteLifecycleEvent.Closed && injected.not()) {
                        injected = true
                        throw primary
                    }
                }
                fleet.handles.values
                    .first()
                    .close()
                check(trace.throwables.any { it === primary })
            }

            RemoteServiceGate.Trigger.ReportFailure -> {
                injected = reportFailure(fleet, trace, primary, reporting)
            }

            RemoteServiceGate.Trigger.SendFailure -> {
                fleet.sender = { player, bytes ->
                    if (player == 0 && injected.not()) {
                        injected = true
                        throw primary
                    }
                    checkNotNull(fleet.peers[player]).outbound.addLast(bytes)
                }
                fleet.allSources()
                check(trace.throwables.any { it === primary })
                checkNotNull(fleet.service.capabilities(1))
            }

            else -> {
                error("Invalid failure trigger")
            }
        }
        check(injected)
    }

    private fun closeFailure(
        fleet: RemoteServiceFleet,
        trace: RemoteServiceTrace,
    ) {
        fleet.establish()
        val primary = IllegalArgumentException("first subscription close")
        val secondary = IllegalStateException("second subscription close")
        fleet.handles.values
            .take(2)
            .forEach { it.close() }
        var released = 0
        fleet.open(0, UiPresentation.Hud, released = {
            released++
            throw primary
        })
        fleet.open(0, UiPresentation.Hud, released = {
            released++
            throw secondary
        })
        fleet.service.disconnect(0)
        check(released == 2)
        check(trace.throwables.any { it === primary && it.suppressed.any { suppressed -> suppressed === secondary } })
        checkNotNull(fleet.service.capabilities(1))
        check(fleet.subscriptions == fleet.topology.sessionsPerPeer)
    }

    private fun admission(
        case: RemoteServiceGate.Case,
        fleet: RemoteServiceFleet,
        trace: RemoteServiceTrace,
    ) {
        if (case.trigger == RemoteServiceGate.Trigger.HudBudget) {
            fleet.establish()
            val rejected = fleet.open(0, UiPresentation.Hud)
            check((rejected.status as RemoteSessionStatus.Closed).reason == RemoteFailure.ResourceLimit)
            check(fleet.subscriptions == 17)
        } else {
            check(case.trigger == RemoteServiceGate.Trigger.NodeBudget)
            fleet.join(0, RemoteLimits(treeNodes = 40))
            fleet.join(1)
            fleet.tick()
            fleet.deliver()
            fleet.flushIncoming()
            fleet.tick()
            fleet.openPeer(0)
            fleet.openPeer(1)
            repeat(fleet.topology.deliveryTicks) {
                fleet.tick()
                fleet.deliver()
                fleet.acknowledge()
            }
            fleet.tick()
            fleet.deliver()
            check(trace.throwables.any { (it as? RemoteProtocolException)?.reason == RemoteFailure.ResourceLimit })
            check(fleet.service.capabilities(0) == null && fleet.service.capabilities(1) != null)
            check(fleet.subscriptions == 17)
        }
    }

    private fun transitions(
        fleet: RemoteServiceFleet,
        trace: RemoteServiceTrace,
    ) {
        var notifications = 0
        fleet.listener = { player, event ->
            trace.event(player, event)
            if (event is RemoteLifecycleEvent.Closed) {
                notifications++
                fleet.service.open(Any(), player, UiDefinition { Spacer() })
            }
        }
        val handle = fleet.service.open(Any(), 0, UiDefinition { Spacer() })
        check(handle.status is RemoteSessionStatus.Closed)
        check(notifications == 65)
        check((trace.throwables.single() as RemoteProtocolException).reason == RemoteFailure.ResourceLimit)
        fleet.listener = { player, event -> trace.event(player, event) }
    }

    /**
     * Injects the reporting failure after a real content failure and verifies both original identities.
     */
    private fun reportFailure(
        fleet: RemoteServiceFleet,
        trace: RemoteServiceTrace,
        primary: Throwable,
        reporting: Throwable,
    ): Boolean {
        var injected = false
        fleet.reporter = { failure ->
            trace.failure(failure)
            injected = true
            throw reporting
        }
        var enabled = false
        fleet.handles.values
            .first()
            .close()
        val callback = fleet.open(0, UiPresentation.Hud) { if (enabled) throw primary }
        enabled = true
        checkNotNull(fleet.sources[callback.identity]).let { it.publish(it.value + 1) }
        val propagated = runCatching(fleet::tick).exceptionOrNull()
        check(propagated === reporting)
        check(trace.throwables.any { it === primary })
        fleet.reporter = { trace.failure(it) }
        return injected
    }
}
