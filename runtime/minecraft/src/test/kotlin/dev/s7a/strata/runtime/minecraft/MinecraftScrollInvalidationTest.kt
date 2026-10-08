package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.ScrollArea
import dev.s7a.strata.component.ScrollMetrics
import dev.s7a.strata.component.ScrollState
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.minecraft.MinecraftScrollInputFixture.Observer
import dev.s7a.strata.runtime.minecraft.MinecraftScrollInputFixture.Owner
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies actual profile input preserves state, ordered callbacks, active drag and independent pixels.
 * Keyed diagnostics distinguish origin-node callbacks from required linked-control or unrelated dirty work.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftScrollInvalidationTest {
    @Test
    fun outwardAndZeroWheelAtBothEndpointsAddNoOriginWorkAcrossOneHundredFrames() {
        for (bottom in listOf(false, true)) {
            MinecraftScrollInputFixture().use { fixture ->
                if (bottom) fixture.state.scrollTo(fixture.state.metrics.maximumOffset)
                val frame = fixture.frame()
                fixture.trace.clear()
                fixture.monitor.checkpoint()
                val metrics = fixture.state.metrics
                repeat(100) {
                    assertEquals(InputResult.Consumed, fixture.wheel(if (bottom) 1.0 else -1.0))
                    assertSame(frame, fixture.frame())
                    assertEquals(InputResult.Consumed, fixture.wheel(0.0))
                    assertSame(frame, fixture.frame())
                }
                assertEquals(metrics, fixture.state.metrics)
                assertTrue(fixture.trace.isEmpty())
                assertEquals(0L, fixture.count(Owner.Area, UiRenderMetric.Layout))
                assertEquals(0L, fixture.count(Owner.Area, UiRenderMetric.Paint))
                assertEquals(0L, fixture.count(Owner.FirstBar, UiRenderMetric.Paint))
                assertEquals(0L, fixture.count(Owner.SecondBar, UiRenderMetric.Paint))
                fixture.verifyPixels(frame)
            }
        }
    }

    @Test
    fun fractionalAndReverseMovementKeepsExactSnapshotsAndRequiredOriginAndLinkedPaint() {
        MinecraftScrollInputFixture(scrollRate = 1).use { fixture ->
            fixture.monitor.checkpoint()
            assertEquals(InputResult.Consumed, fixture.wheel(0.25))
            fixture.frame()
            val expected = ScrollMetrics(0.25, 50, 184)
            assertEquals(expected, fixture.state.metrics)
            assertEquals(listOf(Observer.First to expected, Observer.Last to expected), fixture.trace)
            assertEquals(1L, fixture.count(Owner.Area, UiRenderMetric.Layout))
            assertEquals(1L, fixture.count(Owner.Area, UiRenderMetric.Paint))
            assertEquals(1L, fixture.count(Owner.FirstBar, UiRenderMetric.Paint))
            assertEquals(1L, fixture.count(Owner.SecondBar, UiRenderMetric.Paint))
            fixture.verifyPixels()
            fixture.monitor.checkpoint()
            fixture.trace.clear()
            fixture.wheel(-0.25)
            fixture.frame()
            assertEquals(ScrollMetrics(0.0, 50, 184), fixture.state.metrics)
            assertEquals(1L, fixture.count(Owner.Area, UiRenderMetric.Layout))
            assertEquals(1L, fixture.count(Owner.Area, UiRenderMetric.Paint))
            fixture.verifyPixels()
        }
    }

    @Test
    fun signedZeroUsesTheExistingSnapshotEqualityWithoutIntegerRounding() {
        MinecraftScrollInputFixture(initialOffset = -0.0, scrollRate = 1).use { fixture ->
            assertEquals((-0.0).toBits(), fixture.state.metrics.offset.toBits())
            fixture.monitor.checkpoint()
            fixture.wheel(0.0)
            fixture.frame()
            assertEquals(0.0.toBits(), fixture.state.metrics.offset.toBits())
            assertEquals(2, fixture.trace.size)
            assertEquals(1L, fixture.count(Owner.Area, UiRenderMetric.Layout))
            assertEquals(1L, fixture.count(Owner.Area, UiRenderMetric.Paint))
            fixture.state.scrollTo(-0.0)
            fixture.frame()
            fixture.trace.clear()
            fixture.monitor.checkpoint()
            fixture.wheel(-0.0)
            fixture.frame()
            assertEquals((-0.0).toBits(), fixture.state.metrics.offset.toBits())
            assertTrue(fixture.trace.isEmpty())
            assertEquals(0L, fixture.count(Owner.Area, UiRenderMetric.Layout))
            assertEquals(0L, fixture.count(Owner.Area, UiRenderMetric.Paint))
        }
    }

    @Test
    fun activeScrollbarAllThreeDragBranchesPreserveNoOpAndChangedFeedback() {
        MinecraftScrollInputFixture().use { fixture ->
            assertEquals(InputResult.Consumed, fixture.press())
            fixture.frame()
            fixture.monitor.checkpoint()
            repeat(100) {
                assertEquals(InputResult.Consumed, fixture.drag(-10))
                fixture.frame()
            }
            assertEquals(0.0, fixture.state.metrics.offset)
            assertEquals(0L, fixture.count(Owner.FirstBar, UiRenderMetric.Paint))
            fixture.drag(60)
            fixture.frame()
            assertEquals(fixture.state.metrics.maximumOffset, fixture.state.metrics.offset)
            assertEquals(1L, fixture.count(Owner.FirstBar, UiRenderMetric.Paint))
            assertEquals(1L, fixture.count(Owner.SecondBar, UiRenderMetric.Paint))
            fixture.verifyPixels()
            fixture.monitor.checkpoint()
            repeat(100) {
                fixture.drag(60)
                fixture.frame()
            }
            fixture.drag(20, 0.0)
            fixture.frame()
            assertEquals(0L, fixture.count(Owner.FirstBar, UiRenderMetric.Paint))
            fixture.drag(20, -0.25)
            fixture.frame()
            val maximum = fixture.state.metrics.maximumOffset
            assertEquals(maximum - 0.25 * (maximum / 18.0), fixture.state.metrics.offset)
            assertEquals(1L, fixture.count(Owner.FirstBar, UiRenderMetric.Paint))
            assertEquals(InputResult.Consumed, fixture.release(10))
            assertEquals(InputResult.Ignored, fixture.drag(20))
            fixture.frame()
            fixture.verifyPixels()
        }
    }

    @Test
    fun externalGeometryAndUnrelatedPendingLeafPaintSurviveUnchangedWheel() {
        MinecraftScrollInputFixture().use { fixture ->
            fixture.monitor.checkpoint()
            fixture.color.value = ArgbColor(0xff102030.toInt())
            fixture.wheel(-1.0)
            fixture.frame()
            assertEquals(0L, fixture.count(Owner.Area, UiRenderMetric.Layout))
            assertEquals(0L, fixture.count(Owner.Area, UiRenderMetric.Paint))
            fixture.verifyPixels()
            fixture.extent.value = IntSize(80, 210)
            fixture.wheel(-1.0)
            fixture.frame()
            assertEquals(ScrollMetrics(0.0, 50, 214), fixture.state.metrics)
            fixture.verifyPixels()
            fixture.monitor.checkpoint()
            fixture.state.scrollTo(10.0)
            fixture.wheel(0.0)
            fixture.frame()
            assertEquals(1L, fixture.count(Owner.Area, UiRenderMetric.Layout))
            assertEquals(1L, fixture.count(Owner.Area, UiRenderMetric.Paint))
            assertEquals(1L, fixture.count(Owner.FirstBar, UiRenderMetric.Paint))
            fixture.verifyPixels()
        }
    }

    @Test
    fun coalescedRequestsRetainEveryStateCallbackAndOnlyTheFinalFrameOpportunity() {
        MinecraftScrollInputFixture().use { fixture ->
            fixture.monitor.checkpoint()
            repeat(4) { fixture.wheel(1.0) }
            fixture.frame()
            assertEquals(ScrollMetrics(36.0, 50, 184), fixture.state.metrics)
            assertEquals((1..4).flatMap { index -> listOf(Observer.First to ScrollMetrics(index * 9.0, 50, 184), Observer.Last to ScrollMetrics(index * 9.0, 50, 184)) }, fixture.trace)
            assertEquals(4L, fixture.count(Owner.Area, UiRenderMetric.Layout))
            assertEquals(1L, fixture.count(Owner.Area, UiRenderMetric.Paint))
            fixture.verifyPixels()
        }
    }

    @Test
    fun finiteWheelWhoseRateMultiplicationOverflowsStillConsumesWithoutStateOrWork() {
        MinecraftScrollInputFixture().use { fixture ->
            val previous = fixture.frame()
            fixture.monitor.checkpoint()
            assertEquals(InputResult.Consumed, fixture.wheel(Double.MAX_VALUE))
            assertSame(previous, fixture.frame())
            assertTrue(fixture.trace.isEmpty())
            assertEquals(0L, fixture.count(Owner.Area, UiRenderMetric.Layout))
            assertEquals(0L, fixture.count(Owner.Area, UiRenderMetric.Paint))
        }
    }

    @Test
    fun observerReentryPreservesFinalAuthoritativeStateAndFailureIdentity() {
        MinecraftScrollInputFixture().use { fixture ->
            var redirected = false
            fixture.state.observe { metrics ->
                if (redirected.not() && metrics.offset == 9.0) {
                    redirected = true
                    fixture.state.scrollTo(18.0)
                }
            }.use {
                fixture.wheel(1.0)
                fixture.frame()
                assertEquals(ScrollMetrics(18.0, 50, 184), fixture.state.metrics)
                fixture.verifyPixels()
            }
            val failure = IllegalArgumentException("Rejected scroll observer")
            fixture.state.observe { throw failure }.use {
                assertSame(failure, assertThrows(IllegalArgumentException::class.java) { fixture.wheel(1.0) })
                assertEquals(27.0, fixture.state.metrics.offset)
            }
        }
    }

    @Test
    fun standaloneBarPreservesOutsideHitTestingSecondaryReleaseResetAndDetach() {
        MinecraftScrollInputFixture().use { fixture ->
            assertFalse(fixture.capturesPointer)
            fixture.press()
            fixture.frame()
            fixture.monitor.checkpoint()
            assertEquals(InputResult.Ignored, fixture.host.dispatchPointer(PointerEvent.Drag(IntOffset(106, -10), PointerButton.Primary, 0.0, -1.0)))
            assertEquals(InputResult.Ignored, fixture.host.dispatchPointer(PointerEvent.Release(IntOffset(106, 60), PointerButton.Primary)))
            assertEquals(InputResult.Ignored, fixture.host.dispatchPointer(PointerEvent.Release(IntOffset(106, 10), PointerButton.Secondary)))
            assertEquals(0.0, fixture.state.metrics.offset)
            fixture.frame()
            assertEquals(0L, fixture.count(Owner.FirstBar, UiRenderMetric.Paint))
            fixture.host.resetInputState()
            assertEquals(InputResult.Consumed, fixture.drag(20, 0.25))
            fixture.frame()
            fixture.verifyPixels()
            fixture.host.detach()
            fixture.host.attach()
            fixture.frame()
            assertEquals(InputResult.Ignored, fixture.drag(20, 0.25))
            fixture.verifyPixels()
        }
    }

    @Test
    fun nestedAreaConsumesOutwardAndFractionalWheelWithoutChangingItsParentPosition() {
        val outer = ScrollState()
        val inner = ScrollState()
        val key = ElementKey(Owner.Area)
        val host =
            createMinecraftUiHost(
                UiDefinition("Nested scroll invalidation") {
                    ScrollArea(outer, Modifier.Empty.size(100, 50), scrollRate = 1) {
                        Column {
                            ScrollArea(inner, Modifier.Empty.size(80, 50), key, scrollRate = 1) {
                                Spacer(modifier = Modifier.Empty.size(60, 180).background(ArgbColor(0xffabcdef.toInt())))
                            }
                            Spacer(modifier = Modifier.Empty.size(80, 130))
                        }
                    }
                },
                MinecraftProfileFixture.create(),
            )
        host.use {
            host.attach()
            val initial = host.frame(IntSize(100, 50))
            assertEquals(ScrollMetrics(0.0, 50, 184), outer.metrics)
            assertEquals(ScrollMetrics(0.0, 50, 184), inner.metrics)
            assertEquals(
                IntRect(20, 4, 80, 184),
                initial.drawCommands
                    .filterIsInstance<DrawCommand.FillRectangle>()
                    .single()
                    .bounds,
            )
            host.startRenderMonitoring(32).use { monitor ->
                repeat(100) {
                    assertEquals(InputResult.Consumed, host.dispatchPointer(PointerEvent.Scroll(IntOffset(20, 20), 0.0, -1.0)))
                    assertSame(initial, host.frame(IntSize(100, 50)))
                }
                val id = monitor.findNodes(key).single()
                val counts = monitor.snapshot().nodes.single { node -> node.id == id }.counts
                assertEquals(0L, counts[UiRenderMetric.Layout] ?: 0L)
                assertEquals(0L, counts[UiRenderMetric.Paint] ?: 0L)
                assertEquals(InputResult.Consumed, host.dispatchPointer(PointerEvent.Scroll(IntOffset(20, 20), 0.0, 0.25)))
                val moved = host.frame(IntSize(100, 50))
                assertEquals(ScrollMetrics(0.25, 50, 184), inner.metrics)
                assertEquals(ScrollMetrics(0.0, 50, 184), outer.metrics)
                assertEquals(initial.drawCommands, moved.drawCommands)
            }
        }
    }
}
