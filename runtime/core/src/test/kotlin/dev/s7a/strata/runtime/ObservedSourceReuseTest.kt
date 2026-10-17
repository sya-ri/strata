package dev.s7a.strata.runtime

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.map
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies retained reuse through fixture callbacks, actual frame identity and subscription cleanup.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class ObservedSourceReuseTest {
    @Test
    fun oneHundredStableFramesAndEqualProjectionDoNoDependentWork() {
        val source = ObserveTestSource(1)
        var projections = 0
        val parity =
            source.map {
                projections += 1
                it % 2
            }
        var evaluations = 0
        UiSession(TestOwnerDispatcher()) {
            evaluateComponentTree {
                Observe(parity) {
                    evaluations += 1
                    Spacer()
                }
            }
        }.use { session ->
            session.attach()
            val stable = session.frame(Constraints())
            val initialProjections = projections
            repeat(100) { assertSame(stable, session.frame(Constraints())) }
            assertEquals(initialProjections, projections)
            assertEquals(1, evaluations)
            source.publish(1)
            assertSame(stable, session.frame(Constraints()))
            assertEquals(initialProjections, projections)
            source.publish(3)
            assertSame(stable, session.frame(Constraints()))
            assertEquals(initialProjections + 1, projections)
            assertEquals(1, evaluations)
            source.publish(4)
            session.frame(Constraints())
            assertEquals(initialProjections + 2, projections)
            assertEquals(2, evaluations)
        }
        assertEquals(1, source.releases)
    }

    @Test
    fun onlyOneOf128IndependentOwnersReceivesValuesAndReevaluates() {
        val sources = List(128) { ObserveTestSource(0) }
        val evaluations = IntArray(128)
        UiSession(TestOwnerDispatcher()) {
            evaluateComponentTree {
                Column {
                    sources.forEachIndexed { index, source ->
                        Observe(source, key = ElementKey(index)) {
                            evaluations[index] += 1
                            Spacer()
                        }
                    }
                }
            }
        }.use { session ->
            session.attach()
            session.frame(Constraints())
            sources[42].publish(1)
            session.frame(Constraints())
            assertEquals(List(128) { if (it == 42) 2 else 1 }, evaluations.toList())
        }
        sources.forEach { assertEquals(1, it.releases) }
    }

    @Test
    fun nestedPendingRemovalCancelsChildEvaluationAndReleasesSubscriptions() {
        val visible = ObserveTestSource(true)
        val child = ObserveTestSource(0)
        var parentEvaluations = 0
        var childEvaluations = 0
        UiSession(TestOwnerDispatcher()) {
            evaluateComponentTree {
                Observe(visible) {
                    parentEvaluations += 1
                    if (it) {
                        Observe(child) {
                            childEvaluations += 1
                            Spacer()
                        }
                    }
                }
            }
        }.use { session ->
            session.attach()
            session.frame(Constraints())
            visible.publish(false)
            child.publish(1)
            session.frame(Constraints())
            assertEquals(2, parentEvaluations)
            assertEquals(1, childEvaluations)
            assertEquals(1, child.releases)
            assertEquals(0, visible.releases)
        }
        assertEquals(1, visible.releases)
        assertEquals(1, child.releases)
    }

    @Test
    fun mapperFailureClosesAllSubscriptionsAndPreservesTheFailure() {
        val source = ObserveTestSource(0)
        val failure = IllegalStateException("Mapping failed")
        val mapped = source.map { if (it == 0) it else throw failure }
        UiSession(TestOwnerDispatcher()) {
            evaluateComponentTree { Observe(mapped) { Spacer() } }
        }.use { session ->
            session.attach()
            session.frame(Constraints())
            source.publish(1)
            assertSame(failure, assertThrows(IllegalStateException::class.java) { session.frame(Constraints()) })
            assertEquals(1, source.releases)
            assertThrows(IllegalStateException::class.java) { session.frame(Constraints()) }
        }
        assertEquals(1, source.releases)
    }
}
