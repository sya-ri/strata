package dev.s7a.strata.runtime.minecraft.canvas

import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Checks guarded portable-set removal against independent request/query tables and physical acknowledgement cutoffs.
 * Each set uses a distinct owner so the full device bound is exercised without changing per-owner generation limits.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class NativeGuiPollingTest {
    @Test
    fun releaseCallbacksPreserveZeroOneAndMaximumSetTraversal() {
        listOf(0, 1, 64).forEach { count ->
            NativeGuiResourceFixture().use { fixture ->
                val sets = List(count) { fixture.initialized(ownerId = fixture.gui.createOwnerId()) }
                val events = ArrayList<Event>()
                fixture.allocations.forEachIndexed { index, resource ->
                    resource.onClose = {
                        events.add(Event(index, Action.Close))
                        if (index + 1 < count) fixture.gui.release(sets[index + 1])
                        assertThrows(IllegalStateException::class.java) { fixture.gui.createOwnerId() }
                        assertThrows(IllegalStateException::class.java) { fixture.device.poll() }
                    }
                    resource.onDestruction = { events.add(Event(index, Action.Query)) }
                }
                fixture.driver.signalAll()
                sets.firstOrNull()?.let(fixture.gui::release)
                if (count == 0) fixture.device.poll()

                val expected = (0 until count).flatMap { listOf(Event(it, Action.Close), Event(it, Action.Query)) }
                assertEquals(expected, events)
                assertEquals(0, fixture.gui.retainedSetCount())
                assertTrue(fixture.allocations.all { it.closeCalls == 1 && it.destructionPolls == 1 })
                fixture.device.poll()
                assertEquals(expected, events)
            }
        }
    }

    @Test
    fun asynchronousSetsContinueAfterMultiplePhysicalFailuresAndKeepOnlyUnacknowledgedPermits() {
        NativeGuiResourceFixture().use { fixture ->
            val sets = List(64) { fixture.initialized(ownerId = fixture.gui.createOwnerId()) }
            val queries = ArrayList<Int>()
            fixture.allocations.forEachIndexed { index, resource ->
                resource.destroyOnClose = false
                resource.onClose = { if (index + 1 < sets.size) fixture.gui.release(sets[index + 1]) }
                resource.onDestruction = { queries.add(index) }
            }
            fixture.driver.signalAll()
            fixture.gui.release(sets.first())
            assertEquals((0 until 64).toList(), queries)
            assertEquals(64, fixture.gui.retainedSetCount())
            assertTrue(fixture.allocations.all { it.closeCalls == 1 && it.releaseAccepted })

            val firstFailure = IllegalArgumentException("first portable query")
            val secondFailure = IllegalStateException("second portable query")
            fixture.allocations[1].destructionFailure = firstFailure
            fixture.allocations[3].destructionFailure = secondFailure
            fixture.allocations.forEachIndexed { index, resource -> resource.destroyed = index % 2 == 0 }
            queries.clear()
            val failure = assertThrows(IllegalArgumentException::class.java) { fixture.device.poll() }
            assertSame(firstFailure, failure)
            assertEquals(listOf(secondFailure), failure.suppressed.toList())
            assertEquals((0 until 64).toList(), queries)
            assertEquals(32, fixture.gui.retainedSetCount())

            fixture.allocations.forEach { resource ->
                resource.destroyed = true
                resource.destructionFailure = null
            }
            queries.clear()
            fixture.device.poll()
            assertEquals((0 until 64).filter { it % 2 == 1 }, queries)
            assertEquals(0, fixture.gui.retainedSetCount())
            assertTrue(fixture.allocations.all { it.closeCalls == 1 })
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
