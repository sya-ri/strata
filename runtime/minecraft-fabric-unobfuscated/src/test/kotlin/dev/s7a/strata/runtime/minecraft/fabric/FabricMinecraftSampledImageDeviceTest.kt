package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDevice
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDriver
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasFence
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasTarget
import dev.s7a.strata.runtime.minecraft.canvas.NativeGuiResource
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies referential cache identity, independent bounds, and fenced terminal release without a loaded Minecraft client.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class FabricMinecraftSampledImageDeviceTest {
    @Test
    fun repeatedIdentityHitsWithoutUploadWhileEqualPixelsInAnotherImageMiss() {
        SampledFixture().use { fixture ->
            val owner = fixture.manager.openOwner()
            val first = image(1)
            val equalPixels = image(1)

            fixture.borrow(owner, listOf(first))
            fixture.borrow(owner, listOf(first))
            fixture.borrow(owner, listOf(equalPixels))

            assertEquals(1, fixture.hits)
            assertEquals(2, fixture.misses)
            assertEquals(2, fixture.uploads)
            assertEquals(0, fixture.evictions)
            assertEquals(2, fixture.manager.retainedResourceCount())
            assertEquals(8L, fixture.manager.retainedResourceBytes())

            fixture.manager.release(owner)
            fixture.driver.signalAll()
            fixture.manager.poll()
            assertEquals(0, fixture.manager.retainedResourceCount())
            assertEquals(0L, fixture.manager.retainedResourceBytes())
            assertEquals(listOf(1, 1), fixture.resources.map { resource -> resource.closeCalls })
        }
    }

    @Test
    fun ownerEvictsOnlyAnUnrequestedLeastRecentlyUsedIdentity() {
        SampledFixture().use { fixture ->
            val owner = fixture.manager.openOwner()
            val images = List(257) { value -> image(value) }
            fixture.borrow(owner, images.take(256))
            val uploads = fixture.uploads

            fixture.borrow(owner, images.drop(1))

            assertEquals(uploads + 1, fixture.uploads)
            assertEquals(1, fixture.evictions)
            assertEquals(257, fixture.manager.retainedResourceCount())
            fixture.driver.signalAll()
            fixture.manager.poll()
            assertEquals(256, fixture.manager.retainedResourceCount())

            fixture.manager.release(owner)
            fixture.manager.poll()
            assertEquals(0, fixture.manager.retainedResourceCount())
        }
    }

    @Test
    fun nestedBorrowKeepsTheOuterPinnedIdentityCachedAtOwnerCapacity() {
        SampledFixture().use { fixture ->
            val owner = fixture.manager.openOwner()
            val pinned = image(0)
            val otherImages = List(256) { value -> image(value + 1) }

            fixture.manager
                .borrow(
                    owner,
                    listOf(pinned),
                    { fixture.hits += 1 },
                    { fixture.misses += 1 },
                    { fixture.uploads += 1 },
                    { fixture.evictions += 1 },
                ).use {
                    fixture.borrow(owner, otherImages)
                    fixture.borrow(owner, listOf(pinned))
                }

            assertEquals(1, fixture.hits)
            assertEquals(257, fixture.misses)
            assertEquals(256, fixture.uploads)
            assertEquals(0, fixture.evictions)
            assertEquals(256, fixture.manager.retainedResourceCount())

            fixture.manager.release(owner)
            fixture.driver.signalAll()
            fixture.manager.poll()
            assertEquals(0, fixture.manager.retainedResourceCount())
        }
    }

    @Test
    fun deviceEntryCapacityFallsBackWithoutUsingCanvasOrAllocatingAReplacement() {
        SampledFixture().use { fixture ->
            val firstOwner = fixture.manager.openOwner()
            val secondOwner = fixture.manager.openOwner()
            val waitingOwner = fixture.manager.openOwner()
            val images = List(513) { value -> image(value) }
            fixture.borrow(firstOwner, images.subList(0, 256))
            fixture.borrow(secondOwner, images.subList(256, 512))
            val uploads = fixture.uploads

            fixture.manager
                .borrow(waitingOwner, listOf(images.last()), {}, { fixture.misses += 1 }, { fixture.uploads += 1 }, { fixture.evictions += 1 })
                .use { borrowed -> assertNull(borrowed.texture(images.last())) }

            assertEquals(uploads, fixture.uploads)
            assertEquals(512, fixture.manager.retainedResourceCount())
            assertEquals(2_048L, fixture.manager.retainedResourceBytes())

            fixture.manager.release(firstOwner)
            fixture.manager.release(secondOwner)
            fixture.manager.release(waitingOwner)
            fixture.driver.signalAll()
            fixture.manager.poll()
            assertEquals(0, fixture.manager.retainedResourceCount())
        }
    }

    @Test
    fun deviceTerminalSequenceFinishesThenClosesDrainsAndAcknowledgesSampledStorage() {
        SampledFixture().use { fixture ->
            val device = NativeCanvasDevice(fixture.driver)
            device.registerGuiResourceManager(fixture.manager)
            val owner = fixture.manager.openOwner()
            val image = image(7)
            fixture.manager
                .borrow(owner, listOf(image), {}, {}, {}, {})
                .use { borrowed -> borrowed.queued(image) }
            device.consumed()
            fixture.manager.release(owner)
            assertEquals(1, device.retainedManagedGuiResourceCount())
            assertEquals(4L, device.retainedManagedGuiResourceBytes())

            device.closeAfterGuiDiscarded()

            assertEquals(1, fixture.driver.finishCalls)
            assertEquals(1, fixture.driver.drainCalls)
            assertEquals(0, device.retainedManagedGuiResourceCount())
            assertEquals(0L, device.retainedManagedGuiResourceBytes())
            assertEquals(1, fixture.resources.single().closeCalls)
        }
    }

    @Test
    fun terminalInitializationFenceCloseFailureRetainsTheEntryAndBytes() {
        SampledFixture().use { fixture ->
            val device = NativeCanvasDevice(fixture.driver)
            device.registerGuiResourceManager(fixture.manager)
            val owner = fixture.manager.openOwner()
            fixture.borrow(owner, listOf(image(8)))
            val initialization = fixture.driver.fences.single()
            initialization.closeFailures = 1

            assertThrows(IllegalStateException::class.java) { device.closeAfterGuiDiscarded() }

            assertFalse(initialization.closed)
            assertEquals(1, initialization.closeCalls)
            assertEquals(0, fixture.resources.single().closeCalls)
            assertEquals(1, device.retainedManagedGuiResourceCount())
            assertEquals(4L, device.retainedManagedGuiResourceBytes())
        }
    }

    @Test
    fun terminalGuiFenceCloseFailureRetainsTheEntryAndBytes() {
        SampledFixture().use { fixture ->
            val device = NativeCanvasDevice(fixture.driver)
            device.registerGuiResourceManager(fixture.manager)
            val owner = fixture.manager.openOwner()
            val image = image(9)
            fixture.manager
                .borrow(owner, listOf(image), {}, {}, {}, {})
                .use { borrowed -> borrowed.queued(image) }
            fixture.driver.signalAll()
            fixture.manager.poll()
            device.consumed()
            val guiCompletion = fixture.driver.fences.last()
            guiCompletion.closeFailures = 1

            assertThrows(IllegalStateException::class.java) { device.closeAfterGuiDiscarded() }

            assertFalse(guiCompletion.closed)
            assertEquals(1, guiCompletion.closeCalls)
            assertEquals(0, fixture.resources.single().closeCalls)
            assertEquals(1, device.retainedManagedGuiResourceCount())
            assertEquals(4L, device.retainedManagedGuiResourceBytes())
        }
    }

    @Test
    fun initializationFenceCloseFailureRetainsTheEntryAndRetriesWithoutLosingTheHandle() {
        SampledFixture().use { fixture ->
            val owner = fixture.manager.openOwner()
            fixture.borrow(owner, listOf(image(10)))
            fixture.manager.release(owner)
            val initialization = fixture.driver.fences.single()
            initialization.signalled = true
            initialization.closeFailures = 1

            assertThrows(IllegalStateException::class.java) { fixture.manager.poll() }
            assertFalse(initialization.closed)
            assertEquals(1, fixture.manager.retainedResourceCount())

            fixture.manager.poll()
            assertTrue(initialization.closed)
            assertEquals(2, initialization.closeCalls)
            assertEquals(0, fixture.manager.retainedResourceCount())
            assertEquals(1, fixture.resources.single().closeCalls)
        }
    }

    @Test
    fun supersededGuiFenceCloseFailureRetainsBothCompletionsUntilRetry() {
        SampledFixture().use { fixture ->
            val owner = fixture.manager.openOwner()
            val image = image(11)
            fixture.borrow(owner, listOf(image))
            fixture.driver.signalAll()
            fixture.manager.poll()

            fixture.queue(owner, image)
            fixture.manager.consumed()
            val superseded = fixture.driver.fences.last()
            superseded.closeFailures = 2
            fixture.queue(owner, image)

            assertThrows(IllegalStateException::class.java) { fixture.manager.consumed() }
            assertFalse(superseded.closed)
            assertEquals(1, fixture.manager.retainedResourceCount())

            fixture.driver.signalAll()
            fixture.manager.poll()
            assertTrue(superseded.closed)
            assertEquals(3, superseded.closeCalls)
            fixture.manager.release(owner)
            fixture.manager.poll()
            assertEquals(0, fixture.manager.retainedResourceCount())
            assertEquals(1, fixture.resources.single().closeCalls)
        }
    }

    private fun image(value: Int): DrawImage = createDrawImage(IntSize(1, 1), intArrayOf(value))

    private class SampledFixture : AutoCloseable {
        val driver = Driver()
        val resources = ArrayList<Resource>()
        val manager =
            FabricMinecraftSampledImageDevice(driver, { true }) { _, retain ->
                Resource().also { resource ->
                    resources.add(resource)
                    retain(resource)
                }
                null
            }
        var hits = 0
        var misses = 0
        var uploads = 0
        var evictions = 0

        fun borrow(
            owner: FabricMinecraftSampledImageDevice.Owner,
            images: List<DrawImage>,
        ) {
            manager
                .borrow(owner, images, { hits += 1 }, { misses += 1 }, { uploads += 1 }, { evictions += 1 })
                .use {}
        }

        fun queue(
            owner: FabricMinecraftSampledImageDevice.Owner,
            image: DrawImage,
        ) {
            manager
                .borrow(owner, listOf(image), { hits += 1 }, { misses += 1 }, { uploads += 1 }, { evictions += 1 })
                .use { borrowed -> borrowed.queued(image) }
        }

        override fun close() {
            if (manager.retainedResourceCount() == 0) return
            manager.beginShutdown()
            driver.finish()
            manager.closeAfterFinish()
            driver.drainRetirements()
            manager.acknowledgeAfterDrain()
        }
    }

    private class Driver : NativeCanvasDriver {
        val fences = ArrayList<Fence>()
        var finishCalls = 0
        var drainCalls = 0

        override fun createTarget(
            physicalSize: IntSize,
            depth: Boolean,
        ): NativeCanvasTarget = error("Sampled-image tests never allocate Canvas targets: $physicalSize, depth=$depth")

        override fun fence(): NativeCanvasFence = Fence().also(fences::add)

        override fun finish() {
            finishCalls += 1
            signalAll()
        }

        override fun drainRetirements() {
            drainCalls += 1
        }

        fun signalAll() {
            fences.forEach { fence -> fence.signalled = true }
        }
    }

    private class Fence : NativeCanvasFence {
        var signalled = false
        var closed = false
        var closeFailures = 0
        var closeCalls = 0

        override fun isSignalled(): Boolean = signalled

        override fun close() {
            closeCalls += 1
            if (0 < closeFailures) {
                closeFailures -= 1
                error("Injected sampled-image fence close failure.")
            }
            check(closed.not()) { "A sampled-image test fence closes once." }
            closed = true
        }
    }

    private class Resource : NativeGuiResource {
        var closeCalls = 0

        override fun close() {
            closeCalls += 1
        }

        override fun isDestroyed(): Boolean = 0 < closeCalls
    }
}
