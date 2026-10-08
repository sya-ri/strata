@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.mutableStateOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Exercises operation guards through real clean, timed, nested and failed sessions on JVM and JavaScript.
 */
internal class PortableOperationGuardTest {
    @Test
    fun cleanAndTimedFramesAcrossManySessionsPreserveOutputAndReleaseTheirGuards() {
        val state = mutableStateOf(0)
        val probes = List(128) { TestProbe() }
        var evaluations = 0
        val sessions = probes.map { probe ->
            createRuntimeUiSession {
                evaluations += 1
                probe.root(emptyList())
            }
        }
        val constraints = Constraints.fixed(2, 1)
        try {
            sessions.forEach { it.attach() }
            val frames = sessions.map { it.frame(constraints) }
            repeat(10) { tick ->
                sessions.forEachIndexed { index, session ->
                    val frame = frames[index]
                    assertSame(frame, session.frame(constraints))
                    assertSame(frame, session.frame(constraints, FrameTime(tick.toLong())))
                    assertEquals(IntSize(2, 1), frame.size)
                    assertEquals(1, frame.drawCommands.size)
                    assertEquals(1, frame.semantics.size)
                }
                state.value = tick + 1
            }
            assertEquals(128, evaluations)
            probes.forEach { probe ->
                assertEquals(1, probe.measureCalls)
                assertEquals(1, probe.paintCalls)
                assertEquals(10, probe.frameTimes.size)
            }
        } finally {
            sessions.forEach { it.close() }
        }
        state.value = 11
        assertEquals(11, state.value)
    }

    @Test
    fun nestedInputCannotBypassOuterFrameButSucceedsAfterItReturns() {
        val state = mutableStateOf(0)
        val outerProbe = TestProbe()
        val innerProbe = TestProbe()
        val inner = createRuntimeUiSession { innerProbe.root(emptyList()) }
        val outer = createRuntimeUiSession { outerProbe.root(emptyList()) }
        val constraints = Constraints.fixed(2, 1)
        try {
            inner.attach()
            val innerFrame = inner.frame(constraints)
            outer.attach()
            outerProbe.nodeForTag(TestProbe.ProbeId("root")).onPaint = {
                inner.dispatchAction {
                    assertFailsWith<IllegalStateException> { state.value = 1 }
                    assertEquals(0, state.value)
                }
                assertFailsWith<IllegalStateException> { outer.frame(constraints) }
            }
            val outerFrame = outer.frame(constraints)
            assertEquals(innerFrame.drawCommands, outerFrame.drawCommands)
            assertEquals(innerFrame.semantics, outerFrame.semantics)
            inner.dispatchAction { state.value = 2 }
            assertEquals(2, state.value)
            assertSame(outerFrame, outer.frame(constraints))
            assertSame(innerFrame, inner.frame(constraints))
        } finally {
            outer.close()
            inner.close()
        }
        state.value = 3
    }

    @Test
    fun pipelineAndCleanupFailuresLeaveNoGuardForTheNextSession() {
        val state = mutableStateOf(0)
        val failure = IllegalStateException("Paint failed")
        val cleanupFailure = IllegalArgumentException("Dispose failed")
        val probe = TestProbe()
        var disposals = 0
        val session = createRuntimeUiSession { probe.root(emptyList()) }
        session.attach()
        val node = probe.nodeForTag(TestProbe.ProbeId("root"))
        node.onPaint = {
            assertFailsWith<IllegalStateException> { state.value = 1 }
            throw failure
        }
        node.onDispose = {
            disposals += 1
            assertFailsWith<IllegalStateException> { state.value = 2 }
            throw cleanupFailure
        }
        assertSame(failure, assertFailsWith<IllegalStateException> { session.frame(Constraints.fixed(2, 1)) })
        assertEquals(listOf(cleanupFailure), failure.suppressedExceptions)
        session.close()
        assertEquals(1, disposals)
        state.value = 3
        val healthy = createRuntimeUiSession { TestProbe().root(emptyList()) }
        try {
            healthy.attach()
            healthy.frame(Constraints.fixed(2, 1))
            healthy.dispatchAction { state.value = 4 }
            assertEquals(4, state.value)
        } finally {
            healthy.close()
        }
    }
}
