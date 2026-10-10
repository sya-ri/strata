@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.runtime.spi.RuntimeUiSession
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Real weak-reference and foreign-owner evidence with closed/live session carriers deliberately kept reachable.
 */
internal class LinearMeasurementReleaseTest {
    @Test
    fun normalAndExceptionalCloseReleaseTreeSourceObserverAndContentCaptures() {
        for (horizontal in listOf(true, false)) {
            for (fails in listOf(false, true)) {
                val evidence = closedOwners(horizontal, fails)
                assertCollected(evidence.references)
                assertFailsWith<IllegalStateException> { evidence.session.frame(Constraints.fixed(320, 180)) }
                evidence.session.close()
            }
        }
    }

    @Test
    fun shrinkingAStillLiveTreeReleasesRemovedChildrenBeforeTerminalClose() {
        for (horizontal in listOf(true, false)) {
            val evidence = retiredOwners(horizontal)
            try {
                assertCollected(listOf(evidence.retired))
                evidence.session.frame(Constraints.fixed(320, 180))
            } finally {
                evidence.session.close()
            }
            assertCollected(evidence.references)
        }
    }

    @Test
    fun foreignThreadCannotUseTheClosedOrActiveLinearOwner() {
        val evidence = retiredOwners(true)
        try {
            val failure = AtomicReference<Throwable?>()
            thread(name = "linear-foreign-owner") {
                failure.set(runCatching { evidence.session.frame(Constraints.fixed(320, 180)) }.exceptionOrNull())
            }.join()
            assertTrue(failure.get() is IllegalStateException)
            evidence.session.frame(Constraints.fixed(320, 180))
        } finally {
            evidence.session.close()
        }
    }

    private fun closedOwners(horizontal: Boolean, fails: Boolean): Evidence {
        val source = ReleaseSource()
        val probe = TestProbe(failingMeasureTag = if (fails) TestProbe.ProbeId("0") else null)
        val session = session(horizontal, source, probe)
        session.attach()
        val outcome = runCatching { session.frame(Constraints.fixed(320, 180)) }
        assertEquals(fails, outcome.isFailure)
        val observer = checkNotNull(source.observerReference)
        val retired = WeakReference(probe.created.first())
        probe.created.clear()
        session.close()
        assertEquals(1, source.closes)
        assertEquals(null, source.observer)
        return Evidence(session, retired, listOf(retired, WeakReference(source), WeakReference(probe), observer))
    }

    private fun retiredOwners(horizontal: Boolean): Evidence {
        val source = ReleaseSource()
        val probe = TestProbe()
        val session = session(horizontal, source, probe)
        session.attach()
        session.frame(Constraints.fixed(320, 180))
        val observer = WeakReference(checkNotNull(source.observer))
        val retired = WeakReference(probe.created.first())
        source.publish(0)
        session.frame(Constraints.fixed(320, 180))
        probe.created.clear()
        return Evidence(session, retired, listOf(retired, WeakReference(source), WeakReference(probe), observer))
    }

    private fun session(horizontal: Boolean, source: ReleaseSource, probe: TestProbe): RuntimeUiSession =
        createRuntimeUiSession {
            evaluateComponentTree {
                Observe(source) { count ->
                    if (horizontal) Row { repeat(count) { element(probe.element(TestProbe.ProbeId(it.toString()))) } }
                    else Column { repeat(count) { element(probe.element(TestProbe.ProbeId(it.toString()))) } }
                }
            }
        }

    // Collection occurs after each creating stack frame returns, without measuring GC or allocating pressure buffers.
    private fun assertCollected(references: List<WeakReference<*>>) {
        repeat(20) {
            if (references.all { it.get() == null }) return
            System.gc()
            Thread.sleep(10)
        }
        assertTrue(references.all { it.get() == null }, "Linear measurement retained a retired owner or callback.")
    }

    /**
     * Closed or live carrier kept reachable while checking retired owners; observations are weak only.
     */
    private data class Evidence(
        val session: RuntimeUiSession,
        val retired: WeakReference<*>,
        val references: List<WeakReference<*>>,
    )

    /**
     * One real source subscription, whose callback must be released by the session rather than by test cleanup.
     */
    private class ReleaseSource : StateSource<Int> {
        private var snapshot = StateSnapshot(StateRevision(0), 16)
        /**
         * Records the actual callback weakly before a failing frame releases its subscription.
         */
        var observerReference: WeakReference<*>? = null
            private set
        var observer: ((StateSnapshot<Int>) -> Unit)? = null
        var closes: Int = 0

        override fun subscribe(observer: (StateSnapshot<Int>) -> Unit): StateSubscription<Int> {
            check(this.observer == null)
            observerReference = WeakReference(observer)
            this.observer = observer
            return StateSubscription(snapshot) {
                this.observer = null
                closes += 1
            }
        }

        /**
         * Publishes one actual revision; callbacks only enqueue into the real retained session.
         */
        fun publish(count: Int) {
            snapshot = StateSnapshot(StateRevision(snapshot.revision.value + 1), count)
            observer?.invoke(snapshot)
        }
    }
}
