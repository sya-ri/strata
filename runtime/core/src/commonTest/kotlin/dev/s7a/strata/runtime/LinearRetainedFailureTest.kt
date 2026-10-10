package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.Constraints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Pipeline failures preserve primary/suppressed identity, poisoned cleanup, and idempotent terminal close.
 */
internal class LinearRetainedFailureTest {
    @Test
    fun firstMiddleLastChildFailuresPreserveOriginalCleanupAndThrowableIdentities() {
        for (horizontal in listOf(true, false)) {
            for (index in listOf(0, 8, 15)) {
                for (stage in listOf(TestProbe.FailureStage.Measure, TestProbe.FailureStage.Layout)) {
                    val failure = IllegalStateException("Primary child failure")
                    val cleanup = IllegalArgumentException("Cleanup child failure")
                    val tag = TestProbe.ProbeId(index.toString())
                    val probes = List(2) {
                        TestProbe(
                            failingMeasureTag = if (stage == TestProbe.FailureStage.Measure) tag else null,
                            failingLayoutTag = if (stage == TestProbe.FailureStage.Layout) tag else null,
                            failingDisposeTag = TestProbe.ProbeId("15"),
                            measureFailure = failure,
                            layoutFailure = failure,
                            disposeFailure = cleanup,
                        )
                    }
                    val traces = mutableListOf<List<TestProbe.Event>>()
                    for (side in listOf(0, 1)) {
                        val fixture = LinearRetainedTestFixture(side == 0, horizontal, probes[side])
                        fixture.update((0 until 16).toList(), List(16) { if (it % 2 == 0) 1f else null })
                        val actual = assertFailsWith<IllegalStateException> { fixture.frame(Constraints.fixed(320, 180)) }
                        assertSame(failure, actual)
                        assertSame(cleanup, actual.suppressedExceptions.single())
                        assertEquals(TreeState.Poisoned, fixture.tree.state)
                        traces += fixture.probe.events.toList()
                        fixture.close()
                        val closedEvents = fixture.probe.events.toList()
                        fixture.close()
                        assertEquals(closedEvents, fixture.probe.events)
                        assertEquals(TreeState.Closed, fixture.tree.state)
                    }
                    assertEquals(traces[0], traces[1])
                }
            }
        }
    }
}
