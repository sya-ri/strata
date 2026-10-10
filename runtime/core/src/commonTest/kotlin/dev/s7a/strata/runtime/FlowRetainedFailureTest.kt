package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.Constraints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Actual retained first/middle/last child failures preserve original poison and terminal cleanup identities.
 */
internal class FlowRetainedFailureTest {
    @Test
    fun childAndSuppressedCleanupFailuresPreserveExactOriginalCallbacksAndIdentities() {
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
                    val fixture = FlowRetainedTestFixture(side == 0, probes[side])
                    fixture.update((0 until 16).toList())
                    val actual = assertFailsWith<IllegalStateException> { fixture.frame(Constraints(maxWidth = 7, maxHeight = 180)) }
                    assertSame(failure, actual)
                    assertSame(cleanup, actual.suppressedExceptions.single())
                    assertEquals(TreeState.Poisoned, fixture.tree.state)
                    traces += fixture.probe.events.toList()
                    fixture.close()
                    val closed = fixture.probe.events.toList()
                    fixture.close()
                    assertEquals(closed, fixture.probe.events)
                    assertEquals(TreeState.Closed, fixture.tree.state)
                }
                assertEquals(traces[0], traces[1])
            }
        }
    }

    @Test
    fun realScopeOverflowReleasesTheWholeChildCutoffBeforeTheTreePoisons() {
        for (original in listOf(false, true)) {
            val fixture = FlowRetainedTestFixture(original)
            fixture.update(listOf(0, 1, 2), List(3) { Int.MAX_VALUE }, List(3) { 1 })
            assertFailsWith<ArithmeticException> { fixture.frame(Constraints()) }
            assertEquals(3, fixture.probe.measureCalls)
            assertEquals(TreeState.Poisoned, fixture.tree.state)
            fixture.close()
            fixture.close()
        }
    }
}
