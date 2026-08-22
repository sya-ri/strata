@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.FocusEvent
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.initialFocus
import dev.s7a.strata.modifier.onFocusChanged
import dev.s7a.strata.modifier.size
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.semantics.SemanticsEntry
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.properties.ReadWriteProperty

/**
 * Verifies immutable frame snapshots, retained caches, keyed identity, and input delegation.
 */
internal class UiSessionFrameInputTest {
    @Test
    fun frameOutputsAreDefensiveAndInputWaitsForTheFirstCommit() {
        val probe = TestProbe()
        val session = UiSession(TestOwnerDispatcher()) { probe.root(emptyList()) }
        session.attach()
        assertEquals(
            InputResult.Ignored,
            session.dispatchPointer(PointerEvent.Move(IntOffset(0, 0))),
        )
        val frame = session.frame(Constraints.fixed(2, 1))
        assertThrows(UnsupportedOperationException::class.java) {
            (frame.drawCommands as MutableList<DrawCommand>).add(frame.drawCommands.single())
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (frame.semantics as MutableList<SemanticsEntry>).add(frame.semantics.single())
        }
        assertEquals(InputResult.Consumed, session.dispatchPointer(PointerEvent.Move(IntOffset(0, 0))))
        session.close()
    }

    @Test
    fun unchangedFramesReuseTheCompleteImmutableSnapshot() {
        val probe = TestProbe()
        val session = UiSession(TestOwnerDispatcher()) { probe.root(emptyList()) }
        session.attach()
        val first = session.frame(Constraints.fixed(2, 1))
        val measureCalls = probe.measureCalls
        val layoutCalls = probe.layoutCalls
        val paintCalls = probe.paintCalls
        val semanticsCalls = probe.semanticsCalls
        val second = session.frame(Constraints.fixed(2, 1))
        assertEquals(measureCalls, probe.measureCalls)
        assertEquals(layoutCalls, probe.layoutCalls)
        assertEquals(paintCalls, probe.paintCalls)
        assertEquals(semanticsCalls, probe.semanticsCalls)
        assertSame(first, second)
        assertSame(first.drawCommands, second.drawCommands)
        assertSame(first.semantics, second.semantics)
        session.close()
    }

    @Test
    fun contentRebuildCreatesFreshFrameEvenWhenTheRetainedTreeStaysClean() {
        val probe = TestProbe()
        val holder = LocalHolder<Int>()
        val contentValues = ArrayList<Int>()
        val session =
            UiSession(TestOwnerDispatcher()) {
                contentValues.add(holder.value)
                probe.root(emptyList())
            }
        holder.delegate = session.state(0)
        session.attach()
        val first = session.frame(Constraints.fixed(2, 1))

        holder.value = 1
        val rebuilt = session.frame(Constraints.fixed(2, 1))
        val clean = session.frame(Constraints.fixed(2, 1))

        assertEquals(listOf(0, 1), contentValues)
        assertEquals(first, rebuilt)
        assertNotSame(first, rebuilt)
        assertNotSame(first.drawCommands, rebuilt.drawCommands)
        assertNotSame(first.semantics, rebuilt.semantics)
        assertSame(rebuilt, clean)
        session.close()
    }

    @Test
    fun dirtyRevisionAndChangedConstraintsEachRebuildTheFrame() {
        val probe = TestProbe()
        val session = UiSession(TestOwnerDispatcher()) { probe.root(emptyList()) }
        session.attach()
        val constraints = Constraints.fixed(2, 1)
        val first = session.frame(constraints)
        val paintCalls = probe.paintCalls

        probe.nodeForTag(TestProbe.ProbeId("root")).invalidateForTest(DirtyMask.of(DirtyPhase.Paint))
        val repainted = session.frame(constraints)
        assertEquals(paintCalls + 1, probe.paintCalls)
        assertNotSame(first, repainted)
        assertNotSame(first.drawCommands, repainted.drawCommands)
        assertSame(repainted, session.frame(constraints))

        val measureCalls = probe.measureCalls
        val resized = session.frame(Constraints.fixed(3, 1))
        assertEquals(measureCalls + 1, probe.measureCalls)
        assertNotSame(repainted, resized)
        assertNotSame(repainted.drawCommands, resized.drawCommands)
        assertSame(resized, session.frame(Constraints.fixed(3, 1)))
        session.close()
    }

