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
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.PointerHoverEvent
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.initialFocus
import dev.s7a.strata.modifier.onCapturedPointerEvent
import dev.s7a.strata.modifier.onFocusChanged
import dev.s7a.strata.modifier.onHover
import dev.s7a.strata.modifier.onKeyEvent
import dev.s7a.strata.modifier.onTextInput
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
    fun pointerBurstsSynchronizeOnlyDirtyGeometryBeforeTheNextEvent() {
        val position = IntOffset.Zero
        val scroll = PointerEvent.Scroll(position, 0.0, 1.0)
        val following = listOf(PointerEvent.Move(position), PointerEvent.Press(position, PointerButton.Primary))
        for (phase in listOf(DirtyPhase.Measure, DirtyPhase.Layout)) {
            for (next in following) {
                val probe = TestProbe()
                val tag = TestProbe.ProbeId("scroll")
                var invalidateNext = true
                val session =
                    UiSession(TestOwnerDispatcher()) {
                        probe.element(tag, onInput = {
                            if (invalidateNext) {
                                invalidateNext = false
                                probe.nodeForTag(tag).invalidateForTest(DirtyMask.of(phase))
                            }
                        })
                    }
                session.attach()
                session.frame(Constraints.fixed(2, 1))
                assertEquals(InputResult.Consumed, session.dispatchPointer(scroll))
                assertEquals(1, probe.measureCalls)
                assertEquals(1, probe.layoutCalls)
                assertEquals(InputResult.Consumed, session.dispatchPointer(next))
                assertEquals(if (phase == DirtyPhase.Measure) 2 else 1, probe.measureCalls)
                assertEquals(2, probe.layoutCalls)
                assertEquals(1, probe.paintCalls)
                assertEquals(1, probe.semanticsCalls)
                assertEquals(listOf(scroll, next), probe.inputObservations.map { it.event })
                session.close()
            }
        }
    }

    @Test
    fun keyboardTextAndHoverGeometryChangesAreSafeBeforePointerInput() {
        val probe = TestProbe()
        val tag = TestProbe.ProbeId("editable")
        val invalidate = { probe.nodeForTag(tag).invalidateForTest(DirtyMask.of(DirtyPhase.Measure)) }
        val modifier =
            Modifier.Empty
                .initialFocus()
                .onKeyEvent {
                    invalidate()
                    InputResult.Consumed
                }.onTextInput {
                    invalidate()
                    InputResult.Consumed
                }.onHover { invalidate() }
        val session = UiSession(TestOwnerDispatcher()) { probe.element(tag, modifier = modifier) }
        session.attach()
        session.frame(Constraints.fixed(2, 1))
        assertEquals(InputResult.Consumed, session.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Enter, 0)))
        assertEquals(InputResult.Consumed, session.dispatchPointer(PointerEvent.Move(IntOffset.Zero)))
        assertEquals(InputResult.Consumed, session.dispatchPointer(PointerEvent.Move(IntOffset.Zero)))
        assertEquals(3, probe.measureCalls)
        assertEquals(InputResult.Consumed, session.dispatchTextInput(TextInputEvent.Character('A'.code)))
        assertEquals(InputResult.Consumed, session.dispatchPointer(PointerEvent.Press(IntOffset.Zero, PointerButton.Primary)))
        assertEquals(4, probe.measureCalls)
        assertEquals(1, probe.paintCalls)
        session.close()
    }

    @Test
    fun cleanAndPaintOnlyInputDoNotInvokeGeometryOrFrameCallbacks() {
        val probe = TestProbe()
        val tag = TestProbe.ProbeId("clean")
        val session = UiSession(TestOwnerDispatcher()) { probe.element(tag) }
        session.attach()
        session.frame(Constraints.fixed(2, 1), FrameTime(10L))
        repeat(3) { session.dispatchPointer(PointerEvent.Move(IntOffset.Zero)) }
        probe.nodeForTag(tag).invalidateForTest(DirtyMask.of(DirtyPhase.Paint, DirtyPhase.Semantics))
        repeat(3) { session.dispatchPointer(PointerEvent.Move(IntOffset.Zero)) }
        assertEquals(1, probe.measureCalls)
        assertEquals(1, probe.layoutCalls)
        assertEquals(1, probe.paintCalls)
        assertEquals(1, probe.semanticsCalls)
        assertEquals(listOf(FrameTime(10L)), probe.frameTimes)
        session.close()
    }

    @Test
    fun inputUsesTheLastCommittedConstraintsEvenWhenTheFrameWasNotCacheable() {
        val probe = TestProbe()
        val tag = TestProbe.ProbeId("viewport")
        var contentCalls = 0
        var invalidatePaint = true
        val session =
            UiSession(TestOwnerDispatcher()) {
                contentCalls++
                probe.element(tag, onPaint = {
                    if (invalidatePaint) {
                        invalidatePaint = false
                        probe.nodeForTag(tag).invalidateForTest(DirtyMask.of(DirtyPhase.Paint))
                    }
                })
            }
        session.attach()
        val original = Constraints.fixed(2, 1)
        session.frame(original, FrameTime(10L))
        probe.nodeForTag(tag).invalidateForTest(DirtyMask.of(DirtyPhase.Measure))
        session.dispatchPointer(PointerEvent.Move(IntOffset.Zero))
        assertEquals(listOf(original, original), probe.measureConstraints)
        assertEquals(listOf(FrameTime(10L)), probe.frameTimes)
        assertEquals(1, contentCalls)
        assertEquals(1, probe.paintCalls)
        val resized = Constraints.fixed(4, 3)
        assertEquals(IntSize(4, 3), session.frame(resized, FrameTime(20L)).size)
        assertEquals(listOf(original, original, resized), probe.measureConstraints)
        assertEquals(2, probe.paintCalls)
        session.close()
    }

    @Test
    fun inputGeometryRejectsReentryAndDeclarativeStateMutation() {
        val probe = TestProbe()
        val tag = TestProbe.ProbeId("guarded")
        val holder = LocalHolder<Int>()
        val session = UiSession(TestOwnerDispatcher()) { probe.element(tag) }
        holder.delegate = session.state(0)
        session.attach()
        session.frame(Constraints.fixed(2, 1))
        val node = probe.nodeForTag(tag)
        node.onMeasure = {
            assertThrows(IllegalStateException::class.java) { holder.value = 1 }
            assertThrows(IllegalStateException::class.java) { session.dispatchPointer(PointerEvent.Move(IntOffset.Zero)) }
            assertThrows(IllegalStateException::class.java) { session.close() }
        }
        node.invalidateForTest(DirtyMask.of(DirtyPhase.Measure))
        assertEquals(InputResult.Consumed, session.dispatchPointer(PointerEvent.Move(IntOffset.Zero)))
        assertEquals(0, holder.value)
        assertEquals(1, probe.inputEvents.size)
        session.close()
        assertThrows(IllegalStateException::class.java) { session.dispatchPointer(PointerEvent.Move(IntOffset.Zero)) }
    }

    @Test
    fun inputGeometryFailurePreventsDispatchAndPreservesCleanupFailureIdentity() {
        val primary = IllegalArgumentException("input geometry")
        val cleanup = IllegalStateException("input cleanup")
        val probe = TestProbe()
        val tag = TestProbe.ProbeId("failing geometry")
        val session = UiSession(TestOwnerDispatcher()) { probe.element(tag) }
        session.attach()
        session.frame(Constraints.fixed(2, 1))
        val node = probe.nodeForTag(tag)
        node.onMeasure = { throw primary }
        node.onDetach = { throw cleanup }
        node.invalidateForTest(DirtyMask.of(DirtyPhase.Measure))
        assertSame(primary, assertThrows(IllegalArgumentException::class.java) { session.dispatchPointer(PointerEvent.Move(IntOffset.Zero)) })
        assertEquals(listOf(cleanup), primary.suppressed.toList())
        assertTrue(session.lifecycleState is UiSessionState.Failed)
        assertEquals(emptyList<TestProbe.ProbeId>(), probe.inputEvents)
        val events = probe.events.toList()
        assertEquals(listOf(TestProbe.Event.Attach(tag), TestProbe.Event.Detach(tag), TestProbe.Event.Dispose(tag)), events)
        session.close()
        session.close()
        assertEquals(events, probe.events)
    }

    @Test
    fun selfInvalidatingInputGeometryPoisonsBeforeTheEventIsDelivered() {
        for (phase in listOf(DirtyPhase.Measure, DirtyPhase.Layout)) {
            val probe = TestProbe()
            val tag = TestProbe.ProbeId("unsettled")
            val session = UiSession(TestOwnerDispatcher()) { probe.element(tag) }
            session.attach()
            session.frame(Constraints.fixed(2, 1))
            val node = probe.nodeForTag(tag)
            val invalidate = { node.invalidateForTest(DirtyMask.of(phase)) }
            if (phase == DirtyPhase.Measure) node.onMeasure = invalidate else node.onLayout = invalidate
            invalidate()

            assertThrows(IllegalStateException::class.java) { session.dispatchPointer(PointerEvent.Move(IntOffset.Zero)) }
            assertTrue(session.lifecycleState is UiSessionState.Failed)
            assertTrue(probe.inputEvents.isEmpty())
            assertEquals(listOf(TestProbe.Event.Attach(tag), TestProbe.Event.Detach(tag), TestProbe.Event.Dispose(tag)), probe.events)
            session.close()
            assertEquals(3, probe.events.size)
        }
    }

    @Test
    fun theFrameAfterInputGeometryCommitsPaintAndSemanticsThenCachesNormally() {
        val probe = TestProbe()
        val tag = TestProbe.ProbeId("commit")
        val session = UiSession(TestOwnerDispatcher()) { probe.element(tag) }
        session.attach()
        val constraints = Constraints.fixed(2, 1)
        val first = session.frame(constraints)
        probe.nodeForTag(tag).invalidateForTest(DirtyMask.of(DirtyPhase.Measure))
        session.dispatchPointer(PointerEvent.Move(IntOffset.Zero))
        assertEquals(2, probe.measureCalls)
        assertEquals(2, probe.layoutCalls)
        assertEquals(1, probe.paintCalls)
        assertEquals(1, probe.semanticsCalls)

        val committed = session.frame(constraints)
        assertNotSame(first, committed)
        assertEquals(2, probe.measureCalls)
        assertEquals(2, probe.layoutCalls)
        assertEquals(2, probe.paintCalls)
        assertEquals(2, probe.semanticsCalls)
        assertSame(committed, session.frame(constraints))
        session.close()
    }

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

    @Test
    fun detachCancelsCaptureWithoutDisposingRetainedNodesAndReattachStartsUncaptured() {
        val probe = TestProbe(inputResult = InputResult.Ignored)
        val cancellations = ArrayList<PointerButton>()
        val session =
            UiSession(TestOwnerDispatcher()) {
                probe.root(
                    emptyList(),
                    modifier = Modifier.Empty.onCapturedPointerEvent(cancellations::add) { _, _ -> InputResult.Consumed },
                )
            }
        val constraints = Constraints.fixed(2, 1)
        session.attach()
        session.frame(constraints)
        val retained = probe.created.single()
        session.dispatchPointer(PointerEvent.Press(IntOffset.Zero, PointerButton.Primary))
        session.detach()
        assertEquals(listOf(PointerButton.Primary), cancellations)
        assertEquals(0, probe.events.count { event -> event is TestProbe.Event.Detach || event is TestProbe.Event.Dispose })

        session.attach()
        assertEquals(InputResult.Ignored, session.dispatchPointer(PointerEvent.Move(IntOffset(10, 10))))
        session.frame(constraints)
        assertSame(retained, probe.created.single())
        assertEquals(InputResult.Ignored, session.dispatchPointer(PointerEvent.Move(IntOffset(10, 10))))
        session.dispatchPointer(PointerEvent.Press(IntOffset.Zero, PointerButton.Secondary))
        session.close()
        assertEquals(listOf(PointerButton.Primary, PointerButton.Secondary), cancellations)
        assertEquals(1, probe.events.count { event -> event is TestProbe.Event.Dispose })
    }

    @Test
    fun inputResetCancellationFailureStillClearsEveryHoverAndFocusObserver() {
        val cancellation = IllegalArgumentException("cancel")
        val firstHover = IllegalStateException("inner hover")
        val secondHover = IllegalStateException("outer hover")
        val firstFocus = IllegalStateException("inner focus")
        val secondFocus = IllegalStateException("outer focus")
        var cancellations = 0
        val observed = ArrayList<Throwable>()
        val session =
            UiSession(TestOwnerDispatcher()) {
                evaluateComponentTree {
                    Spacer(
                        modifier =
                            Modifier.Empty
                                .size(2, 1)
                                .initialFocus()
                                .onFocusChanged { event ->
                                    if (event === FocusEvent.Lost) {
                                        recordFailure(observed, secondFocus)
                                    }
                                }.onFocusChanged { event ->
                                    if (event === FocusEvent.Lost) {
                                        recordFailure(observed, firstFocus)
                                    }
                                }.onHover { event ->
                                    if (event === PointerHoverEvent.Exit) {
                                        recordFailure(observed, secondHover)
                                    }
                                }.onHover { event ->
                                    if (event === PointerHoverEvent.Exit) {
                                        recordFailure(observed, firstHover)
                                    }
                                }.onCapturedPointerEvent(
                                    onCancel = { _ ->
                                        cancellations += 1
                                        throw cancellation
                                    },
                                ) { _, _ -> InputResult.Consumed },
                    )
                }
            }
        session.attach()
        session.frame(Constraints.fixed(2, 1))
        session.dispatchPointer(PointerEvent.Move(IntOffset.Zero))
        session.dispatchPointer(PointerEvent.Press(IntOffset.Zero, PointerButton.Primary))

        val failure = assertThrows(IllegalArgumentException::class.java) { session.resetInputState() }
        assertSame(cancellation, failure)
        val cleanup = listOf(firstHover, secondHover, firstFocus, secondFocus)
        assertEquals(cleanup, observed)
        assertEquals(cleanup, failure.suppressed.toList())
        assertEquals(UiSessionState.Failed(cancellation), session.lifecycleState)
        session.close()
        assertEquals(1, cancellations)
    }

    private fun recordFailure(
        observed: MutableList<Throwable>,
        failure: Throwable,
    ): Nothing {
        observed += failure
        throw failure
    }

    private class LocalHolder<T> {
        lateinit var delegate: ReadWriteProperty<Any?, T>

        var value: T
            get() = delegate.getValue(this, LocalHolder<T>::value)
            set(next) = delegate.setValue(this, LocalHolder<T>::value, next)
    }
}
