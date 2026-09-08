package dev.s7a.strata.runtime

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.map
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * State scheduling evidence obtained through the same monitoring capability exposed by native screens.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class ObservedMonitoringTest {
    @Test
    fun oneHundredStableFramesAndEqualProjectionDoNoDependentWork() {
        val source = ObserveTestSource(1)
        val parity = source.map { it % 2 }
        var evaluations = 0
        UiSession(TestOwnerDispatcher()) {
            evaluateComponentTree {
                Observe(parity) {
                    evaluations += 1
                    Spacer()
                }
            }
        }.use { session ->
            session.attach()
            val stable = session.frame(Constraints())
            session.startRenderMonitoring().use { monitor ->
                repeat(100) { assertSame(stable, session.frame(Constraints())) }
                val idle = monitor.snapshot()
                assertEquals(100L, idle.counts[UiRenderMetric.FrameCacheHit])
                assertEquals(100L, idle.counts[UiRenderMetric.FrameSuccess])
                assertEquals(0L, idle.counts[UiRenderMetric.ContentEvaluation])
                assertEquals(1, idle.activeSubscriptions)
                monitor.checkpoint()
                source.publish(1)
                assertSame(stable, session.frame(Constraints()))
                assertEquals(0L, monitor.snapshot().counts[UiRenderMetric.Projection])
                monitor.checkpoint()
                source.publish(3)
                assertSame(stable, session.frame(Constraints()))
                val equal = monitor.snapshot()
                assertEquals(1L, equal.counts[UiRenderMetric.Projection])
                assertEquals(1L, equal.counts[UiRenderMetric.ProjectionEqual])
                listOf(
                    UiRenderMetric.ConsumerNotification,
                    UiRenderMetric.ContentEvaluation,
                    UiRenderMetric.NodeUpdate,
                    UiRenderMetric.Measure,
                    UiRenderMetric.Layout,
                    UiRenderMetric.Paint,
                    UiRenderMetric.Semantics,
                ).forEach {
                    assertEquals(0L, equal.counts[it], it.name)
                }
                assertEquals(1, evaluations)
                monitor.checkpoint()
                source.publish(4)
                session.frame(Constraints())
                assertEquals(1L, monitor.snapshot().counts[UiRenderMetric.ObserveEvaluation])
                assertEquals(2, evaluations)
            }
        }
    }

    @Test
    fun onlyOneOf128IndependentOwnersReceivesValuesAndReevaluates() {
        val sources = List(128) { ObserveTestSource(0) }
        val evaluations = IntArray(128)
        UiSession(TestOwnerDispatcher()) {
            evaluateComponentTree {
                Column {
                    sources.forEachIndexed { index, source ->
                        Observe(source, key = ElementKey(index)) {
                            evaluations[index] += 1
                            Spacer()
                        }
                    }
                }
            }
        }.use { session ->
            session.attach()
            session.frame(Constraints())
            session.startRenderMonitoring().use { monitor ->
                sources[42].publish(1)
                session.frame(Constraints())
                val snapshot = monitor.snapshot()
                assertEquals(1L, snapshot.counts[UiRenderMetric.ConsumerNotification])
                assertEquals(1L, snapshot.counts[UiRenderMetric.ContentEvaluation])
                assertEquals(List(128) { if (it == 42) 2 else 1 }, evaluations.toList())
                assertEquals(128, snapshot.activeSubscriptions)
            }
        }
        sources.forEach { assertEquals(1, it.releases) }
    }

    @Test
    fun nestedPendingRemovalCancelsChildEvaluationAndReleasesSubscriptions() {
        val visible = ObserveTestSource(true)
        val child = ObserveTestSource(0)
        UiSession(TestOwnerDispatcher()) {
            evaluateComponentTree { Observe(visible) { if (it) Observe(child) { Spacer() } } }
        }.use { session ->
            session.attach()
            session.frame(Constraints())
            session.startRenderMonitoring().use { monitor ->
                visible.publish(false)
                child.publish(1)
                session.frame(Constraints())
                val snapshot = monitor.snapshot()
                assertEquals(1L, snapshot.counts[UiRenderMetric.ObserveEvaluation])
                assertEquals(1, snapshot.activeSubscriptions)
                assertEquals(1L, snapshot.counts[UiRenderMetric.SubscriptionClose])
            }
        }
    }

    @Test
    fun mapperFailureClosesMonitorAndAllSubscriptions() {
        val source = ObserveTestSource(0)
        val mapped =
            source.map {
                check(it == 0)
                it
            }
        UiSession(TestOwnerDispatcher()) {
            evaluateComponentTree { Observe(mapped) { Spacer() } }
        }.use { session ->
            session.attach()
            session.frame(Constraints())
            val monitor = session.startRenderMonitoring()
            source.publish(1)
            assertThrows(IllegalStateException::class.java) { session.frame(Constraints()) }
            assertEquals(1, source.releases)
            assertEquals(1L, monitor.snapshot().counts[UiRenderMetric.FrameFailure])
            assertEquals(0, monitor.snapshot().activeSubscriptions)
            monitor.close()
            assertThrows(IllegalStateException::class.java) { monitor.snapshot() }
        }
    }
}
