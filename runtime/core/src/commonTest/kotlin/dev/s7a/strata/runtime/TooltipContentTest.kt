@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.DoubleOffset
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.onMove
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.node.ChildTransform
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.spi.TooltipModifier
import dev.s7a.strata.state.mutableStateOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Exercises auxiliary-root timing, geometry, state and ownership on JVM and JavaScript.
 */
internal class TooltipContentTest {
    @Test
    fun delayedContentPaintsAfterTheScreenAndResetsAcrossDetach() {
        val probe = RootOverlayProbe(IntSize(10, 6))
        probe.paint = { it.fillRectangle(IntRect(0, 0, 10, 6), ArgbColor(7)) }
        val session =
            createRuntimeUiSession {
                evaluateComponentTree {
                    Stack(Modifier.size(40, 30).background(ArgbColor(1))) {
                        Spacer(Modifier.size(4, 4).then(TooltipModifier(probe.element(), 500)))
                    }
                }
            }
        session.attach()
        val constraints = Constraints.fixed(40, 30)
        session.frame(constraints, FrameTime(0))
        session.dispatchPointer(PointerEvent.Move(IntOffset(1, 1)))
        val waiting = session.frame(constraints, FrameTime(0))
        assertEquals(0, probe.paintCalls)
        assertSame(waiting, session.frame(constraints, FrameTime(499_999_999)))
        val visible = session.frame(constraints, FrameTime(500_000_000))
        assertEquals(listOf(ArgbColor(1), ArgbColor(7)), visible.drawCommands.filterIsInstance<DrawCommand.FillRectangle>().map { it.color })
        assertEquals(
            IntRect(0, 8, 10, 14),
            visible.drawCommands
                .filterIsInstance<DrawCommand.FillRectangle>()
                .last()
                .bounds,
        )
        assertSame(visible, session.frame(constraints, FrameTime(600_000_000)))
        session.detach()
        session.attach()
        assertEquals(waiting.drawCommands, session.frame(constraints, FrameTime(700_000_000)).drawCommands)
        assertEquals(0, probe.disposeCalls)
        session.close()
        assertEquals(1, probe.detachCalls)
        assertEquals(1, probe.disposeCalls)
    }

    @Test
    fun overlayRepositionsAtViewportEdgesAndNeverReceivesInput() {
        var overlayEvents = 0
        var anchorEvents = 0
        val overlay =
            evaluateComponentTree {
                Spacer(
                    Modifier.size(10, 6).background(ArgbColor(7)).onMove { _, _ ->
                        overlayEvents += 1
                        InputResult.Consumed
                    },
                )
            }
        val session =
            createRuntimeUiSession {
                evaluateComponentTree {
                    Stack(Modifier.size(40, 30).padding(left = 35, top = 24)) {
                        Spacer(
                            Modifier.size(4, 4).then(TooltipModifier(overlay, 0)).onMove { _, _ ->
                                anchorEvents += 1
                                InputResult.Consumed
                            },
                        )
                    }
                }
            }
        session.attach()
        val constraints = Constraints.fixed(40, 30)
        session.frame(constraints)
        session.dispatchPointer(PointerEvent.Move(IntOffset(36, 25)))
        val frame = session.frame(constraints)
        assertEquals(IntSize(40, 30), frame.size)
        assertEquals(
            IntRect(30, 14, 40, 20),
            frame.drawCommands
                .filterIsInstance<DrawCommand.FillRectangle>()
                .single()
                .bounds,
        )
        assertTrue(0 < anchorEvents)
        session.dispatchPointer(PointerEvent.Move(IntOffset(31, 15)))
        assertEquals(0, overlayEvents)
        assertTrue(session.frame(constraints).drawCommands.isEmpty())
        session.close()
    }

    @Test
    fun transformedAnchorEscapesAncestorClipsWithoutScalingItsContent() {
        val content = RootOverlayProbe(IntSize(6, 4))
        content.paint = { it.fillRectangle(IntRect(0, 0, 6, 4), ArgbColor(7)) }
        val anchor = evaluateComponentTree { Spacer(Modifier.size(8, 8).then(TooltipModifier(content.element(), 0))) }
        val clipped = RootOverlayProbe(IntSize(4, 4), IntOffset(1, 1), ChildTransform(0.5, DoubleOffset(0.0, 0.0)))
        val root = RootOverlayProbe(IntSize(40, 30))
        createRuntimeUiSession { root.element(listOf(clipped.element(listOf(anchor)))) }.use { session ->
            session.attach()
            val constraints = Constraints.fixed(40, 30)
            session.frame(constraints)
            session.dispatchPointer(PointerEvent.Move(IntOffset(2, 2)))
            val commands = session.frame(constraints).drawCommands
            val index = commands.indexOfFirst { it is DrawCommand.FillRectangle && it.color == ArgbColor(7) }
            assertTrue(0 <= index)
            assertEquals(IntRect(1, 9, 7, 13), (commands[index] as DrawCommand.FillRectangle).bounds)
            val clips =
                commands.take(index).sumOf {
                    when (it) {
                        is DrawCommand.PushClip -> 1
                        DrawCommand.PopClip -> -1
                        else -> 0
                    }
                }
            assertEquals(1, clips)
        }
    }