    @Test
    fun paintInvalidationDuringAFramePreventsReuseUntilTheNextFrame() {
        val probe = TestProbe()
        val tag = TestProbe.ProbeId("self-invalidating")
        var invalidateOnPaint = true
        val session =
            UiSession(TestOwnerDispatcher()) {
                probe.element(
                    tag,
                    onPaint = {
                        if (invalidateOnPaint) {
                            invalidateOnPaint = false
                            probe.nodeForTag(tag).invalidateForTest(DirtyMask.of(DirtyPhase.Paint))
                        }
                    },
                )
            }
        session.attach()

        val first = session.frame(Constraints.fixed(2, 1))
        val settled = session.frame(Constraints.fixed(2, 1))
        val clean = session.frame(Constraints.fixed(2, 1))

        assertEquals(2, probe.paintCalls)
        assertNotSame(first, settled)
        assertNotSame(first.drawCommands, settled.drawCommands)
        assertSame(settled, clean)
        session.close()
    }

    @Test
    fun detachDropsTheCachedFrameAndReacquiresInitialFocusAfterReattach() {
        val focusEvents = ArrayList<FocusEvent>()
        val session =
            UiSession(TestOwnerDispatcher()) {
                evaluateComponentTree {
                    Spacer(
                        modifier =
                            Modifier.Empty
                                .size(2, 1)
                                .initialFocus()
                                .onFocusChanged(focusEvents::add),
                    )
                }
            }
        val constraints = Constraints.fixed(2, 1)
        session.attach()
        val first = session.frame(constraints)
        assertEquals(listOf(FocusEvent.Gained), focusEvents)

        session.detach()
        assertEquals(listOf(FocusEvent.Gained, FocusEvent.Lost), focusEvents)
        session.attach()
        val reattached = session.frame(constraints)

        assertNotSame(first, reattached)
        assertNotSame(first.drawCommands, reattached.drawCommands)
        assertNotSame(first.semantics, reattached.semantics)
        assertEquals(listOf(FocusEvent.Gained, FocusEvent.Lost, FocusEvent.Gained), focusEvents)
        assertSame(reattached, session.frame(constraints))
        session.close()
    }

    @Test
    fun keyedChildrenRetainNodeIdentityAcrossAChangedFrame() {
        val probe = TestProbe()
        val holder = LocalHolder<Int>()
        val session =
            UiSession(TestOwnerDispatcher()) {
                val tag = TestProbe.ProbeId(holder.value.toString())
                probe.root(listOf(probe.element(tag, key = TestProbe.ProbeId("stable"))))
            }
        holder.delegate = session.state(0)
        session.attach()
        session.frame(Constraints.fixed(2, 2))
        val oldNode = probe.nodeForTag(TestProbe.ProbeId("0"))
        holder.value = 1
        session.frame(Constraints.fixed(2, 2))
        val newNode = probe.nodeForTag(TestProbe.ProbeId("1"))
        assertSame(oldNode, newNode)
        session.close()
    }

