package dev.s7a.strata.runtime

import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.lang.ref.WeakReference

/**
 * Retained source callbacks must release committed and late values after every terminal session path.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class ObservedSourceRetentionTest {
    @ParameterizedTest
    @EnumSource(Termination::class)
    fun terminalCallbacksDoNotRetainObservedValues(termination: Termination) {
        val fixture = terminalObservation(termination)
        val late = publishLate(fixture.callback)
        repeat(50) {
            if (fixture.value.get() != null || late.get() != null) {
                System.gc()
                Thread.sleep(10)
            }
        }
        assertNull(fixture.value.get(), "A retained callback kept the terminal committed value.")
        assertNull(late.get(), "A terminal callback retained a late observation.")
        fixture.callback(StateSnapshot(StateRevision(3), Any()))
        fixture.session.close()
    }

    /**
     * Retains the callback independently of its source, subscription, and observation values.
     */
    private fun terminalObservation(termination: Termination): Fixture {
        var callback: ((StateSnapshot<Any>) -> Unit)? = null
        val failure = IllegalStateException("Terminal observation fixture failure.")
        val source =
            StateSource { observer ->
                callback = observer
                StateSubscription(StateSnapshot(StateRevision(0), Any())) {
                    if (termination == Termination.CloseFailure) throw failure
                }
            }
        var failContent = false
        val session =
            UiSession(TestOwnerDispatcher()) {
                evaluateComponentTree {
                    Observe(source) {
                        if (failContent) throw failure
                        Spacer()
                    }
                }
            }
        session.attach()
        session.frame(Constraints())
        val value = Any()
        val retainedCallback = checkNotNull(callback)
        retainedCallback(StateSnapshot(StateRevision(1), value))
        if (termination == Termination.Failure) {
            failContent = true
            assertSame(failure, assertThrows(IllegalStateException::class.java) { session.frame(Constraints()) })
        } else {
            session.frame(Constraints())
            if (termination == Termination.CloseFailure) {
                assertSame(failure, assertThrows(IllegalStateException::class.java, session::close))
            } else {
                session.close()
            }
        }
        return Fixture(retainedCallback, WeakReference(value), session)
    }

    /**
     * Keeps the late value off the collection assertion's stack.
     */
    private fun publishLate(callback: (StateSnapshot<Any>) -> Unit): WeakReference<Any> {
        val value = Any()
        callback(StateSnapshot(StateRevision(2), value))
        return WeakReference(value)
    }

    /**
     * Independent roots that remain live through collection and repeated close assertions.
     */
    private data class Fixture(
        val callback: (StateSnapshot<Any>) -> Unit,
        val value: WeakReference<Any>,
        val session: UiSession,
    )

    /**
     * Terminal boundaries sharing the source binding's value-release contract.
     */
    enum class Termination { Close, Failure, CloseFailure }
}
