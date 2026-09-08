package dev.s7a.strata.runtime

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.UiScope
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.node.StateObserverNode
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.map
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies projections through the real retained registry and frame lifecycle.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MappedObservationTest {
    @Test
    fun equalProjectionKeepsFrameAndStopsDownstreamTransformations() {
        val source = ObserveTestSource(1)
        var firstCalls = 0
        var secondCalls = 0
        var evaluations = 0
        val parity =
            source.map {
                firstCalls += 1
                it % 2
            }
        val label =
            parity.map {
                secondCalls += 1
                "Parity $it"
            }
        session {
            Observe(label) {
                evaluations += 1
                Spacer()
            }
        }.use { session ->
            session.attach()
            session.frame(Constraints())
            val stable = session.frame(Constraints())
            source.publish(3)
            assertSame(stable, session.frame(Constraints()))
            assertEquals(2, firstCalls)
            assertEquals(1, secondCalls)
            assertEquals(1, evaluations)
            source.publish(4)
            session.frame(Constraints())
            assertEquals(3, firstCalls)
            assertEquals(2, secondCalls)
            assertEquals(2, evaluations)
            assertEquals(1, source.subscriptions)
        }
        assertEquals(1, source.releases)
    }

    @Test
    fun originalAndChainedConsumersShareCommittedInputAndSubscription() {
        val source = ObserveTestSource(1)
        var transforms = 0
        val mapped =
            source.map {
                transforms += 1
                "value=$it"
            }
        val pairs = ArrayList<Pair<Int, String>>()
        session {
            Column {
                Observe(source, mapped, mapped) { original, first, second ->
                    assertEquals(first, second)
                    pairs.add(original to first)
                    Spacer()
                }
                Observe(mapped) { Spacer() }
            }
        }.use { session ->
            session.attach()
            session.frame(Constraints())
            source.publish(2)
            source.publish(3)
            session.frame(Constraints())
            assertEquals(listOf(1 to "value=1", 3 to "value=3"), pairs)
            assertEquals(2, transforms)
            assertEquals(1, source.subscriptions)
        }
        assertEquals(1, source.releases)
    }

    @Test
    fun oneChangedRootDoesNotNotifyOtherConsumers() {
        val sources = List(128) { ObserveTestSource(0) }
        val notifications = IntArray(128)
        ObservedSourceRegistry().use { registry ->
            sources.forEachIndexed { index, source ->
                registry.synchronize(
                    object : StateObserverNode {
                        override val observedSources: List<StateSource<*>> = listOf(source)

                        override fun commitObservedValues(values: List<Any?>) {
                            notifications[index] += 1
                        }
                    },
                )
            }
            sources[42].publish(1)
            registry.capture()
            registry.commit()
            registry.finishFrame()
            assertEquals(List(128) { if (it == 42) 2 else 1 }, notifications.toList())
        }
        sources.forEach { source -> assertEquals(1, source.releases) }
    }

    @Test
    fun projectionAdmittedByParentUsesCurrentCutoffAndDefersNewNotifications() {
        val source = ObserveTestSource(0)
        val values = ArrayList<String>()
        session {
            Observe(source) { parent ->
                if (parent == 1) source.publish(2)
                val mapped = source.map { "value=$it" }
                Observe(mapped) { child ->
                    values.add("$parent/$child")
                    Spacer()
                }
            }
        }.use { session ->
            session.attach()
            session.frame(Constraints())
            source.publish(1)
            session.frame(Constraints())
            assertEquals(listOf("0/value=0", "1/value=1"), values)
            session.frame(Constraints())
            assertEquals("2/value=2", values.last())
            assertEquals(1, source.subscriptions)
        }
        assertEquals(1, source.releases)
    }

    @Test
    fun failedInitialProjectionReleasesAcquiredRoot() {
        val source = ObserveTestSource(0)
        val failure = IllegalArgumentException("projection failed")
        val mapped = source.map<Int, String> { throw failure }
        session { Observe(mapped) { Spacer() } }.use { session ->
            session.attach()
            assertSame(failure, assertThrows(IllegalArgumentException::class.java) { session.frame(Constraints()) })
        }
        assertEquals(1, source.subscriptions)
        assertEquals(1, source.releases)
    }

    private fun session(content: UiScope.() -> Unit): UiSession = UiSession(TestOwnerDispatcher()) { evaluateComponentTree(content) }
}
