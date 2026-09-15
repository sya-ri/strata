package dev.s7a.strata.runtime

import dev.s7a.strata.node.StateObserverNode
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.diagnostics.UiRenderOperation
import dev.s7a.strata.runtime.platform.IdentityMap
import dev.s7a.strata.runtime.platform.identitySet
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Checks identity ownership and detached diagnostic collection contracts on JVM and JavaScript.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class PortableRegistryTest {
    @Test
    fun referenceSetIteratorRemovesOnlyTheReturnedIdentity() {
        val first = listOf(1)
        val second = listOf(1)
        val identities = identitySet<List<Int>>()
        identities.addAll(listOf(first, second))
        assertEquals(2, identities.size)
        val iterator = identities.iterator()
        assertFailsWith<IllegalStateException> { iterator.remove() }
        val removed = iterator.next()
        iterator.remove()
        assertFalse(removed in identities)
        assertFailsWith<IllegalStateException> { iterator.remove() }
        assertTrue(iterator.next() in identities)
        iterator.remove()
        assertTrue(identities.isEmpty())
        assertFalse(iterator.hasNext())
        assertFailsWith<NoSuchElementException> { iterator.next() }
    }

    @Test
    fun equalSourcesAndObserversKeepIndependentSubscriptionsAndCleanup() {
        val firstSource = EqualSource(1)
        val secondSource = EqualSource(2)
        val first = EqualObserver(firstSource)
        val second = EqualObserver(secondSource)
        ObservedSourceRegistry().use { registry ->
            registry.synchronize(first)
            registry.synchronize(second)
            assertEquals(2, registry.activeSubscriptions)
            assertEquals(listOf(1), first.values)
            assertEquals(listOf(2), second.values)
            firstSource.publish(3, 1)
            secondSource.publish(4, 1)
            registry.capture()
            registry.commit()
            registry.finishFrame()
            assertEquals(listOf(3), first.values)
            assertEquals(listOf(4), second.values)
            registry.remove(first)
            assertEquals(1, registry.activeSubscriptions)
            assertEquals(1, firstSource.releases)
            assertEquals(0, secondSource.releases)
            secondSource.publish(5, 2)
            registry.capture()
            registry.commit()
            registry.finishFrame()
            assertEquals(listOf(3), first.values)
            assertEquals(listOf(5), second.values)
        }
        assertEquals(1, firstSource.releases)
        assertEquals(1, secondSource.releases)
    }

    @Test
    fun equalKeysRemainIndependentAndSnapshotsSurviveRelease() {
        val first = listOf(1)
        val second = listOf(1)
        val registry = IdentityMap<List<Int>, String>()
        registry[first] = "first"
        registry[second] = "second"
        val values = registry.values
        assertEquals("first", registry.remove(first))
        assertNull(registry[first])
        assertEquals("second", registry[second])
        registry.clear()
        assertEquals(emptyList(), registry.values)
        assertEquals(setOf("first", "second"), values.toSet())
    }

    @Test
    fun diagnosticMapsRemainDetachedAfterCountersReset() {
        val counts = RenderWorkCounts()
        counts.record(UiRenderMetric.FrameAttempt, UiRenderOperation.Frame)
        val totals = counts.totals()
        val operations = counts.operations()
        val frame = operations.getValue(UiRenderOperation.Frame)
        counts.clear()
        assertEquals(1L, totals[UiRenderMetric.FrameAttempt])
        assertEquals(1L, frame[UiRenderMetric.FrameAttempt])
        assertEquals(0L, counts.totals()[UiRenderMetric.FrameAttempt])
        assertEquals(totals.toMap(), totals)
        assertEquals(totals.toMap().hashCode(), totals.hashCode())
    }

    /**
     * Synchronous source fixture whose value equality deliberately ignores subscription identity.
     */
    private class EqualSource(
        private val initial: Int,
    ) : StateSource<Int> {
        private var observer: ((StateSnapshot<Int>) -> Unit)? = null
        var releases = 0
            private set

        override fun subscribe(observer: (StateSnapshot<Int>) -> Unit): StateSubscription<Int> {
            check(this.observer == null)
            this.observer = observer
            return StateSubscription(StateSnapshot(StateRevision(0), initial)) {
                this.observer = null
                releases += 1
            }
        }

        fun publish(
            value: Int,
            revision: Long,
        ) {
            checkNotNull(observer)(StateSnapshot(StateRevision(revision), value))
        }

        override fun equals(other: Any?): Boolean = other is EqualSource

        override fun hashCode(): Int = 0
    }

    /**
     * Observer fixture whose equality must not merge independently retained consumers.
     */
    private class EqualObserver(
        source: StateSource<Int>,
    ) : StateObserverNode {
        override val observedSources: List<StateSource<*>> = listOf(source)
        var values: List<Any?> = emptyList()
            private set

        override fun commitObservedValues(values: List<Any?>) {
            this.values = values
        }

        override fun equals(other: Any?): Boolean = other is EqualObserver

        override fun hashCode(): Int = 0
    }
}
