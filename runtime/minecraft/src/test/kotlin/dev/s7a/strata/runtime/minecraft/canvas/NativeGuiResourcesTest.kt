package dev.s7a.strata.runtime.minecraft.canvas

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Verifies portable generation reservation, immutable reuse, extraction pins, and separate GUI completion lifetimes.
 *
 * All native operations use deterministic nonblocking probes; loaded tests independently verify real textures and consumers.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class NativeGuiResourcesTest {
    @Test
    fun reservationSurvivesUnsealedReleaseAndInitializationUntilItsFenceSignals() {
        NativeGuiResourceFixture().use { fixture ->
            val set = fixture.gui.reserve(fixture.owner, listOf(IntSize(2, 2)))
            assertEquals(1, fixture.gui.retainedSetCount())
            val resource = fixture.add(set)
            fixture.gui.release(set)
            repeat(100) { fixture.device.poll() }
            assertEquals(0, fixture.driver.fences.size)
            assertEquals(0, resource.closeCalls)
            fixture.gui.seal(set)
            repeat(100) { fixture.device.poll() }
            assertEquals(0, resource.closeCalls)
            assertEquals(0, fixture.driver.finishCalls)
            fixture.driver.signalAll()
            fixture.device.poll()
            assertEquals(1, resource.closeCalls)
            assertTrue(resource.destroyed)
            assertEquals(0, fixture.gui.retainedSetCount())
            fixture.gui.release(set)
            assertThrows(IllegalStateException::class.java) { fixture.gui.beginUse(set) }
        }
    }

    @Test
    fun copiedExtentListBoundsTransfersAndSealedGenerationRejectsMutation() {
        NativeGuiResourceFixture().use { fixture ->
            val extents = arrayListOf(IntSize(2, 2), IntSize(3, 1))
            val set = fixture.gui.reserve(fixture.owner, extents)
            extents.clear()
            fixture.add(set)
            fixture.add(set)
            val excess = NativeGuiResourceFixture.Resource()
            assertThrows(IllegalStateException::class.java) { fixture.gui.add(set, excess) }
            assertThrows(IllegalStateException::class.java) { fixture.gui.beginUse(set) }
            fixture.gui.seal(set)
            assertThrows(IllegalStateException::class.java) { fixture.gui.seal(set) }
            assertThrows(IllegalStateException::class.java) { fixture.gui.add(set, excess) }
            fixture.gui.beginUse(set)
            fixture.gui.endUse(set)
            fixture.gui.release(set)
            fixture.driver.signalAll()
            fixture.device.poll()
            assertTrue(fixture.allocations.all { it.closeCalls == 1 })
            assertEquals(0, excess.closeCalls)
        }
    }

    @Test
    fun partialAndEmptyInitializationCanRetireButCannotBorrowMissingLayers() {
        NativeGuiResourceFixture().use { fixture ->
            val partial = fixture.gui.reserve(fixture.owner, listOf(IntSize(2, 2), IntSize(2, 2)))
            val resource = fixture.add(partial)
            fixture.gui.seal(partial)
            assertThrows(IllegalStateException::class.java) { fixture.gui.beginUse(partial) }
            fixture.gui.release(partial)
            val empty = fixture.initialized(count = 0)
            fixture.gui.beginUse(empty)
            fixture.gui.endUse(empty)
            fixture.gui.release(empty)
            assertEquals(2, fixture.gui.retainedSetCount())
            assertEquals(0, resource.closeCalls)
            fixture.driver.signalAll()
            fixture.device.poll()
            assertEquals(0, fixture.gui.retainedSetCount())
            assertEquals(1, resource.closeCalls)
            assertTrue(fixture.driver.fences.all { it.closeCalls == 1 })
        }
    }

    @Test
    fun queuedUseSurvivesCacheReleaseAndWaitsForTheActualConsumerFence() {
        NativeGuiResourceFixture().use { fixture ->
            val set = fixture.initialized()
            val resource = fixture.allocations.single()
            fixture.gui.beginUse(set)
            fixture.gui.release(set)
            assertThrows(IllegalStateException::class.java) { fixture.gui.beginUse(set) }
            fixture.driver.signalAll()
            repeat(100) { fixture.device.poll() }
            assertEquals(0, resource.closeCalls)
            fixture.gui.queued(set)
            fixture.gui.endUse(set)
            repeat(100) { fixture.device.poll() }
            assertEquals(1, fixture.driver.fences.size)
            assertEquals(0, resource.closeCalls)
            fixture.device.consumed()
            assertEquals(2, fixture.driver.fences.size)
            repeat(100) { fixture.device.poll() }
            assertEquals(0, resource.closeCalls)
            assertEquals(0, fixture.driver.finishCalls)
            fixture.driver.fences
                .last()
                .signalled = true
            fixture.device.poll()
            assertEquals(1, resource.closeCalls)
            assertEquals(0, fixture.gui.retainedSetCount())
        }
    }

    @Test
    fun retiredPinnedGenerationCanQueueAgainAfterAnIntermediateConsumer() {
        NativeGuiResourceFixture().use { fixture ->
            val set = fixture.initialized()
            val resource = fixture.allocations.single()
            fixture.gui.beginUse(set)
            fixture.gui.queued(set)
            fixture.device.consumed()
            fixture.driver.signalAll()
            fixture.device.poll()
            fixture.gui.release(set)
            assertEquals(0, resource.closeCalls)
            fixture.gui.queued(set)
            fixture.gui.endUse(set)
            repeat(100) { fixture.device.poll() }
            assertEquals(0, resource.closeCalls)
            fixture.device.consumed()
            assertEquals(3, fixture.driver.fences.size)
            repeat(100) { fixture.device.poll() }
            assertEquals(0, resource.closeCalls)
            fixture.driver.fences
                .last()
                .signalled = true
            fixture.device.poll()
            assertEquals(1, resource.closeCalls)
            assertEquals(0, fixture.gui.retainedSetCount())
        }
    }

    @Test
    fun unchangedReadOnlyGenerationReusesResourcesButWaitsForItsLatestConsumption() {
        NativeGuiResourceFixture().use { fixture ->
            val set = fixture.initialized()
            val resource = fixture.allocations.single()
            repeat(4) {
                fixture.gui.beginUse(set)
                fixture.gui.queued(set)
                fixture.gui.endUse(set)
                fixture.device.consumed()
            }
            assertEquals(1, fixture.allocations.size)
            assertEquals(1, fixture.gui.retainedSetCount())
            assertEquals(5, fixture.driver.fences.size)
            fixture.gui.release(set)
            fixture.driver.fences
                .dropLast(1)
                .forEach { it.signalled = true }
            fixture.device.poll()
            assertEquals(0, resource.closeCalls)
            fixture.driver.fences
                .last()
                .signalled = true
            fixture.device.poll()
            assertEquals(1, resource.closeCalls)
            assertTrue(fixture.driver.fences.all { it.closeCalls == 1 })
        }
    }

    @Test
    fun stableOwnerLimitIncludesRetiredAsynchronousDestructionWithoutAdditionalClosePermits() {
        NativeGuiResourceFixture().use { fixture ->
            val sets = List(3) { fixture.initialized() }
            fixture.allocations.forEach { it.destroyOnClose = false }
            sets.forEach(fixture.gui::release)
            fixture.driver.signalAll()
            repeat(100) { fixture.device.poll() }
            assertEquals(3, fixture.gui.retainedSetCount())
            assertTrue(fixture.allocations.all { it.closeCalls == 1 })
            assertThrows(IllegalStateException::class.java) { fixture.initialized() }
            assertEquals(3, fixture.allocations.size)
            fixture.allocations.first().destroyed = true
            fixture.device.poll()
            val replacement = fixture.initialized()
            assertEquals(3, fixture.gui.retainedSetCount())
            fixture.gui.release(replacement)
            fixture.allocations.forEach { it.destroyed = true }
            fixture.driver.signalAll()
            fixture.device.poll()
            assertEquals(0, fixture.gui.retainedSetCount())
            assertTrue(fixture.allocations.all { it.closeCalls == 1 })
        }
    }

    @Test
    fun deviceLimitIncludesAllRetiredPresentersUntilTheirPhysicalDestruction() {
        NativeGuiResourceFixture().use { fixture ->
            val sets = List(64) { fixture.initialized(ownerId = fixture.gui.createOwnerId()) }
            fixture.allocations.forEach { it.destroyOnClose = false }
            sets.forEach(fixture.gui::release)
            fixture.driver.signalAll()
            repeat(100) { fixture.device.poll() }
            assertEquals(64, fixture.gui.retainedSetCount())
            assertEquals(0, fixture.device.retainedTargetCount())
            repeat(5) {
                val newOwner = fixture.gui.createOwnerId()
                assertThrows(IllegalStateException::class.java) { fixture.initialized(ownerId = newOwner) }
            }
            assertEquals(64, fixture.allocations.size)
            assertTrue(fixture.allocations.all { it.closeCalls == 1 })
            fixture.allocations.forEach { it.destroyed = true }
            fixture.device.poll()
            assertEquals(0, fixture.gui.retainedSetCount())
            fixture.initialized()
            assertEquals(1, fixture.gui.retainedSetCount())
        }
    }

    @Test
    fun fullPortableQuotaDoesNotConsumeNativeCanvasTargetPermits() {
        NativeGuiResourceFixture().use { fixture ->
            repeat(64) { fixture.initialized(ownerId = fixture.gui.createOwnerId()) }
            val tree = fixture.canvas.tree()
            fixture.canvas.submit(fixture.canvas.prepare(tree))
            assertEquals(64, fixture.gui.retainedSetCount())
            assertEquals(1, fixture.device.retainedTargetCount())
            assertEquals(1, fixture.driver.allocationAttempts)
            fixture.device.closeAfterGuiDiscarded()
            assertEquals(0, fixture.gui.retainedSetCount())
            assertEquals(0, fixture.device.retainedTargetCount())
            assertEquals(1, fixture.driver.finishCalls)
            assertEquals(1, fixture.driver.drainCalls)
        }
    }

    @Test
    fun invalidExtentsForeignTokensAndDuplicateResourcesFailBeforeTransferOrOutput() {
        NativeGuiResourceFixture().use { fixture ->
            NativeGuiResourceFixture().use { foreign ->
                val gui = fixture.gui
                assertThrows(IllegalArgumentException::class.java) { gui.reserve(fixture.owner, listOf(IntSize(0, 2))) }
                assertThrows(ArithmeticException::class.java) { gui.reserve(fixture.owner, listOf(IntSize(Int.MAX_VALUE, 2))) }
                assertThrows(IllegalStateException::class.java) { gui.reserve(foreign.owner, listOf(IntSize(2, 2))) }
                assertEquals(0, gui.retainedSetCount())
                val first = gui.reserve(fixture.owner, listOf(IntSize(2, 2)))
                val second = gui.reserve(fixture.owner, listOf(IntSize(2, 2)))
                val resource = fixture.add(first)
                assertThrows(IllegalStateException::class.java) { gui.add(second, resource) }
                assertThrows(IllegalStateException::class.java) { foreign.gui.add(first, resource) }
                assertThrows(IllegalStateException::class.java) { foreign.gui.release(first) }
                assertEquals(0, fixture.driver.fences.size)
                gui.seal(first)
                gui.seal(second)
                gui.release(first)
                gui.release(second)
                fixture.driver.signalAll()
                fixture.device.poll()
                assertEquals(1, resource.closeCalls)
                assertEquals(0, gui.retainedSetCount())
            }
        }
    }

    @Test
    fun offThreadAcquisitionAndRetirementCannotMutateTheOwner() {
        NativeGuiResourceFixture().use { fixture ->
            val set = fixture.initialized()
            val observed = AtomicReference<List<Throwable?>>()
            val thread =
                Thread {
                    observed.set(
                        listOf(
                            runCatching { fixture.gui.createOwnerId() }.exceptionOrNull(),
                            runCatching { fixture.gui.release(set) }.exceptionOrNull(),
                            runCatching { fixture.gui.retainedSetCount() }.exceptionOrNull(),
                        ),
                    )
                }
            thread.start()
            thread.join()
            assertTrue(observed.get().all { it is IllegalStateException })
            fixture.driver.signalAll()
            fixture.device.poll()
            assertEquals(0, fixture.allocations.single().closeCalls)
            fixture.gui.beginUse(set)
            fixture.gui.endUse(set)
        }
    }

    @Test
    fun terminalShutdownFinishesBothResourceFamiliesBeforeOnePhysicalDestructionDrain() {
        NativeGuiResourceFixture().use { fixture ->
            fixture.driver.destroyOnClose = false
            val tree = fixture.canvas.tree()
            fixture.device.queue(fixture.canvas.prepare(tree))
            val set = fixture.initialized(count = 2)
            fixture.allocations.forEach { resource ->
                resource.destroyOnClose = false
                resource.onClose = {
                    assertEquals(1, fixture.driver.finishCalls)
                    assertEquals(0, fixture.driver.drainCalls)
                    assertEquals(
                        1,
                        fixture.driver.targets
                            .single()
                            .closeCalls,
                    )
                    assertThrows(IllegalStateException::class.java) { fixture.gui.createOwnerId() }
                }
            }
            fixture.gui.beginUse(set)
            fixture.gui.queued(set)
            fixture.device.closeAfterGuiDiscarded()
            fixture.gui.endUse(set)
            fixture.gui.release(set)
            fixture.device.closeAfterGuiDiscarded()
            assertEquals(1, fixture.driver.finishCalls)
            assertEquals(1, fixture.driver.drainCalls)
            assertEquals(0, fixture.gui.retainedSetCount())
            assertEquals(0, fixture.device.retainedTargetCount())
            assertTrue(fixture.allocations.all { it.destroyed && it.closeCalls == 1 })
            assertTrue(fixture.driver.fences.all { it.closeCalls == 1 })
            assertThrows(IllegalStateException::class.java) { fixture.gui.reserve(fixture.owner, emptyList()) }
        }
    }

    @Test
    fun retirementCallbacksMayReleaseAnotherSetAndDetachANativeTreeWithoutReentry() {
        NativeGuiResourceFixture().use { fixture ->
            val tree = fixture.canvas.tree()
            fixture.device.cancel(fixture.canvas.prepare(tree))
            val first = fixture.initialized()
            val second = fixture.initialized()
            fixture.allocations.first().onClose = {
                fixture.gui.release(second)
                tree.close()
                assertThrows(IllegalStateException::class.java) { fixture.gui.createOwnerId() }
                assertThrows(IllegalStateException::class.java) { fixture.device.poll() }
            }
            fixture.driver.signalAll()
            fixture.gui.release(first)
            fixture.device.poll()
            assertEquals(0, fixture.gui.retainedSetCount())
            assertEquals(0, fixture.device.retainedTargetCount())
            assertTrue(fixture.allocations.all { it.closeCalls == 1 })
            assertEquals(
                1,
                fixture.canvas.producers
                    .single()
                    .closeCalls,
            )
            assertFalse(fixture.allocations.any { it.destructionPolls == 0 })
        }
    }
}
