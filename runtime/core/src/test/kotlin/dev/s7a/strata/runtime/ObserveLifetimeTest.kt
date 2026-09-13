package dev.s7a.strata.runtime

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.UiScope
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies bounded observation work and exact subscription cleanup across retained session transitions.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class ObserveLifetimeTest {
    @Test
    fun unchangedFramesDoNoComponentOrPrimitiveWorkAndOnlyTheChangedSiblingIsReevaluated() {
        val sources = List(128) { ObserveTestSource(0) }
        val evaluations = IntArray(sources.size)
        val probe = TestProbe()
        var roots = 0
        session {
            roots += 1
            Column {
                sources.forEachIndexed { index, source ->
                    Observe(source) { value ->
                        evaluations[index] += 1
                        element(probe.element(TestProbe.ProbeId("$index:$value")))
                    }
                }
            }
        }.use { session ->
            session.attach()
            session.frame(Constraints())
            val committed = session.frame(Constraints())
            val counts = listOf(probe.updateCalls, probe.measureCalls, probe.layoutCalls, probe.paintCalls, probe.semanticsCalls)
            repeat(100) { assertSame(committed, session.frame(Constraints())) }
            assertEquals(counts, listOf(probe.updateCalls, probe.measureCalls, probe.layoutCalls, probe.paintCalls, probe.semanticsCalls))
            sources[64].publish(1)
            session.frame(Constraints())
            assertEquals(List(128) { index -> if (index == 64) 2 else 1 }, evaluations.toList())
            assertEquals(1, roots)
            assertEquals(128, probe.created.size)
            assertEquals(1, probe.updateCalls)
        }
        assertTrue(sources.all { source -> source.subscriptions == 1 && source.releases == 1 })
    }

    @Test
    fun detachedSessionKeepsItsSourceAndCommitsOnlyTheNewestValueOnReattach() {
        val source = ObserveTestSource(0)
        val values = ArrayList<Int>()
        session {
            Observe(source) { value ->
                values.add(value)
                Spacer()
            }
        }.use { session ->
            session.attach()
            session.frame(Constraints())
            session.detach()
            source.publish(1)
            source.publish(2)
            assertEquals(listOf(0), values)
            assertEquals(0, source.releases)
            session.attach()
            session.frame(Constraints())
            assertEquals(listOf(0, 2), values)
            assertEquals(1, source.subscriptions)
        }
        assertEquals(1, source.releases)
    }

    @Test
    fun failedSubscriptionReleasesEarlierAcquisitionsAndPreservesTheOriginalFailure() {
        val acquired = ObserveTestSource(0)
        val failure = IllegalStateException("subscribe failure")
        val broken = StateSource<Int> { throw failure }
        session { Observe(acquired, broken) { _, _ -> Spacer() } }.use { session ->
            session.attach()
            assertSame(failure, assertThrows(IllegalStateException::class.java) { session.frame(Constraints()) })
            assertEquals(1, acquired.releases)
        }
        assertEquals(1, acquired.releases)
    }

    @Test
    fun allSourcesAreReleasedEvenWhenACloserThrowsAndLateCallbacksAreDisabled() {
        val good = ObserveTestSource(0)
        val cleanupFailure = IllegalStateException("close failure")
        var releases = 0
        var callback: ((StateSnapshot<Int>) -> Unit)? = null
        val broken =
            StateSource<Int> { observer ->
                callback = observer
                StateSubscription(StateSnapshot(StateRevision(0), 0)) {
                    releases += 1
                    throw cleanupFailure
                }
            }
        val session = session { Observe(broken, good) { _, _ -> Spacer() } }
        session.attach()
        session.frame(Constraints())
        assertSame(cleanupFailure, assertThrows(IllegalStateException::class.java, session::close))
        checkNotNull(callback)(StateSnapshot(StateRevision(1), 1))
        session.close()
        assertEquals(1, releases)
        assertEquals(1, good.releases)
    }

    private fun session(content: UiScope.() -> Unit): UiSession = UiSession(TestOwnerDispatcher()) { evaluateComponentTree(content) }
}
