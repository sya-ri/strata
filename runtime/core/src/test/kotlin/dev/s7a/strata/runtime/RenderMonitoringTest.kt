package dev.s7a.strata.runtime

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.VirtualList
import dev.s7a.strata.component.VirtualListState
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.diagnostics.UiRenderOperation
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Independently counts real callbacks so missing diagnostic hooks cannot establish a false success.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class RenderMonitoringTest {
    @Test
    fun actualPipelineAndLifecycleMatchIndependentProbeCounts() {
        val probe = TestProbe()
        UiTree().use { tree ->
            assertNull(tree.monitoring.collector)
            tree.startRenderMonitoring().use { monitor ->
                tree.update(probe.root(listOf(probe.element(TestProbe.ProbeId("child")))))
                tree.measure(Constraints())
                tree.layout()
                tree.paint()
                tree.semantics()
                val first = monitor.snapshot()
                assertEquals(probe.created.size.toLong(), first.counts[UiRenderMetric.NodeCreate])
                assertEquals(probe.measureCalls.toLong(), first.counts[UiRenderMetric.Measure])
                assertEquals(probe.layoutCalls.toLong(), first.counts[UiRenderMetric.Layout])
                assertEquals(probe.paintCalls.toLong(), first.counts[UiRenderMetric.Paint])
                assertEquals(probe.semanticsCalls.toLong(), first.counts[UiRenderMetric.Semantics])
                assertFalse(first.overflowed)
                monitor.checkpoint()
                tree.update(probe.root(emptyList()))
                val changed = monitor.snapshot()
                assertEquals(probe.updateCalls.toLong(), changed.counts[UiRenderMetric.NodeUpdate])
                assertEquals(1L, changed.counts[UiRenderMetric.NodeDispose])
                assertEquals(1, changed.nodes.count { it.retired })
                assertEquals(0L, first.counts[UiRenderMetric.NodeDispose])
                assertThrows(UnsupportedOperationException::class.java) {
                    (first.counts as MutableMap)[UiRenderMetric.NodeCreate] = 999L
                }
                monitor.checkpoint()
                assertEquals(1, monitor.snapshot().nodes.size)
            }
            assertNull(tree.monitoring.collector)
        }
    }

    @Test
    fun identitiesDistinguishEqualKeysUnderDifferentParentsAndRemainStable() {
        UiTree().use { tree ->
            tree.update(
                evaluateComponentTree {
                    Column {
                        Column(key = ElementKey("left")) { Spacer(key = ElementKey("shared")) }
                        Column(key = ElementKey("right")) { Spacer(key = ElementKey("shared")) }
                    }
                },
            )
            tree.startRenderMonitoring().use { monitor ->
                val ids = monitor.findNodes(ElementKey("shared"))
                assertEquals(2, ids.size)
                val parents =
                    monitor
                        .snapshot()
                        .nodes
                        .filter { it.id in ids }
                        .map { it.parentId }
                assertEquals(2, parents.toSet().size)
                monitor.checkpoint()
                assertEquals(ids, monitor.findNodes(ElementKey("shared")))
                assertTrue(
                    monitor
                        .snapshot()
                        .counts.values
                        .all { it == 0L },
                )
            }
        }
    }

    @Test
    fun monitorRejectsForeignThreadReentryAndDuplicateStartAndReleasesOnClose() {
        UiTree().use { tree ->
            val monitor = tree.startRenderMonitoring()
            assertThrows(IllegalStateException::class.java) { tree.startRenderMonitoring() }
            val failure = AtomicReference<Throwable>()
            Thread { failure.set(runCatching { monitor.snapshot() }.exceptionOrNull()) }.apply {
                start()
                join()
            }
            assertTrue(failure.get() is IllegalStateException)
            val probe = TestProbe()
            tree.update(
                probe.element(TestProbe.ProbeId("root"), onMeasure = {
                    assertThrows(IllegalStateException::class.java) { monitor.checkpoint() }
                    assertThrows(IllegalStateException::class.java) { monitor.close() }
                }),
            )
            tree.measure(Constraints())
            monitor.close()
            monitor.close()
            assertThrows(IllegalStateException::class.java) { monitor.snapshot() }
            assertNull(tree.monitoring.collector)
            tree.startRenderMonitoring().use { assertEquals(1, it.snapshot().nodes.size) }
        }
    }

    @Test
    fun overflowIsBoundedAndExplicitEvenAcrossCheckpoint() {
        UiTree().use { tree ->
            tree.update(evaluateComponentTree { Column { repeat(4_100) { Spacer() } } })
            tree.startRenderMonitoring().use { monitor ->
                assertTrue(monitor.snapshot().overflowed)
                assertEquals(4_096, monitor.snapshot().nodes.size)
                monitor.checkpoint()
                assertTrue(monitor.snapshot().overflowed)
            }
        }
    }

    @Test
    fun nodeIdsSurviveMonitorRestartsAndAreNotReusedAfterRemoval() {
        UiTree().use { tree ->
            tree.update(evaluateComponentTree { Spacer(key = ElementKey("first")) })
            val original = tree.startRenderMonitoring().use { it.findNodes(ElementKey("first")).single() }
            tree.startRenderMonitoring().use { assertEquals(original, it.findNodes(ElementKey("first")).single()) }
            tree.update(evaluateComponentTree { Spacer(key = ElementKey("replacement")) })
            tree.startRenderMonitoring().use { monitor ->
                assertTrue(original.value < monitor.findNodes(ElementKey("replacement")).single().value)
            }
        }
    }

    @Test
    fun virtualizedRowsCountActualContentAndSeparatePreInputGeometry() {
        val state = VirtualListState<Int>()
        var rows = 0
        UiSession(TestOwnerDispatcher()) {
            evaluateComponentTree {
                VirtualList(
                    itemCount = 1_000,
                    itemAt = { it },
                    keyAt = { it },
                    state = state,
                    viewportSize = IntSize(80, 30),
                    rowHeight = 10,
                ) {
                    rows += 1
                    Spacer()
                }
            }
        }.use { session ->
            session.attach()
            session.startRenderMonitoring().use { monitor ->
                session.frame(Constraints.fixed(80, 30))
                assertEquals(rows.toLong(), monitor.snapshot().counts[UiRenderMetric.RowEvaluation])
                monitor.checkpoint()
                val before = rows
                session.dispatchPointer(PointerEvent.Scroll(IntOffset(1, 1), 0.0, 10.0))
                session.dispatchPointer(PointerEvent.Move(IntOffset(1, 1)))
                val snapshot = monitor.snapshot()
                assertTrue(0 < rows - before)
                assertEquals((rows - before).toLong(), snapshot.operations[UiRenderOperation.InputGeometry]?.get(UiRenderMetric.RowEvaluation))
                assertEquals(0L, snapshot.operations[UiRenderOperation.Frame]?.get(UiRenderMetric.RowEvaluation))
                monitor.checkpoint()
                session.frame(Constraints.fixed(80, 30))
                session.frame(Constraints.fixed(80, 30))
                assertEquals(0L, monitor.snapshot().counts[UiRenderMetric.RowEvaluation])
            }
        }
    }
}
