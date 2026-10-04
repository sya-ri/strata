package dev.s7a.strata.runtime.minecraft.canvas

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies immutable layer sharing across independently fenced and bounded portable generations.
 *
 * Synthetic fences exercise delayed initialization, both consumption orders, partial failure, and terminal destruction.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class NativeGuiResourceReuseTest {
    @Test
    fun changingOneLayerAcrossHundredsOfGenerationsRetainsOnlyCurrentStorage() {
        NativeGuiResourceFixture().use { fixture ->
            var previous = fixture.initialized(count = 2)
            val unchanged = fixture.allocations.first()
            repeat(200) {
                val next = fixture.gui.reserve(fixture.owner, List(2) { IntSize(2, 2) })
                fixture.gui.reuse(next, previous, 0)
                fixture.add(next)
                fixture.gui.seal(next)
                fixture.gui.release(previous)
                fixture.driver.signalAll()
                fixture.device.poll()
                assertEquals(1, fixture.gui.retainedSetCount())
                assertEquals(0, unchanged.closeCalls)
                assertEquals(2, fixture.allocations.count { it.destroyed.not() })
                previous = next
            }
            fixture.gui.release(previous)
            fixture.device.poll()
            assertEquals(0, fixture.gui.retainedSetCount())
            assertEquals(202, fixture.allocations.size)
            assertTrue(fixture.allocations.all { it.closeCalls == 1 && it.destroyed })
        }
    }

    @Test
    fun sharedLayerWaitsForEveryGenerationPinAndActualConsumerFence() {
        listOf(true, false).forEach { retireSourceFirst ->
            NativeGuiResourceFixture().use { fixture ->
                val source = fixture.initialized()
                val resource = fixture.allocations.single()
                val next = fixture.gui.reserve(fixture.owner, listOf(IntSize(2, 2)))
                fixture.gui.reuse(next, source, 0)
                fixture.gui.seal(next)
                fixture.gui.beginUse(source)
                fixture.gui.beginUse(next)
                fixture.gui.queued(source)
                fixture.device.consumed()
                fixture.driver.signalAll()
                fixture.gui.release(source)
                fixture.gui.release(next)
                val first = if (retireSourceFirst) source else next
                val last = if (retireSourceFirst) next else source
                fixture.gui.endUse(first)
                fixture.device.poll()
                assertEquals(0, resource.closeCalls)
                fixture.gui.queued(last)
                fixture.gui.endUse(last)
                fixture.device.poll()
                assertEquals(0, resource.closeCalls)
                fixture.device.consumed()
                fixture.device.poll()
                assertEquals(0, resource.closeCalls)
                fixture.driver.signalAll()
                fixture.device.poll()
                assertEquals(1, resource.closeCalls)
                assertEquals(0, fixture.gui.retainedSetCount())
            }
        }
    }

    @Test
    fun failedPartialReplacementReleasesOnlyItsNewStorageAndKeepsThePreviousPixels() {
        NativeGuiResourceFixture().use { fixture ->
            val source = fixture.initialized()
            val retained = fixture.allocations.single()
            val failed = fixture.gui.reserve(fixture.owner, List(3) { IntSize(2, 2) })
            fixture.gui.reuse(failed, source, 0)
            val partial = fixture.add(failed)
            fixture.gui.seal(failed)
            fixture.gui.release(failed)
            fixture.driver.signalAll()
            fixture.device.poll()
            assertEquals(0, retained.closeCalls)
            assertEquals(1, partial.closeCalls)
            assertEquals(1, fixture.gui.retainedSetCount())
            fixture.gui.beginUse(source)
            fixture.gui.endUse(source)
            fixture.gui.release(source)
            fixture.device.poll()
            assertEquals(1, retained.closeCalls)
            assertEquals(0, fixture.gui.retainedSetCount())
        }
    }

    @Test
    fun reusedLayersDoNotBypassTheThreeGenerationLimitOrDelayedDestruction() {
        NativeGuiResourceFixture().use { fixture ->
            val source = fixture.initialized()
            val generations = mutableListOf(source)
            repeat(2) {
                val next = fixture.gui.reserve(fixture.owner, listOf(IntSize(2, 2)))
                fixture.gui.reuse(next, source, 0)
                fixture.gui.seal(next)
                generations += next
            }
            assertThrows(IllegalStateException::class.java) { fixture.gui.reserve(fixture.owner, listOf(IntSize(2, 2))) }
            val resource = fixture.allocations.single()
            resource.destroyOnClose = false
            generations.forEach(fixture.gui::release)
            fixture.driver.signalAll()
            fixture.device.poll()
            assertEquals(1, resource.closeCalls)
            assertEquals(1, fixture.gui.retainedSetCount())
            resource.destroyed = true
            fixture.device.poll()
            assertEquals(0, fixture.gui.retainedSetCount())
        }
    }

    @Test
    fun reuseRejectsForeignOwnersChangedExtentsAndInvalidSourceStatesBeforeTransfer() {
        NativeGuiResourceFixture().use { fixture ->
            val source = fixture.initialized()
            val foreign = fixture.gui.reserve(fixture.gui.createOwnerId(), listOf(IntSize(2, 2)))
            assertThrows(IllegalStateException::class.java) { fixture.gui.reuse(foreign, source, 0) }
            fixture.gui.seal(foreign)
            fixture.gui.release(foreign)
            val changed = fixture.gui.reserve(fixture.owner, listOf(IntSize(3, 2)))
            assertThrows(IllegalStateException::class.java) { fixture.gui.reuse(changed, source, 0) }
            assertThrows(IndexOutOfBoundsException::class.java) { fixture.gui.reuse(changed, source, 1) }
            fixture.gui.seal(changed)
            fixture.gui.release(changed)
            fixture.driver.signalAll()
            fixture.device.poll()
            val next = fixture.gui.reserve(fixture.owner, listOf(IntSize(2, 2)))
            fixture.gui.release(source)
            assertThrows(IllegalStateException::class.java) { fixture.gui.reuse(next, source, 0) }
            fixture.gui.seal(next)
            fixture.gui.release(next)
            fixture.driver.signalAll()
            fixture.device.poll()
            assertEquals(1, fixture.allocations.single().closeCalls)
        }
    }

    @Test
    fun quarantinedSharedGenerationRetainsStorageUntilTerminalCompletion() {
        NativeGuiResourceFixture().use { fixture ->
            val source = fixture.initialized()
            val next = fixture.gui.reserve(fixture.owner, listOf(IntSize(2, 2)))
            fixture.gui.reuse(next, source, 0)
            fixture.driver.nextFenceFailure = IllegalStateException("replacement initialization fence")
            assertThrows(IllegalStateException::class.java) { fixture.gui.seal(next) }
            fixture.gui.release(source)
            fixture.gui.release(next)
            fixture.driver.signalAll()
            fixture.device.poll()
            assertEquals(0, fixture.allocations.single().closeCalls)
            assertEquals(1, fixture.gui.retainedSetCount())
            fixture.device.closeAfterGuiDiscarded()
            assertEquals(1, fixture.allocations.single().closeCalls)
            assertEquals(0, fixture.gui.retainedSetCount())
        }
    }

    @Test
    fun reuseRejectsUnsealedIncompleteQuarantinedAndSealedGenerations() {
        NativeGuiResourceFixture().use { fixture ->
            val source = fixture.initialized()
            val next = fixture.gui.reserve(fixture.owner, listOf(IntSize(2, 2)))
            val partial = fixture.gui.reserve(fixture.owner, listOf(IntSize(2, 2)))
            assertThrows(IllegalStateException::class.java) { fixture.gui.reuse(next, partial, 0) }
            fixture.gui.seal(partial)
            assertThrows(IllegalStateException::class.java) { fixture.gui.reuse(next, partial, 0) }
            assertThrows(IndexOutOfBoundsException::class.java) { fixture.gui.reuse(next, source, -1) }
            fixture.gui.beginUse(source)
            fixture.gui.queued(source)
            fixture.gui.endUse(source)
            fixture.device.failedGui()
            assertThrows(IllegalStateException::class.java) { fixture.gui.reuse(next, source, 0) }
            fixture.gui.seal(next)
            assertThrows(IllegalStateException::class.java) { fixture.gui.reuse(next, source, 0) }
            fixture.gui.release(next)
            fixture.gui.release(partial)
            fixture.gui.release(source)
            fixture.driver.signalAll()
            fixture.device.poll()
            assertEquals(0, fixture.allocations.single().closeCalls)
            fixture.device.closeAfterGuiDiscarded()
            assertEquals(1, fixture.allocations.single().closeCalls)
            assertEquals(0, fixture.gui.retainedSetCount())
        }
    }

    @Test
    fun repeatedReferencesInsideOneGenerationCloseTheSharedStorageOnlyOnce() {
        NativeGuiResourceFixture().use { fixture ->
            val source = fixture.initialized()
            val next = fixture.gui.reserve(fixture.owner, List(2) { IntSize(2, 2) })
            repeat(2) { fixture.gui.reuse(next, source, 0) }
            fixture.gui.seal(next)
            fixture.gui.release(source)
            fixture.driver.signalAll()
            fixture.device.poll()
            assertEquals(0, fixture.allocations.single().closeCalls)
            fixture.gui.beginUse(next)
            fixture.gui.endUse(next)
            fixture.gui.release(next)
            fixture.device.poll()
            assertEquals(1, fixture.allocations.single().closeCalls)
            assertEquals(0, fixture.gui.retainedSetCount())
        }
    }
}
