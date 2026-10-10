@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.component.VirtualList
import dev.s7a.strata.component.VirtualListState
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.runtime.spi.RuntimeUiSession
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Checks moving rows against independent geometry and work references through the portable retained pipeline.
 */
internal class PortableVirtualRowReuseTest {
    @Test
    fun movingWindowPreservesGeometryPaintInputSemanticsAndRetainedIdentity() {
        Fixture().use { fixture ->
            val original = fixture.frame(1_000.0)
            val oldCommands = original.drawCommands.toList()
            val oldSemantics = original.semantics.toList()
            val retained = fixture.probe.nodeForTag(fixture.items[110])
            fixture.constructed.clear()
            val monitor = fixture.session.startRenderMonitoring()
            try {
                val shifted = fixture.frame(1_010.0)
                fixture.verifyFrame(shifted, 1_010)
                assertEquals(listOf(fixture.items[139]), fixture.constructed)
                assertEquals(1L, monitor.snapshot().counts[UiRenderMetric.RowEvaluation])
                assertSame(retained, fixture.probe.nodeForTag(fixture.items[110]))
                // New rows change the retained revision during measurement; the following frame establishes its clean snapshot.
                val settled = fixture.session.frame(fixture.constraints)
                assertEquals(shifted.drawCommands, settled.drawCommands)
                assertEquals(shifted.semantics, settled.semantics)
                assertSame(settled, fixture.session.frame(fixture.constraints))
                assertEquals(InputResult.Consumed, fixture.session.dispatchPointer(PointerEvent.Press(IntOffset.Zero, PointerButton.Primary)))
                assertEquals(fixture.items[101], fixture.probe.inputEvents.last())
                fixture.session.dispatchPointer(PointerEvent.Release(IntOffset.Zero, PointerButton.Primary))
                assertEquals(oldCommands, original.drawCommands)
                assertEquals(oldSemantics, original.semantics)
            } finally {
                monitor.close()
            }
            fixture.session.detach()
            fixture.session.attach()
            fixture.verifyFrame(fixture.session.frame(fixture.constraints), 1_010)
            assertSame(retained, fixture.probe.nodeForTag(fixture.items[110]))
        }
    }

    @Test
    fun fractionalScrollAndFastJumpsRetainBoundedOverscanAndInputCoordinates() {
        Fixture().use { fixture ->
            for (offset in listOf(1_000.0, 1_000.25, 1_001.0, 7_000.0, 1_010.0, 0.0, 9_620.0)) {
                val frame = fixture.frame(offset)
                val actual = fixture.state.scrollState.metrics.offset
                fixture.verifyFrame(frame, actual.toInt())
                val hitY = if (actual.toInt() % 10 == 0) 0 else 10 - actual.toInt() % 10
                assertEquals(InputResult.Consumed, fixture.session.dispatchPointer(PointerEvent.Move(IntOffset(0, hitY))))
                val hitIndex = (actual.toInt() + hitY) / 10
                assertEquals(fixture.items[hitIndex], fixture.probe.inputEvents.last())
                assertEquals(IntOffset.Zero, fixture.probe.inputObservations.last().localPosition)
                assertTrue(frame.semantics.size <= 41)
            }
        }
    }

    @Test
    fun refreshPreservesStableAnchorAcrossInsertRemoveReorderAndSameCountReplacement() {
        Fixture().use { fixture ->
            fixture.frame(1_001.0)
            val anchor = fixture.items[100]
            fixture.items.add(0, TestProbe.ProbeId("prepended"))
            fixture.state.refresh()
            fixture.constructed.clear()
            fixture.verifyFrame(fixture.session.frame(fixture.constraints), 1_011)
            assertEquals(1_011.0, fixture.state.scrollState.metrics.offset)
            assertEquals(41, fixture.constructed.size)
            fixture.items.removeAt(0)
            fixture.state.refresh()
            fixture.verifyFrame(fixture.session.frame(fixture.constraints), 1_001)
            val adjacent = fixture.items[99]
            fixture.items[99] = anchor
            fixture.items[100] = adjacent
            fixture.state.refresh()
            fixture.verifyFrame(fixture.session.frame(fixture.constraints), 991)
            assertEquals(991.0, fixture.state.scrollState.metrics.offset)
            fixture.items[110] = TestProbe.ProbeId("replacement")
            fixture.state.refresh()
            fixture.verifyFrame(fixture.session.frame(fixture.constraints), 991)
        }
    }

    /**
     * Stable immutable row inputs and portable lifecycle probes owned by one session.
     */
    private class Fixture : AutoCloseable {
        val probe = TestProbe()
        val items = MutableList(1_000) { TestProbe.ProbeId("row-$it") }
        val constructed = ArrayList<TestProbe.ProbeId>()
        val state = VirtualListState<TestProbe.ProbeId>()
        val constraints = Constraints.fixed(8, 380)
        val session: RuntimeUiSession =
            createRuntimeUiSession {
                evaluateComponentTree {
                    VirtualList(
                        itemCount = { items.size },
                        itemAt = items::get,
                        keyAt = items::get,
                        state = state,
                        viewportSize = IntSize(8, 380),
                        rowHeight = 10,
                    ) { item ->
                        constructed.add(item)
                        element(probe.element(item, key = item))
                    }
                }
            }

        init {
            session.attach()
            session.frame(constraints)
        }

        /**
         * Applies one owner-thread scroll offset and commits the resulting frame.
         */
        fun frame(offset: Double): RuntimeUiFrame {
            state.scrollState.scrollTo(offset)
            return session.frame(constraints)
        }

        /**
         * Calculates the fixed-row reference without inspecting the optimized declaration cache.
         */
        fun verifyFrame(
            frame: RuntimeUiFrame,
            offset: Int,
        ) {
            val first = offset / 10
            val start = maxOf(0, first - 1)
            val end = minOf(items.size, (state.scrollState.metrics.offset + 379.0).toInt() / 10 + 2)
            val indices = start until end
            val bounds = indices.map { index -> IntRect(0, index * 10 - offset, 2, index * 10 - offset + 1) }
            assertEquals(IntSize(8, 380), frame.size)
            assertEquals(bounds, frame.semantics.map { it.bounds })
            assertEquals(indices.map { UiText.Literal(items[it].value) }, frame.semantics.map { it.semantics.label })
            assertEquals(bounds, frame.drawCommands.filterIsInstance<DrawCommand.FillRectangle>().map { it.bounds })
        }

        override fun close() {
            session.close()
            val disposed = probe.events.filterIsInstance<TestProbe.Event.Dispose>().size
            assertEquals(probe.created.size, disposed)
            session.close()
            assertEquals(disposed, probe.events.filterIsInstance<TestProbe.Event.Dispose>().size)
        }
    }
}
