@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.PointerHoverEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onHover
import dev.s7a.strata.modifier.size
import dev.s7a.strata.runtime.spi.RuntimeUiSession
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Verifies that the public runtime bridge delegates the existing retained-session contract without widening it.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class RuntimeUiSessionBridgeTest {
    @Test
    fun factoryDoesNotEvaluateContentUntilAttach() {
        val probe = TestProbe()
        var contentCalls = 0
        val session =
            createRuntimeUiSession {
                contentCalls += 1
                probe.root(emptyList())
            }

        assertEquals(0, contentCalls)
        session.close()
        assertEquals(0, contentCalls)
    }

    @Test
    fun lifecycleInputAndRetainedCachesDelegateThroughReattach() {
        val probe = TestProbe()
        var contentCalls = 0
        val session =
            createRuntimeUiSession {
                contentCalls += 1
                probe.root(emptyList())
            }

        session.attach()
        assertEquals(
            InputResult.Ignored,
            session.dispatchPointer(PointerEvent.Move(IntOffset.Zero)),
        )
        val firstFrame = session.frame(Constraints.fixed(2, 1))
        assertSame(firstFrame, session.frame(Constraints.fixed(2, 1)))
        val retainedNode = probe.nodeForTag(TestProbe.ProbeId("root"))
        val measureCalls = probe.measureCalls
        val layoutCalls = probe.layoutCalls
        val paintCalls = probe.paintCalls
        val semanticsCalls = probe.semanticsCalls
        assertEquals(InputResult.Consumed, session.dispatchPointer(PointerEvent.Move(IntOffset.Zero)))

        session.detach()
        session.attach()
        assertEquals(
            InputResult.Ignored,
            session.dispatchPointer(PointerEvent.Move(IntOffset.Zero)),
        )
        val secondFrame = session.frame(Constraints.fixed(2, 1))

        assertEquals(1, contentCalls)
        assertSame(retainedNode, probe.nodeForTag(TestProbe.ProbeId("root")))
        assertEquals(measureCalls, probe.measureCalls)
        assertEquals(layoutCalls, probe.layoutCalls)
        assertEquals(paintCalls, probe.paintCalls)
        assertEquals(semanticsCalls, probe.semanticsCalls)
        assertNotSame(firstFrame, secondFrame)
        assertNotSame(firstFrame.drawCommands, secondFrame.drawCommands)
        assertNotSame(firstFrame.semantics, secondFrame.semantics)
        assertEquals(firstFrame.size, secondFrame.size)
        assertEquals(1, probe.events.count { event -> event is TestProbe.Event.Attach })
        assertEquals(0, probe.events.count { event -> event is TestProbe.Event.Detach })

        session.close()
        session.close()
        assertEquals(1, probe.events.count { event -> event is TestProbe.Event.Detach })
        assertEquals(1, probe.events.count { event -> event is TestProbe.Event.Dispose })
    }

    @Test
    fun detachEmitsOneHoverExitBeforeRetainedReattach() {
        val transitions = ArrayList<PointerHoverEvent>()
        val session =
            createRuntimeUiSession {
                evaluateComponentTree {
                    Spacer(
                        modifier =
                            Modifier.Empty
                                .size(2, 1)
                                .onHover(transitions::add),
                    )
                }
            }

        session.attach()
        session.frame(Constraints.fixed(2, 1))
        assertEquals(InputResult.Ignored, session.dispatchPointer(PointerEvent.Move(IntOffset.Zero)))
        session.detach()
        assertEquals(listOf(PointerHoverEvent.Enter, PointerHoverEvent.Exit), transitions)

        session.attach()
        session.frame(Constraints.fixed(2, 1))
        session.detach()
        assertEquals(listOf(PointerHoverEvent.Enter, PointerHoverEvent.Exit), transitions)
        session.close()
    }

    @Test
    fun invalidTransitionsAndEveryOperationRejectWrongThread() {
        val probe = TestProbe()
        val session = createRuntimeUiSession { probe.root(emptyList()) }

        assertNull(session.textInputFocus)
        assertTrue(wrongThread { session.textInputFocus } is IllegalStateException)
        assertThrows(IllegalStateException::class.java) { session.detach() }
        assertThrows(IllegalStateException::class.java) { session.frame(Constraints.fixed(2, 1)) }
        assertThrows(IllegalStateException::class.java) {
            session.dispatchPointer(PointerEvent.Move(IntOffset.Zero))
        }
        assertTrue(wrongThread { session.attach() } is IllegalStateException)

        session.attach()
        assertThrows(IllegalStateException::class.java) { session.attach() }
        assertNull(session.textInputFocus)
        assertTrue(wrongThread { session.detach() } is IllegalStateException)
        val retainedFrame = session.frame(Constraints.fixed(2, 1))
        assertNull(session.textInputFocus)
        assertTrue(wrongThread { session.textInputFocus } is IllegalStateException)
        assertTrue(wrongThread { session.frame(Constraints.fixed(2, 1)) } is IllegalStateException)
        assertSame(retainedFrame, session.frame(Constraints.fixed(2, 1)))
        assertTrue(
            wrongThread { session.dispatchPointer(PointerEvent.Move(IntOffset.Zero)) } is IllegalStateException,
        )
        assertTrue(wrongThread { session.close() } is IllegalStateException)

        session.detach()
        assertNull(session.textInputFocus)
        assertThrows(IllegalStateException::class.java) { session.frame(Constraints.fixed(2, 1)) }
        session.close()
        session.close()
        assertThrows(IllegalStateException::class.java) { session.textInputFocus }
        assertTrue(wrongThread { session.close() } is IllegalStateException)
        assertThrows(IllegalStateException::class.java) { session.attach() }
        assertThrows(IllegalStateException::class.java) { session.detach() }
        assertThrows(IllegalStateException::class.java) { session.frame(Constraints.fixed(2, 1)) }
        assertThrows(IllegalStateException::class.java) {
            session.dispatchPointer(PointerEvent.Move(IntOffset.Zero))
        }
    }

    @Test
    fun frameSnapshotsAreStableAndRejectJavaMutableCasts() {
        val probe = TestProbe()
        val session = createRuntimeUiSession { probe.root(emptyList()) }
        session.attach()
        val frame = session.frame(Constraints.fixed(2, 1))
        val sourceDrawCommands = frame.drawCommands.toList()
        val sourceSemantics = frame.semantics.toList()

        assertThrows(UnsupportedOperationException::class.java) {
            (frame.drawCommands as MutableList).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (frame.semantics as MutableList).clear()
        }
        assertEquals(sourceDrawCommands, frame.drawCommands)
        assertEquals(sourceSemantics, frame.semantics)
        session.close()
    }

    @Test
    fun contentFailurePreservesIdentityAndDoesNotRepeatCleanup() {
        val primary = IllegalArgumentException("content")
        var contentCalls = 0
        val session =
            createRuntimeUiSession {
                contentCalls += 1
                throw primary
            }

        assertSame(primary, assertThrows(IllegalArgumentException::class.java) { session.attach() })
        session.close()
        session.close()
        assertEquals(1, contentCalls)
    }

    @Test
    fun paintFailurePreservesIdentityAndCleansExactlyOnce() {
        val paintFailure = IllegalStateException("paint")
        val disposeFailure = IllegalStateException("dispose")
        val probe =
            TestProbe(
                failingPaintTag = TestProbe.ProbeId("root"),
                failingDisposeTag = TestProbe.ProbeId("root"),
                paintFailure = paintFailure,
                disposeFailure = disposeFailure,
            )
        val session = createRuntimeUiSession { probe.root(emptyList()) }
        session.attach()

        val thrown =
            assertThrows(IllegalStateException::class.java) {
                session.frame(Constraints.fixed(2, 1))
            }
        assertSame(paintFailure, thrown)
        assertEquals(listOf(disposeFailure), thrown.suppressed.toList())
        assertEquals(1, probe.events.count { event -> event is TestProbe.Event.Dispose })
        session.close()
        session.close()
        assertEquals(1, probe.events.count { event -> event is TestProbe.Event.Dispose })
    }

    @Test
    fun closePreservesPrimaryAndSuppressedCleanupIdentityOnce() {
        val detachFailure = IllegalStateException("detach")
        val disposeFailure = IllegalStateException("dispose")
        val probe =
            TestProbe(
                failingDetachTag = TestProbe.ProbeId("root"),
                failingDisposeTag = TestProbe.ProbeId("root"),
                detachFailure = detachFailure,
                disposeFailure = disposeFailure,
            )
        val session = createRuntimeUiSession { probe.root(emptyList()) }
        session.attach()

        val thrown = assertThrows(IllegalStateException::class.java) { session.close() }
        assertSame(detachFailure, thrown)
        assertEquals(listOf(disposeFailure), thrown.suppressed.toList())
        assertEquals(1, probe.events.count { event -> event is TestProbe.Event.Detach })
        assertEquals(1, probe.events.count { event -> event is TestProbe.Event.Dispose })
        session.close()
        assertEquals(1, probe.events.count { event -> event is TestProbe.Event.Detach })
        assertEquals(1, probe.events.count { event -> event is TestProbe.Event.Dispose })
    }

    private fun wrongThread(action: () -> Unit): Throwable? {
        val task = FutureTask<Throwable?> { runCatching(action).exceptionOrNull() }
        val thread = Thread(task)
        thread.start()
        return task.get(5, TimeUnit.SECONDS)
    }
}