    @Test
    fun changingDelayRestartsTimingAndZeroDelayIsImmediate() {
        val delay = mutableStateOf(500L)
        val probe = RootOverlayProbe()
        createRuntimeUiSession {
            val currentDelay = delay.value
            evaluateComponentTree { Spacer(Modifier.then(TooltipModifier(probe.element(), currentDelay))) }
        }.use { session ->
            session.attach()
            val constraints = Constraints.fixed(20, 20)
            session.frame(constraints, FrameTime(0))
            session.dispatchPointer(PointerEvent.Move(IntOffset(1, 1)))
            session.frame(constraints, FrameTime(0))
            delay.value = 1000L
            session.frame(constraints, FrameTime(400_000_000))
            session.frame(constraints, FrameTime(1_000_000_000))
            assertEquals(0, probe.paintCalls)
            session.frame(constraints, FrameTime(1_400_000_000))
            assertEquals(1, probe.paintCalls)
            delay.value = 500L
            session.frame(constraints, FrameTime(1_500_000_000))
            val paintCalls = probe.paintCalls
            delay.value = 0L
            session.frame(constraints, FrameTime(1_500_000_000))
            assertEquals(paintCalls, probe.paintCalls)
            session.resetInputState()
            session.frame(constraints, FrameTime(1_500_000_000))
            session.dispatchPointer(PointerEvent.Move(IntOffset(1, 1)))
            session.frame(constraints, FrameTime(1_500_000_000))
            assertEquals(paintCalls, probe.paintCalls)
        }
    }

    @Test
    fun largeContentIsConstrainedToTheViewportAndResizesWithoutChangingTheAnchor() {
        val root =
            evaluateComponentTree {
                Spacer(Modifier.size(100, 100).background(ArgbColor(9)))
            }
        createRuntimeUiSession {
            evaluateComponentTree {
                Spacer(Modifier.size(4, 4).then(TooltipModifier(root, 0)))
            }
        }.use { session ->
            session.attach()
            val constraints = Constraints.fixed(30, 20)
            session.frame(constraints)
            session.dispatchPointer(PointerEvent.Move(IntOffset(1, 1)))
            assertEquals(
                IntRect(0, 0, 30, 20),
                session
                    .frame(constraints)
                    .drawCommands
                    .filterIsInstance<DrawCommand.FillRectangle>()
                    .single()
                    .bounds,
            )
            val resized = session.frame(Constraints.fixed(12, 10))
            assertEquals(
                IntRect(0, 0, 12, 10),
                resized.drawCommands
                    .filterIsInstance<DrawCommand.FillRectangle>()
                    .single()
                    .bounds,
            )
        }
    }

    @Test
    fun overlayPaintFailureKeepsThePrimaryFailureAndCleansBothTrees() {
        val failure = IllegalStateException("overlay paint")
        val cleanup = IllegalStateException("overlay disposal")
        val probe = RootOverlayProbe()
        probe.paint = { throw failure }
        probe.disposeFailure = cleanup
        val session =
            createRuntimeUiSession {
                evaluateComponentTree { Spacer(Modifier.then(TooltipModifier(probe.element(), 0))) }
            }
        session.attach()
        val constraints = Constraints.fixed(20, 20)
        session.frame(constraints)
        session.dispatchPointer(PointerEvent.Move(IntOffset(1, 1)))
        assertSame(failure, assertFailsWith<IllegalStateException> { session.frame(constraints) })
        assertTrue(cleanup in failure.suppressedExceptions)
        assertEquals(1, probe.disposeCalls)
        session.close()
        assertEquals(1, probe.disposeCalls)
    }

    @Test
    fun contentReplacementKeepsHoverTimingAndCleansRemovedRootsOnce() {
        val first = RootOverlayProbe()
        val second = RootOverlayProbe()
        val replacement = mutableStateOf(false)
        val enabled = mutableStateOf(true)
        val session =
            createRuntimeUiSession {
                val probe = if (replacement.value) second else first
                evaluateComponentTree {
                    Spacer(Modifier.size(20, 20).then(if (enabled.value) Modifier.then(TooltipModifier(probe.element(), 500)) else Modifier))
                }
            }
        session.attach()
        val constraints = Constraints.fixed(30, 30)
        session.frame(constraints, FrameTime(0))
        session.dispatchPointer(PointerEvent.Move(IntOffset(1, 1)))
        session.frame(constraints, FrameTime(0))
        replacement.value = true
        session.frame(constraints, FrameTime(500_000_000))
        assertEquals(1, first.disposeCalls)
        assertEquals(1, second.paintCalls)
        enabled.value = false
        session.frame(constraints, FrameTime(600_000_000))
        assertEquals(1, second.disposeCalls)
        session.close()
        assertEquals(1, first.disposeCalls)
        assertEquals(1, second.disposeCalls)
    }
}
