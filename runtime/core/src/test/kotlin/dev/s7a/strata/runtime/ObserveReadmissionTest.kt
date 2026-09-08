package dev.s7a.strata.runtime

import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.UiScope
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Verifies frame-consistent source readmission and bounded cleanup during structural reconciliation.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class ObserveReadmissionTest {
    @Test
    fun replacingTheLastObservingChildByKeyKeepsItsCapturedValueAndPendingPublication() {
        verifyReadmission { child, generation, seen ->
            Observe(child, key = ElementKey(generation)) { value ->
                seen.add(value)
                Spacer()
            }
        }
    }

    @Test
    fun replacingTheParentLayoutKeepsTheNestedSourcesCapturedValue() {
        verifyReadmission { child, generation, seen ->
            val content: UiScope.() -> Unit = {
                Observe(child) { value ->
                    seen.add(value)
                    Spacer()
                }
            }
            if (generation == 0) Row(content = content) else Stack(content = content)
        }
    }

    @Test
    fun rebuildingSessionContentKeepsSourcesUntilDeferredChildrenAreSynchronized() {
        val parent = ObserveTestSource(0)
        val child = ObserveTestSource(0)
        val seen = ArrayList<Int>()
        lateinit var generation: () -> Int
        val session =
            session {
                val current = generation()
                if (current == 1) publishOnWorker(child, 11)
                Observe(child, key = ElementKey(current)) { value ->
                    seen.add(value)
                    Spacer()
                }
            }
        val current by session.bind(parent)
        generation = { current }
        session.use {
            session.attach()
            session.frame(Constraints())
            child.publish(10)
            parent.publish(1)
            session.frame(Constraints())
            assertEquals(listOf(0, 10), seen)
            session.frame(Constraints())
            assertEquals(listOf(0, 10, 11), seen)
            assertEquals(1, child.subscriptions)
        }
        assertEquals(1, child.releases)
    }

    @Test
    fun standaloneTreeMeasurementRetainsReplacedSourcesOnlyUntilTheOperationReturns() {
        val parent = ObserveTestSource(0)
        val child = ObserveTestSource(0)
        val seen = ArrayList<Int>()

        fun description(generation: Int) =
            evaluateComponentTree {
                Observe(parent) {
                    if (generation == 1) publishOnWorker(child, 1)
                    if (generation < 2) {
                        Observe(child, key = ElementKey(generation)) { value ->
                            seen.add(value)
                            Spacer()
                        }
                    }
                }
            }
        UiTree().use { tree ->
            tree.update(description(0))
            tree.measure(Constraints())
            tree.update(description(1))
            tree.measure(Constraints())
            assertEquals(listOf(0, 0), seen)
            assertEquals(1, child.subscriptions)
            tree.update(description(2))
            tree.measure(Constraints())
            assertEquals(1, child.releases)
        }
        assertEquals(1, parent.releases)
        assertEquals(1, child.releases)
    }

    @Test
    fun removingTheLastOwnerClosesBeforeReturningAndCacheHitsKeepNoOrphanedBinding() {
        val visible = ObserveTestSource(true)
        val child = ObserveTestSource(0)
        session {
            Observe(visible) { show ->
                if (show) Observe(child) { Spacer() }
            }
        }.use { session ->
            session.attach()
            session.frame(Constraints())
            visible.publish(false)
            session.frame(Constraints())
            assertEquals(1, child.releases)
            val cached = session.frame(Constraints())
            assertSame(cached, session.frame(Constraints()))
            visible.publish(true)
            session.frame(Constraints())
            assertEquals(2, child.subscriptions)
        }
        assertEquals(2, child.releases)
    }

    @Test
    fun closingAnUnusedBindingAtFrameEndPoisonsTheSessionAndReleasesOtherBindings() {
        val visible = ObserveTestSource(true)
        val child = ObserveTestSource(0)
        val other = ObserveTestSource(0)
        val cleanupFailure = IllegalStateException("deferred cleanup")
        val broken = closingWithFailure(child, cleanupFailure)
        session {
            Observe(visible) { show ->
                if (show) Observe(broken, other) { _, _ -> Spacer() }
            }
        }.use { session ->
            session.attach()
            session.frame(Constraints())
            visible.publish(false)
            assertSame(cleanupFailure, assertThrows(IllegalStateException::class.java) { session.frame(Constraints()) })
            assertEquals(1, child.releases)
            assertEquals(1, other.releases)
            assertEquals(1, visible.releases)
        }
    }

    @Test
    fun deferredCleanupPreservesSourceReleaseOrderWhenMultipleClosersFail() {
        val visible = ObserveTestSource(true)
        val first = ObserveTestSource(0)
        val second = ObserveTestSource(0)
        val firstFailure = IllegalStateException("first cleanup")
        val secondFailure = IllegalArgumentException("second cleanup")
        val brokenFirst = closingWithFailure(first, firstFailure)
        val brokenSecond = closingWithFailure(second, secondFailure)
        session {
            Observe(visible) { show ->
                if (show) Observe(brokenFirst, brokenSecond) { _, _ -> Spacer() }
            }
        }.use { session ->
            session.attach()
            session.frame(Constraints())
            visible.publish(false)
            assertSame(firstFailure, assertThrows(IllegalStateException::class.java) { session.frame(Constraints()) })
            assertEquals(listOf(secondFailure), firstFailure.suppressed.toList())
            assertEquals(1, first.releases)
            assertEquals(1, second.releases)
        }
    }

    @Test
    fun failingAfterReadmissionPreservesTheEvaluationFailureAndSuppressesCleanupFailure() {
        val parent = ObserveTestSource(0)
        val child = ObserveTestSource(0)
        val cleanupFailure = IllegalStateException("failed cleanup")
        val evaluationFailure = IllegalArgumentException("failed readmission")
        val broken = closingWithFailure(child, cleanupFailure)
        session {
            Observe(parent) { generation ->
                Observe(broken, key = ElementKey(generation)) {
                    if (generation == 1) throw evaluationFailure
                    Spacer()
                }
            }
        }.use { session ->
            session.attach()
            session.frame(Constraints())
            parent.publish(1)
            assertSame(evaluationFailure, assertThrows(IllegalArgumentException::class.java) { session.frame(Constraints()) })
            assertTrue(evaluationFailure.suppressed.any { it === cleanupFailure })
            assertEquals(1, child.subscriptions)
            assertEquals(1, child.releases)
            assertEquals(1, parent.releases)
        }
    }

    private fun verifyReadmission(content: UiScope.(ObserveTestSource<Int>, Int, MutableList<Int>) -> Unit) {
        val parent = ObserveTestSource(0)
        val child = ObserveTestSource(0)
        val seen = ArrayList<Int>()
        session {
            Observe(parent) { generation ->
                if (generation == 1) publishOnWorker(child, 11)
                content(child, generation, seen)
            }
        }.use { session ->
            session.attach()
            session.frame(Constraints())
            child.publish(10)
            parent.publish(1)
            session.frame(Constraints())
            assertEquals(listOf(0, 10), seen)
            assertEquals(1, child.subscriptions)
            assertEquals(0, child.releases)
            session.frame(Constraints())
            assertEquals(listOf(0, 10, 11), seen)
        }
        assertEquals(1, child.releases)
    }

    private fun publishOnWorker(
        source: ObserveTestSource<Int>,
        value: Int,
    ) {
        val task = FutureTask { source.publish(value) }
        Thread(task).start()
        task.get(5, TimeUnit.SECONDS)
    }

    private fun closingWithFailure(
        source: ObserveTestSource<Int>,
        failure: Throwable,
    ): StateSource<Int> =
        StateSource { observer ->
            val subscription = source.subscribe(observer)
            StateSubscription(subscription.initialSnapshot) {
                subscription.close()
                throw failure
            }
        }

    private fun session(content: UiScope.() -> Unit): UiSession = UiSession(TestOwnerDispatcher()) { evaluateComponentTree(content) }
}
