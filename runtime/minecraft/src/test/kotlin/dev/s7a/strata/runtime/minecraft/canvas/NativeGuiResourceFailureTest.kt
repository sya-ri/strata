package dev.s7a.strata.runtime.minecraft.canvas

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies partial portable uploads, failed consumers, and deferred destruction retain ownership until a safe terminal boundary.
 *
 * Independent deterministic failure probes verify suppression and permits without substituting for loaded GPU tests.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class NativeGuiResourceFailureTest {
    @Test
    fun failedInitializationFenceQuarantinesEvenEmptyOrPartiallyAllocatedSets() {
        listOf(0, 1).forEach { count ->
            NativeGuiResourceFixture().use { fixture ->
                val set = fixture.gui.reserve(fixture.owner, List(count) { IntSize(2, 2) })
                repeat(count) { fixture.add(set) }
                val failure = IllegalArgumentException("initialization fence")
                fixture.driver.nextFenceFailure = failure
                assertSame(failure, assertThrows(IllegalArgumentException::class.java) { fixture.gui.seal(set) })
                fixture.gui.release(set)
                fixture.driver.signalAll()
                repeat(100) { fixture.device.poll() }
                assertEquals(1, fixture.gui.retainedSetCount())
                assertTrue(fixture.allocations.all { it.closeCalls == 0 })
                assertEquals(0, fixture.driver.finishCalls)
                fixture.device.closeAfterGuiDiscarded()
                assertEquals(0, fixture.gui.retainedSetCount())
                assertTrue(fixture.allocations.all { it.closeCalls == 1 })
                assertEquals(1, fixture.driver.finishCalls)
                assertEquals(1, fixture.driver.drainCalls)
            }
        }
    }

    @Test
    fun partialUploadFailureKeepsItsPrimaryExceptionWhenInitializationFenceAlsoFails() {
        NativeGuiResourceFixture().use { fixture ->
            val set = fixture.gui.reserve(fixture.owner, listOf(IntSize(2, 2), IntSize(2, 2)))
            val resource = fixture.add(set)
            val uploadFailure = IllegalArgumentException("partial upload")
            val fenceFailure = IllegalStateException("initialization fence")
            fixture.driver.nextFenceFailure = fenceFailure
            val failures = CanvasFailures(uploadFailure)
            failures.attempt { fixture.gui.seal(set) }
            failures.attempt { fixture.gui.release(set) }
            assertSame(uploadFailure, assertThrows(IllegalArgumentException::class.java) { failures.throwIfPresent() })
            assertEquals(listOf(fenceFailure), uploadFailure.suppressed.toList())
            repeat(100) { fixture.device.poll() }
            assertEquals(0, resource.closeCalls)
            assertEquals(1, fixture.gui.retainedSetCount())
            fixture.device.closeAfterGuiDiscarded()
            assertEquals(1, resource.closeCalls)
            assertEquals(0, fixture.gui.retainedSetCount())
        }
    }

    @Test
    fun failedInitializationCannotFreeAnotherPermitForTheSamePresenter() {
        NativeGuiResourceFixture().use { fixture ->
            repeat(3) {
                val set = fixture.gui.reserve(fixture.owner, listOf(IntSize(2, 2)))
                fixture.add(set)
                fixture.driver.nextFenceFailure = IllegalStateException("initialization fence")
                assertThrows(IllegalStateException::class.java) { fixture.gui.seal(set) }
                fixture.gui.release(set)
            }
            repeat(100) { fixture.device.poll() }
            assertThrows(IllegalStateException::class.java) { fixture.initialized() }
            assertEquals(3, fixture.allocations.size)
            assertEquals(3, fixture.gui.retainedSetCount())
            assertTrue(fixture.allocations.all { it.closeCalls == 0 })
            fixture.device.closeAfterGuiDiscarded()
            assertEquals(0, fixture.gui.retainedSetCount())
            assertTrue(fixture.allocations.all { it.closeCalls == 1 })
        }
    }

    @Test
    fun failedConsumerFenceQuarantinesEveryPendingPortableGeneration() {
        NativeGuiResourceFixture().use { fixture ->
            val sets = List(2) { fixture.initialized() }
            sets.forEach { set ->
                fixture.gui.beginUse(set)
                fixture.gui.queued(set)
                fixture.gui.endUse(set)
                fixture.gui.release(set)
            }
            val failure = IllegalArgumentException("GUI fence")
            fixture.driver.nextFenceFailure = failure
            assertSame(failure, assertThrows(IllegalArgumentException::class.java) { fixture.device.consumed() })
            fixture.driver.signalAll()
            repeat(100) { fixture.device.poll() }
            assertEquals(2, fixture.driver.fences.size)
            assertEquals(2, fixture.gui.retainedSetCount())
            assertTrue(fixture.allocations.all { it.closeCalls == 0 })
            fixture.device.closeAfterGuiDiscarded()
            assertEquals(0, fixture.gui.retainedSetCount())
            assertTrue(fixture.allocations.all { it.closeCalls == 1 })
        }
    }

    @Test
    fun failedPortableOnlyGuiQuarantinesWithoutSubmittingACompletionFence() {
        NativeGuiResourceFixture().use { fixture ->
            val set = fixture.initialized()
            val resource = fixture.allocations.single()
            fixture.gui.beginUse(set)
            fixture.gui.queued(set)
            fixture.gui.endUse(set)
            fixture.gui.release(set)
            fixture.device.failedGui()
            fixture.driver.signalAll()
            repeat(100) { fixture.device.poll() }
            assertEquals(1, fixture.driver.fences.size)
            assertEquals(0, resource.closeCalls)
            assertEquals(1, fixture.gui.retainedSetCount())
            fixture.device.closeAfterGuiDiscarded()
            assertEquals(1, resource.closeCalls)
            assertEquals(0, fixture.gui.retainedSetCount())
        }
    }

    @Test
    fun failedInitializationQueryRetainsItsFenceWhileIndependentRetirementContinues() {
        NativeGuiResourceFixture().use { fixture ->
            val first = fixture.initialized()
            val second = fixture.initialized()
            val failure = IllegalArgumentException("initialization query")
            fixture.driver.fences[0].pollFailure = failure
            fixture.driver.fences[1].signalled = true
            assertSame(failure, assertThrows(IllegalArgumentException::class.java) { fixture.gui.release(first) })
            assertSame(failure, assertThrows(IllegalArgumentException::class.java) { fixture.gui.release(second) })
            assertEquals(0, fixture.allocations[0].closeCalls)
            assertEquals(1, fixture.allocations[1].closeCalls)
            assertEquals(1, fixture.gui.retainedSetCount())
            fixture.driver.fences[0].pollFailure = null
            fixture.driver.fences[0].signalled = true
            fixture.device.poll()
            assertEquals(1, fixture.allocations[0].closeCalls)
            assertEquals(0, fixture.gui.retainedSetCount())
        }
    }

    @Test
    fun fenceReleaseFailureDoesNotSkipIndependentResourceReleaseOrRepeatTheFenceClose() {
        NativeGuiResourceFixture().use { fixture ->
            val set = fixture.initialized(count = 2)
            val failure = IllegalArgumentException("fence close")
            val fence = fixture.driver.fences.single()
            fence.signalled = true
            fence.closeFailure = failure
            assertSame(failure, assertThrows(IllegalArgumentException::class.java) { fixture.gui.release(set) })
            assertTrue(fixture.allocations.all { it.closeCalls == 1 })
            assertEquals(0, fixture.gui.retainedSetCount())
            fixture.device.poll()
            assertEquals(1, fence.closeCalls)
        }
    }

    @Test
    fun failedResourceCloseRetainsTheSetAndRetriesOnlyOnceAtTerminalWithExactSuppression() {
        val fixture = NativeGuiResourceFixture()
        val set = fixture.initialized(count = 2)
        val resource = fixture.allocations.first()
        val initial = IllegalArgumentException("first resource close")
        val retry = IllegalStateException("terminal resource close")
        resource.closeFailure = initial
        fixture.driver.signalAll()
        assertSame(initial, assertThrows(IllegalArgumentException::class.java) { fixture.gui.release(set) })
        assertTrue(fixture.allocations.all { it.closeCalls == 1 })
        assertEquals(1, fixture.gui.retainedSetCount())
        repeat(100) { fixture.device.poll() }
        assertEquals(1, resource.closeCalls)
        resource.closeFailure = retry
        assertSame(initial, assertThrows(IllegalArgumentException::class.java) { fixture.device.closeAfterGuiDiscarded() })
        assertEquals(listOf(retry), initial.suppressed.toList())
        assertSame(initial, assertThrows(IllegalArgumentException::class.java) { fixture.device.closeAfterGuiDiscarded() })
        assertEquals(2, resource.closeCalls)
        assertEquals(1, fixture.allocations[1].closeCalls)
        assertEquals(1, fixture.gui.retainedSetCount())
        assertEquals(1, fixture.driver.finishCalls)
        assertEquals(1, fixture.driver.drainCalls)
    }

    @Test
    fun terminalRetryCanReleaseAFailedResourceOnlyAfterItsPhysicalAcknowledgement() {
        NativeGuiResourceFixture().use { fixture ->
            val set = fixture.initialized()
            val resource = fixture.allocations.single()
            val failure = IllegalArgumentException("resource close")
            resource.destroyOnClose = false
            resource.closeFailure = failure
            fixture.driver.signalAll()
            assertSame(failure, assertThrows(IllegalArgumentException::class.java) { fixture.gui.release(set) })
            assertEquals(1, resource.closeCalls)
            assertEquals(1, fixture.gui.retainedSetCount())
            resource.closeFailure = null
            fixture.device.closeAfterGuiDiscarded()
            assertEquals(2, resource.closeCalls)
            assertEquals(1, resource.destructionPolls)
            assertTrue(resource.destroyed)
            assertEquals(0, fixture.gui.retainedSetCount())
        }
    }

    @Test
    fun destructionQueryFailureRetainsThePermitWithoutRepeatingSuccessfulClose() {
        NativeGuiResourceFixture().use { fixture ->
            val set = fixture.initialized()
            val resource = fixture.allocations.single()
            val failure = IllegalArgumentException("destruction query")
            resource.destroyOnClose = false
            resource.destructionFailure = failure
            fixture.driver.signalAll()
            assertSame(failure, assertThrows(IllegalArgumentException::class.java) { fixture.gui.release(set) })
            repeat(3) { assertSame(failure, assertThrows(IllegalArgumentException::class.java) { fixture.device.poll() }) }
            assertEquals(1, resource.closeCalls)
            assertEquals(1, fixture.gui.retainedSetCount())
            resource.destructionFailure = null
            repeat(100) { fixture.device.poll() }
            assertEquals(1, fixture.gui.retainedSetCount())
            resource.destroyed = true
            fixture.device.poll()
            assertEquals(0, fixture.gui.retainedSetCount())
            assertEquals(1, resource.closeCalls)
        }
    }

    @Test
    fun terminalDrainMustAcknowledgeEveryAsynchronousResourceAndFailureRemainsSticky() {
        val fixture = NativeGuiResourceFixture()
        fixture.driver.completeDestructionOnDrain = false
        fixture.initialized()
        val resource = fixture.allocations.single()
        resource.destroyOnClose = false
        val failure = assertThrows(IllegalStateException::class.java) { fixture.device.closeAfterGuiDiscarded() }
        assertEquals(1, fixture.gui.retainedSetCount())
        assertEquals(1, resource.closeCalls)
        assertEquals(1, fixture.driver.finishCalls)
        assertEquals(1, fixture.driver.drainCalls)
        resource.destroyed = true
        assertSame(failure, assertThrows(IllegalStateException::class.java) { fixture.device.closeAfterGuiDiscarded() })
        assertEquals(1, fixture.gui.retainedSetCount())
        assertEquals(1, resource.closeCalls)
        assertEquals(1, resource.destructionPolls)
    }

    @Test
    fun terminalDrainFailurePreservesPrimaryAndAcknowledgesIndependentSynchronousResources() {
        val fixture = NativeGuiResourceFixture()
        fixture.initialized()
        fixture.initialized()
        fixture.allocations.first().destroyOnClose = false
        val failure = IllegalArgumentException("retirement drain")
        fixture.driver.drainFailure = failure
        assertSame(failure, assertThrows(IllegalArgumentException::class.java) { fixture.device.closeAfterGuiDiscarded() })
        assertEquals(1, failure.suppressed.size)
        assertTrue(failure.suppressed.single() is IllegalStateException)
        assertEquals(1, fixture.gui.retainedSetCount())
        assertTrue(fixture.allocations.all { it.closeCalls == 1 })
        assertTrue(fixture.allocations[1].destroyed)
        assertSame(failure, assertThrows(IllegalArgumentException::class.java) { fixture.device.closeAfterGuiDiscarded() })
        assertEquals(1, fixture.driver.finishCalls)
        assertEquals(1, fixture.driver.drainCalls)
    }

    @Test
    fun failedGlobalFinishRetainsInitializedAndAbandonedPartialStorageWithoutAnyRelease() {
        val fixture = NativeGuiResourceFixture()
        fixture.initialized()
        val abandoned = fixture.gui.reserve(fixture.owner, listOf(IntSize(2, 2), IntSize(2, 2)))
        fixture.add(abandoned)
        val failure = IllegalArgumentException("finish")
        fixture.driver.finishFailure = failure
        assertSame(failure, assertThrows(IllegalArgumentException::class.java) { fixture.device.closeAfterGuiDiscarded() })
        repeat(100) { fixture.device.poll() }
        assertEquals(2, fixture.gui.retainedSetCount())
        assertTrue(fixture.allocations.all { it.closeCalls == 0 })
        assertEquals(
            0,
            fixture.driver.fences
                .single()
                .closeCalls,
        )
        assertEquals(0, fixture.driver.drainCalls)
        assertThrows(IllegalStateException::class.java) { fixture.gui.createOwnerId() }
        assertSame(failure, assertThrows(IllegalArgumentException::class.java) { fixture.device.closeAfterGuiDiscarded() })
        assertEquals(1, fixture.driver.finishCalls)
    }

    @Test
    fun terminalFinishCanReleaseAbandonedUnsealedInitializationWithoutInventingAFence() {
        NativeGuiResourceFixture().use { fixture ->
            val abandoned = fixture.gui.reserve(fixture.owner, listOf(IntSize(2, 2), IntSize(2, 2)))
            val resource = fixture.add(abandoned)
            fixture.gui.release(abandoned)
            assertEquals(0, resource.closeCalls)
            assertEquals(0, fixture.driver.fences.size)
            fixture.device.closeAfterGuiDiscarded()
            assertEquals(1, resource.closeCalls)
            assertEquals(0, fixture.gui.retainedSetCount())
            assertEquals(1, fixture.driver.finishCalls)
            assertEquals(1, fixture.driver.drainCalls)
        }
    }
}