    @Test
    fun pointerMutationAppearsInExactlyOneFollowingFrame() {
        val dispatcher = TestOwnerDispatcher()
        val probe = TestProbe()
        val holder = LocalHolder<Int>()
        var contentCalls = 0
        val session =
            UiSession(dispatcher) {
                contentCalls += 1
                probe.element(
                    TestProbe.ProbeId(holder.value.toString()),
                    onInput = { holder.value = 1 },
                )
            }
        holder.delegate = session.state(0)
        session.attach()
        session.frame(Constraints.fixed(2, 1))
        assertEquals(1, contentCalls)
        assertEquals(InputResult.Consumed, session.dispatchPointer(PointerEvent.Move(IntOffset(0, 0))))
        session.frame(Constraints.fixed(2, 1))
        assertEquals(2, contentCalls)
        session.frame(Constraints.fixed(2, 1))
        assertEquals(2, contentCalls)
        session.close()
    }

    @Test
    fun attachAndPipelineCallbacksCannotMutateDeclarativeState() {
        val attachDispatcher = TestOwnerDispatcher()
        val attachHolder = LocalHolder<Int>()
        var attachRejection: Throwable? = null
        val attachFailure = IllegalStateException("attach primary")
        val attachProbe = TestProbe()
        val attachSession =
            UiSession(attachDispatcher) {
                attachProbe.element(
                    TestProbe.ProbeId("attach"),
                    onAttach = {
                        attachRejection =
                            runCatching { attachHolder.value = 1 }
                                .exceptionOrNull()
                        throw attachFailure
                    },
                )
            }
        attachHolder.delegate = attachSession.state(0)
        val attachThrown = assertThrows(IllegalStateException::class.java) { attachSession.attach() }
        assertSame(attachFailure, attachThrown)
        assertTrue(attachRejection is IllegalStateException)
        attachSession.close()

        val frameDispatcher = TestOwnerDispatcher()
        val frameHolder = LocalHolder<Int>()
        var frameRejection: Throwable? = null
        val frameFailure = IllegalStateException("frame primary")
        val frameProbe = TestProbe()
        val frameSession =
            UiSession(frameDispatcher) {
                frameProbe.element(
                    TestProbe.ProbeId("measure"),
                    onMeasure = {
                        frameRejection =
                            runCatching { frameHolder.value = 1 }
                                .exceptionOrNull()
                        throw frameFailure
                    },
                )
            }
        frameHolder.delegate = frameSession.state(0)
        frameSession.attach()
        val frameThrown =
            assertThrows(IllegalStateException::class.java) {
                frameSession.frame(Constraints.fixed(2, 1))
            }
        assertSame(frameFailure, frameThrown)
        assertTrue(frameRejection is IllegalStateException)
        frameSession.close()
    }

    @Test
    fun frameConstructorCopiesCollectionsAndExposesUnmodifiableViews() {
        val bounds = IntRect(0, 0, 1, 1)
        val command = DrawCommand.FillRectangle(bounds, ArgbColor(0))
        val entry = SemanticsEntry(bounds, Semantics())
        val sourceCommands = arrayListOf<DrawCommand>(command)
        val sourceSemantics = arrayListOf(entry)
        val frame = UiFrame(IntSize.Zero, sourceCommands, sourceSemantics)

        sourceCommands.clear()
        sourceSemantics.clear()

        assertEquals(listOf(command), frame.drawCommands)
        assertEquals(listOf(entry), frame.semantics)
        assertThrows(UnsupportedOperationException::class.java) {
            (frame.drawCommands as MutableList<DrawCommand>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (frame.semantics as MutableList<SemanticsEntry>).clear()
        }

        val equalFrame = UiFrame(IntSize.Zero, listOf(command), listOf(entry))
        val differentFrame = UiFrame(IntSize(1, 0), listOf(command), listOf(entry))
        assertEquals(frame, equalFrame)
        assertEquals(frame.hashCode(), equalFrame.hashCode())
        assertNotEquals(frame, differentFrame)
        assertNotEquals(frame, "frame")
    }

    private class LocalHolder<T> {
        lateinit var delegate: ReadWriteProperty<Any?, T>

        var value: T
            get() = delegate.getValue(this, LocalHolder<T>::value)
            set(next) = delegate.setValue(this, LocalHolder<T>::value, next)
    }
}
