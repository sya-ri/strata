package dev.s7a.strata.runtime.minecraft.canvas

import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Preserves snapshot-era callback eligibility, ordered physical queries and owner accounting during guarded polling.
 * The expected event tables are independent of collection traversal and use real retained attachments with CPU probes.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class NativeCanvasPollingTest {
    @Test
    fun emptyTargetGuardStillClosesOwnersWithEagerCallbackEligibility() {
        NativeCanvasFixture().use { fixture ->
            val first = fixture.tree()
            val second = fixture.tree()
            fixture.producers[0].onClose = { second.close() }

            first.close()

            assertEquals(0, fixture.device.retainedTargetCount())
            assertEquals(1, fixture.producers[0].closeCalls)
            assertEquals(0, fixture.producers[1].closeCalls)
            fixture.device.poll()
            assertEquals(1, fixture.producers[1].closeCalls)
            repeat(8) { fixture.device.poll() }
            assertTrue(fixture.producers.all { it.closeCalls == 1 })
        }
    }

    @Test
    fun targetRetirementCallbacksPreserveEveryRemainingMemberAndQueryOrder() {
        listOf(0, 1, 64).forEach { count ->
            NativeCanvasFixture().use { fixture ->
                val trees = List(count) { fixture.tree() }
                trees.forEach { tree -> fixture.device.cancel(fixture.prepare(tree)) }
                val events = ArrayList<Event>()
                fixture.driver.targets.forEachIndexed { index, target ->
                    target.onClose = {
                        events.add(Event(index, Action.Close))
                        if (index + 1 < count) trees[index + 1].close()
                        assertThrows(IllegalStateException::class.java) { fixture.device.poll() }
                    }
                    target.onDestruction = { events.add(Event(index, Action.Query)) }
                }
                fixture.driver.signalAll()
                trees.firstOrNull()?.close()
                if (count == 0) fixture.device.poll()

                val expected = (0 until count).flatMap { listOf(Event(it, Action.Close), Event(it, Action.Query)) }
                assertEquals(expected, events)
                assertEquals(0, fixture.device.retainedTargetCount())
                assertTrue(fixture.driver.targets.all { it.closeCalls == 1 && it.destructionPolls == 1 })
                assertTrue(fixture.producers.all { it.closeCalls == 1 })
                fixture.device.poll()
                assertEquals(expected, events)
            }
        }
    }

    @Test
    fun asynchronousTargetsReleaseOnlyAcknowledgedPermitsAndAggregateIndependentFailures() {
        NativeCanvasFixture().use { fixture ->
            fixture.driver.destroyOnClose = false
            val trees = List(64) { fixture.tree() }
            val presentations = trees.map { tree -> fixture.prepare(tree).also(fixture.device::cancel) }
            val oldCapture = presentations.last().capture()
            val queries = ArrayList<Int>()
            fixture.driver.targets.forEachIndexed { index, target ->
                target.onClose = { if (index + 1 < trees.size) trees[index + 1].close() }
                target.onDestruction = { queries.add(index) }
            }
            fixture.driver.signalAll()
            trees.first().close()
            assertEquals((0 until 64).toList(), queries)
            assertEquals(64, fixture.device.retainedTargetCount())
            assertTrue(fixture.producers.all { it.closeCalls == 0 })
            assertTrue(fixture.driver.targets.all { it.releaseAccepted && it.closeCalls == 1 })

            val firstFailure = IllegalArgumentException("first physical query")
            val secondFailure = IllegalStateException("second physical query")
            fixture.driver.targets[1].destructionFailure = firstFailure
            fixture.driver.targets[3].destructionFailure = secondFailure
            fixture.driver.targets.forEachIndexed { index, target -> target.destroyed = index % 2 == 0 }
            queries.clear()
            val failure = assertThrows(IllegalArgumentException::class.java) { fixture.device.poll() }
            assertSame(firstFailure, failure)
            assertEquals(listOf(secondFailure), failure.suppressed.toList())
            assertEquals((0 until 64).toList(), queries)
            assertEquals(32, fixture.device.retainedTargetCount())
            fixture.producers.forEachIndexed { index, producer -> assertEquals(if (index % 2 == 0) 1 else 0, producer.closeCalls) }

            fixture.driver.targets.forEach { target ->
                target.destroyed = true
                target.destructionFailure = null
            }
            queries.clear()
            fixture.device.poll()
            assertEquals((0 until 64).filter { it % 2 == 1 }, queries)
            assertEquals(0, fixture.device.retainedTargetCount())
            assertTrue(fixture.driver.targets.all { it.closeCalls == 1 })
            assertTrue(fixture.producers.all { it.closeCalls == 1 })
            assertEquals(oldCapture, presentations.last().capture())
        }
    }

    private enum class Action {
        Close,
        Query,
    }

    private data class Event(
        val index: Int,
        val action: Action,
    )
}
