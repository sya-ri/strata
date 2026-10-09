@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiCloseReason
import dev.s7a.strata.ui.UiPresentation
import dev.s7a.strata.ui.UiSessionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Public negotiated callbacks prove snapshot phase timing, nested refresh isolation and exception identity.
 */
internal class RemoteTickMembershipTest {
    @Test
    fun readyMutationUsesTheCurrentMembershipAfterTheOriginalEmptyTickCapture() {
        listOf(1, 4, 17).forEach { count ->
            RemoteTickTestHost().use { host ->
                host.listener = { event ->
                    if (event is RemoteLifecycleEvent.Ready) {
                        repeat(minOf(count, 16)) { host.open() }
                        if (count == 17) host.open(UiPresentation.Screen)
                    }
                }
                host.negotiate()
                assertEquals(count, host.handles.size)
                assertTrue(host.handles.all { it.status == RemoteSessionStatus.Open })
                host.acknowledge()
                assertEquals(host.handles.map { it.identity }, host.events.filterIsInstance<RemoteLifecycleEvent.Opened<*>>().map { it.identity })
                assertTrue(host.handles.all { it.uiSession.status is UiSessionStatus.Ready })
                assertTrue(host.failures.isEmpty())
            }
        }
    }

    @Test
    fun openedMutationClosesTheReportedOwnerOnceAndItsReplacementRefreshIsIndependent() {
        listOf(1, 4).forEach { count ->
            RemoteTickTestHost().use { host ->
                host.negotiate()
                repeat(count) { host.open() }
                val original = host.handles.first()
                var replacement: RemoteScreenSession? = null
                host.listener = { event ->
                    if (event is RemoteLifecycleEvent.Opened && event.identity == original.identity) {
                        event.session.close()
                        replacement = host.open()
                    }
                }
                host.acknowledge()
                assertTrue(original.uiSession.status is UiSessionStatus.Closed)
                assertEquals(1, host.events.filterIsInstance<RemoteLifecycleEvent.Closed<*>>().count { it.identity == original.identity })
                assertEquals(RemoteSessionStatus.Open, checkNotNull(replacement).status)
                host.acknowledge()
                assertTrue(checkNotNull(replacement).uiSession.status is UiSessionStatus.Ready)
                assertTrue(host.failures.isEmpty())
            }
        }
    }

    @Test
    fun repeatedNestedTicksDuringReadyPreserveTheExistingDispatchPermissionAndOuterMembership() {
        listOf(2, 4).forEach { depth ->
            RemoteTickTestHost().use { host ->
                var nested = 0
                host.listener = { event ->
                    if (event is RemoteLifecycleEvent.Ready) {
                        repeat(depth - 1) {
                            host.service.tick()
                            nested++
                        }
                        host.open()
                    }
                }
                host.negotiate()
                assertEquals(depth - 1, nested)
                assertEquals(1, host.events.filterIsInstance<RemoteLifecycleEvent.Ready>().size)
                assertEquals(RemoteSessionStatus.Open, host.handles.single().status)
                assertTrue(host.failures.isEmpty())
            }
        }
    }

    @Test
    fun terminalCallbackMutationKeepsClosedThenReplacementEncounterOrder() {
        RemoteTickTestHost().use { host ->
            host.negotiate()
            val original = host.open()
            host.acknowledge()
            var replacement: RemoteScreenSession? = null
            host.listener = { event ->
                if (event is RemoteLifecycleEvent.Closed && replacement == null) replacement = host.open()
            }
            original.close()
            assertEquals(
                UiCloseReason.Closed,
                host.events
                    .filterIsInstance<RemoteLifecycleEvent.Closed<*>>()
                    .single()
                    .reason,
            )
            assertEquals(RemoteSessionStatus.Open, checkNotNull(replacement).status)
            host.acknowledge()
            assertEquals(listOf(original.identity, checkNotNull(replacement).identity), host.events.filterIsInstance<RemoteLifecycleEvent.Opened<*>>().map { it.identity })
            assertTrue(host.failures.isEmpty())
        }
    }

    @Test
    fun notifyFailurePreservesItsOriginalThrowableOnTickUnwind() {
        RemoteTickTestHost().use { host ->
            val failure = IllegalArgumentException("Ready callback")
            host.listener = { event -> if (event is RemoteLifecycleEvent.Ready) throw failure }
            host.negotiate()
            assertSame(failure, host.failures.single())
            host.listener = {}
            val handle = host.open()
            host.acknowledge()
            assertTrue(handle.uiSession.status is UiSessionStatus.Ready)
        }
    }
}
