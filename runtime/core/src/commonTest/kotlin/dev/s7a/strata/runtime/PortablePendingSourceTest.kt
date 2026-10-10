@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.map
import dev.s7a.strata.state.mutableStateOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Actual observed-root cutoff, dependent ordering, replacement and terminal failure parity on both targets.
 */
internal class PortablePendingSourceTest {
    @Test
    fun oneOfManyRootsUpdatesOnlyItsConsumerAndEqualProjectionStopsPropagation() {
        for (count in listOf(1, 128, 1_024)) {
            val sources = List(count) { CutoffTestSource(0) }
            val projections = sources.map { source -> source.map { it % 2 } }
            val evaluations = IntArray(count)
            val session =
                createRuntimeUiSession {
                    evaluateComponentTree {
                        Stack {
                            projections.forEachIndexed { index, source ->
                                Observe(source, key = ElementKey(index)) {
                                    evaluations[index] += 1
                                    Spacer()
                                }
                            }
                        }
                    }
                }
            try {
                session.attach()
                val constraints = Constraints.fixed(4, 4)
                val initial = session.frame(constraints)
                repeat(10) { assertSame(initial, session.frame(constraints)) }
                sources.last().publish(2)
                assertSame(initial, session.frame(constraints))
                assertEquals(List(count) { 1 }, evaluations.toList())
                val monitor = session.startRenderMonitoring()
                try {
                    sources.last().publish(3)
                    session.frame(constraints)
                    val evidence = monitor.snapshot()
                    assertEquals(1L, evidence.counts[UiRenderMetric.RootValueChange])
                    assertEquals(1L, evidence.counts[UiRenderMetric.Projection])
                    assertEquals(1L, evidence.counts[UiRenderMetric.ConsumerNotification])
                    assertEquals(List(count) { index -> if (index == count - 1) 2 else 1 }, evaluations.toList())
                    sources.forEach { it.publish(4) }
                    session.frame(constraints)
                    assertEquals(List(count) { index -> if (index == count - 1) 3 else 1 }, evaluations.toList())
                } finally {
                    monitor.close()
                }
            } finally {
                session.close()
            }
            sources.forEach {
                assertEquals(1, it.subscriptions)
                assertEquals(1, it.releases)
            }
        }
    }

    @Test
    fun publicationFromEqualityCannotChangeAnotherRootsAlreadyCapturedValue() {
        val second = CutoffTestSource(Value(0))
        var publishDuringComparison = false
        val first =
            CutoffTestSource(
                Value(0) {
                    if (publishDuringComparison) {
                        publishDuringComparison = false
                        second.publish(Value(2))
                    }
                },
            )
        val seen = ArrayList<List<Int>>()
        val session =
            createRuntimeUiSession {
                evaluateComponentTree {
                    Observe(first, second) { left, right ->
                        seen.add(listOf(left.number, right.number))
                        Spacer()
                    }
                }
            }
        try {
            session.attach()
            val constraints = Constraints.fixed(4, 4)
            session.frame(constraints)
            first.publish(Value(1))
            second.publish(Value(1))
            publishDuringComparison = true
            session.frame(constraints)
            assertEquals(listOf(0, 0), seen.first())
            assertEquals(listOf(1, 1), seen.last())
            session.frame(constraints)
            assertEquals(listOf(1, 2), seen.last())
            assertEquals(3, seen.size)
        } finally {
            session.close()
        }
    }

    @Test
    fun sourceReplacementRemovalDetachAndStaleCallbacksPreserveSubscriptionLifetimes() {
        val first = CutoffTestSource(0)
        val second = CutoffTestSource(10)
        val source = mutableStateOf(first)
        val visible = mutableStateOf(true)
        val seen = ArrayList<Int>()
        val session =
            createRuntimeUiSession {
                val current = source.value
                evaluateComponentTree {
                    Stack {
                        if (visible.value) {
                            Observe(current) {
                                seen.add(it)
                                Spacer()
                            }
                        }
                    }
                }
            }
        try {
            session.attach()
            val constraints = Constraints.fixed(4, 4)
            session.frame(constraints)
            first.publish(1)
            source.value = second
            session.frame(constraints)
            assertEquals(10, seen.last())
            assertEquals(1, first.releases)
            checkNotNull(first.staleObserver)(StateSnapshot(StateRevision(100), 99))
            val stable = session.frame(constraints)
            assertEquals(10, seen.last())
            session.detach()
            second.publish(11)
            session.attach()
            session.frame(constraints)
            assertEquals(11, seen.last())
            visible.value = false
            session.frame(constraints)
            assertEquals(1, second.releases)
            checkNotNull(second.staleObserver)(StateSnapshot(StateRevision(100), 99))
            assertEquals(11, seen.last())
            assertEquals(emptyList(), stable.drawCommands)
        } finally {
            session.close()
        }
        assertEquals(1, first.releases)
        assertEquals(1, second.releases)
    }

    @Test
    fun throwingProjectionPreservesPrimaryAndReleasesEveryQueuedSourceDespiteCleanupFailure() {
        val first = CutoffTestSource(0)
        val second = CutoffTestSource(0)
        val failure = IllegalStateException("Projection failed")
        val cleanup = IllegalArgumentException("Subscription cleanup failed")
        var failProjection = false
        val mapped = first.map { value -> if (failProjection) throw failure else value }
        val session =
            createRuntimeUiSession {
                evaluateComponentTree { Observe(mapped, second) { _, _ -> Spacer() } }
            }
        session.attach()
        val constraints = Constraints.fixed(4, 4)
        session.frame(constraints)
        second.closeFailure = cleanup
        failProjection = true
        first.publish(1)
        second.publish(1)
        assertSame(failure, assertFailsWith<IllegalStateException> { session.frame(constraints) })
        assertEquals(listOf(cleanup), failure.suppressedExceptions)
        assertEquals(1, first.releases)
        assertEquals(1, second.releases)
        session.close()
        checkNotNull(first.staleObserver)(StateSnapshot(StateRevision(100), 99))
        checkNotNull(second.staleObserver)(StateSnapshot(StateRevision(100), 99))
    }

    /**
     * Application value equality may enqueue another root but cannot execute frame work.
     */
    private class Value(
        val number: Int,
        private val compared: () -> Unit = {},
    ) {
        override fun equals(other: Any?): Boolean {
            compared()
            return other is Value && number == other.number
        }

        override fun hashCode(): Int = number
    }
}
