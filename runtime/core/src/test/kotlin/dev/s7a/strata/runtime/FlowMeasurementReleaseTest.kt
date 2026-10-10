@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.component.FlowRow
import dev.s7a.strata.component.Observe
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
 * Actual owner collection after unwind, with live and terminal session handles deliberately kept reachable.
 */
internal class FlowMeasurementReleaseTest {
    @Test
    fun normalAndExceptionalCloseReleaseChildSourceObserverAndContentOwners() {
        for (fails in listOf(false, true)) {
            val evidence = owners(fails, shrink = false)
            assertCollected(evidence.references)
            assertFailsWith<IllegalStateException> { evidence.session.frame(BOUNDS) }
            evidence.session.close()
        }
    }

    @Test
    fun shrinkingTheStillLiveFlowRowReleasesRemovedChildrenBeforeTerminalClose() {
        val evidence = owners(fails = false, shrink = true)
        try {
            assertCollected(listOf(evidence.retired))
            evidence.session.frame(BOUNDS)
        } finally {
            evidence.session.close()
        }
        assertCollected(evidence.references)
    }

    @Test
    fun foreignThreadCannotConsumeAnActiveFlowRowOwner() {
        val evidence = owners(fails = false, shrink = true)
        try {
            val failure = AtomicReference<Throwable?>()
            thread(name = "flow-foreign-owner") {
                failure.set(runCatching { evidence.session.frame(BOUNDS) }.exceptionOrNull())
            }.join()
            assertTrue(failure.get() is IllegalStateException)
            evidence.session.frame(BOUNDS)
        } finally {
            evidence.session.close()
        }
    }

    private fun owners(fails: Boolean, shrink: Boolean): Evidence {
        val source = ReleaseSource()
        val probe = TestProbe(failingMeasureTag = if (fails) TestProbe.ProbeId("0") else null)
        val session = createRuntimeUiSession {
            evaluateComponentTree {
                Observe(source) { count ->
                    FlowRow(horizontalSpacing = 1, verticalSpacing = 1) {
                        repeat(count) { element(probe.element(TestProbe.ProbeId(it.toString()))) }
                    }
                }
            }
        }
        session.attach()
        assertEquals(fails, runCatching { session.frame(BOUNDS) }.isFailure)
        val observer = checkNotNull(source.observerReference)
        val retired = WeakReference(probe.created.first())
        if (shrink) {
            source.publish(0)
            session.frame(BOUNDS)
        } else {
            session.close()
            assertEquals(1, source.closes)
            assertEquals(null, source.observer)
        }
        probe.created.clear()
        return Evidence(session, retired, listOf(retired, WeakReference(source), WeakReference(probe), observer))
    }

    // Bounded collection after constructing frames return; no timed GC or artificial memory pressure.
    private fun assertCollected(references: List<WeakReference<*>>) {
        repeat(20) {
            if (references.all { it.get() == null }) return
            System.gc()
            Thread.sleep(10)
        }
        assertTrue(references.all { it.get() == null }, "Flow measurement retained a retired owner or callback.")
    }

    /**
     * Strong carrier holding only the session and weak observations during collection.
     */
    private data class Evidence(
        val session: RuntimeUiSession,
        val retired: WeakReference<*>,
        val references: List<WeakReference<*>>,
    )

    /**
     * Actual subscribed source with a once-only callback release, independent of any tree cache.
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
         * Publishes one real revision; the callback only enqueues into the actual session.
         */
        fun publish(count: Int) {
            snapshot = StateSnapshot(StateRevision(snapshot.revision.value + 1), count)
            observer?.invoke(snapshot)
        }
    }

    private companion object {
        val BOUNDS = Constraints(maxWidth = 7, maxHeight = 180)
    }
}
