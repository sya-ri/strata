@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.MutableState
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription
import dev.s7a.strata.state.mutableStateOf
import java.lang.ref.WeakReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Actual retained sessions release closed and failed roots, deferred captures and subscriptions with caller state alive.
 */
internal class MutableStateSessionRetentionTest {
    @Test
    fun closeAndTerminalPipelineFailureDropRoutingWithoutAnyFollowingAssignment() {
        for (termination in Termination.entries) {
            val state = mutableStateOf(0)
            val retired = retireSession(state, termination)
            assertNull(field(state, "observationPlan"))
            assertEquals(emptySet(), field(state, "observations"))
            for (attempt in 0 until 12) {
                System.gc()
                if (retired.all { it.get() == null }) break
                Thread.sleep(10)
            }
            assertTrue(retired.all { it.get() == null }, "Caller state must not retain a retired screen or deferred callback")
            assertEquals(0, state.value)
        }
    }

    private fun retireSession(
        state: MutableState<Int>,
        termination: Termination,
    ): List<WeakReference<*>> {
        val payload = Payload()
        val probe = TestProbe()
        val source = Source()
        val failure = IllegalArgumentException("Terminal paint failure")
        var failPaint = false
        val session =
            createRuntimeUiSession {
                evaluateComponentTree {
                    Observe(source) {
                        state.value
                        payload.calls += 1
                        element(
                            probe.element(
                                TestProbe.ProbeId("observed"),
                                onPaint = { if (failPaint) throw failure },
                            ),
                        )
                    }
                }
            }
        session.attach()
        session.frame(Constraints.fixed(2, 1))
        state.value = 0
        if (termination == Termination.PipelineFailure) {
            failPaint = true
            assertSame(failure, assertFailsWith<IllegalArgumentException> { session.frame(Constraints.fixed(3, 1)) })
        }
        session.close()
        assertEquals(1, payload.calls)
        assertNull(source.observer)
        assertNull(field(state, "observationPlan"))
        assertEquals(emptySet(), field(state, "observations"))
        return listOf(WeakReference(session), WeakReference(probe), WeakReference(source), WeakReference(payload))
    }

    private fun field(
        instance: Any,
        name: String,
    ): Any? {
        val field = instance.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(instance)
    }

    /**
     * Distinct actual terminal boundaries, each retaining no presentation routing after return.
     */
    private enum class Termination { Close, PipelineFailure }

    /**
     * Callback-only identity with no reference from the authoritative primitive state value.
     */
    private class Payload {
        var calls = 0
    }

    /**
     * Real external subscription callback released by terminal region cleanup.
     */
    private class Source : StateSource<Int> {
        var observer: ((StateSnapshot<Int>) -> Unit)? = null
            private set

        override fun subscribe(observer: (StateSnapshot<Int>) -> Unit): StateSubscription<Int> {
            check(this.observer == null)
            this.observer = observer
            return StateSubscription(StateSnapshot(StateRevision(0), 0)) {
                check(this.observer === observer)
                this.observer = null
            }
        }
    }
}
