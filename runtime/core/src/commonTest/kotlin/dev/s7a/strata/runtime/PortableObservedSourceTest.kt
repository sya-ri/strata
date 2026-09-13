package dev.s7a.strata.runtime

import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription
import dev.s7a.strata.state.map
import dev.s7a.strata.state.mutableStateOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Exercises source projection, ordinary conditionals, and diagnostics together on JVM and JavaScript.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class PortableObservedSourceTest {
    @Test
    fun deferredReadsRefreshIndependentlyAndReleaseInactiveDependencies() {
        val source = StateSource<Unit> { StateSubscription(StateSnapshot(StateRevision(0), Unit)) {} }
        val visible = mutableStateOf(true)
        val alternate = mutableStateOf(false)
        val value = mutableStateOf(0)
        val other = mutableStateOf(10)
        val seen = ArrayList<Int>()
        var roots = 0
        val session =
            createRuntimeUiSession {
                roots += 1
                evaluateComponentTree {
                    Stack {
                        if (visible.value) {
                            Observe(source) {
                                seen.add(if (alternate.value) other.value else value.value)
                                Spacer()
                            }
                        }
                    }
                }
            }
        session.use {
            session.attach()
            session.frame(Constraints())
            value.value = 1
            session.frame(Constraints())
            assertEquals(listOf(0, 1), seen)
            assertEquals(1, roots)
            alternate.value = true
            val unchanged = session.frame(Constraints())
            value.value = 2
            assertSame(unchanged, session.frame(Constraints()))
            assertEquals(listOf(0, 1, 10), seen)
            visible.value = false
            val removed = session.frame(Constraints())
            other.value = 11
            assertSame(removed, session.frame(Constraints()))
            assertEquals(2, roots)
        }
        other.value = 12
    }

    @Test
    fun rootAndDeferredReadsShareOneMutationGuard() {
        val source = StateSource<Unit> { StateSubscription(StateSnapshot(StateRevision(0), Unit)) {} }
        val value = mutableStateOf(0)
        val seen = ArrayList<Int>()
        createRuntimeUiSession {
            val rootValue = value.value
            evaluateComponentTree {
                Stack {
                    Observe(source) {
                        assertEquals(rootValue, value.value)
                        seen.add(value.value)
                        Spacer()
                    }
                }
            }
        }.use { session ->
            session.attach()
            session.frame(Constraints())
            value.value = 1
            session.frame(Constraints())
            assertEquals(listOf(0, 1), seen)
        }
        value.value = 2
    }

    @Test
    fun sourceUpdatesStayPartialWhileOrdinaryConditionalsReleaseTheirSubscriptions() {
        var deliver: ((StateSnapshot<Int>) -> Unit)? = null
        var subscriptions = 0
        var releases = 0
        val source =
            StateSource<Int> { observer ->
                subscriptions += 1
                deliver = observer
                StateSubscription(StateSnapshot(StateRevision(0), 0)) {
                    releases += 1
                    deliver = null
                }
            }
        val parity = source.map { it % 2 }
        val visible = mutableStateOf(true)
        val seen = ArrayList<Int>()
        val session =
            createRuntimeUiSession {
                evaluateComponentTree {
                    Stack {
                        if (visible.value) {
                            Observe(parity, parity) { left, right ->
                                assertEquals(left, right)
                                seen.add(left)
                                Spacer()
                            }
                        }
                    }
                }
            }
        try {
            session.attach()
            session.frame(Constraints())
            val monitor = session.startRenderMonitoring()
            try {
                checkNotNull(deliver)(StateSnapshot(StateRevision(1), 2))
                session.frame(Constraints())
                assertEquals(listOf(0), seen)
                checkNotNull(deliver)(StateSnapshot(StateRevision(2), 3))
                session.frame(Constraints())
                assertEquals(listOf(0, 1), seen)
                assertEquals(0L, monitor.snapshot().counts[UiRenderMetric.RootEvaluation])
                assertEquals(1, subscriptions)
                visible.value = false
                session.frame(Constraints())
                assertEquals(1, releases)
                assertEquals(1L, monitor.snapshot().counts[UiRenderMetric.RootEvaluation])
            } finally {
                monitor.close()
            }
        } finally {
            session.close()
        }
        assertEquals(1, releases)
    }
}
