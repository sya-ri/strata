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
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Verifies referential cache identity, independent bounds, and fenced terminal release without a loaded Minecraft client.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class FabricMinecraftSampledImageDeviceTest {
    @Test
    fun duplicateListEnumerationRemainsGuardedAgainstDeviceCallbackReentry() {
        SampledFixture().use { fixture ->
            val owner = fixture.manager.openOwner()
            val images = CallbackList(image(6)) { fixture.manager.openOwner() }
            val failure = assertThrows(IllegalStateException::class.java) { fixture.borrow(owner, images) }
            assertEquals("Sampled-image device operations cannot reenter callbacks.", failure.message)
            assertEquals(1, images.enumerations)
            assertEquals(0, fixture.manager.retainedResourceCount())
            fixture.borrow(owner, listOf(image(7)))
            assertEquals(1, fixture.uploads)
        }
    }

    @Test
    fun invalidAndClosedOwnersRejectBeforeEnumeratingDuplicateListInputs() {
        SampledFixture().use { fixture ->
            SampledFixture().use { foreign ->
                val images = CallbackList(image(8)) { error("Invalid owners must reject before enumeration.") }
                val foreignOwner = foreign.manager.openOwner()
                assertThrows(IllegalStateException::class.java) { fixture.borrow(foreignOwner, images) }
                val owner = fixture.manager.openOwner()
                fixture.manager.release(owner)
                assertThrows(IllegalStateException::class.java) { fixture.borrow(owner, images) }
                assertEquals(0, images.enumerations)
                assertEquals(0, fixture.uploads)
                assertEquals(0, fixture.manager.retainedResourceCount())
            }
        }
    }

    @Test
    @Suppress("TooGenericExceptionCaught") // The worker transfers any rejected operation failure to its owner-thread assertion.
    fun offThreadBorrowRejectsBeforeEnumeratingDuplicateListInputs() {
        SampledFixture().use { fixture ->
            val owner = fixture.manager.openOwner()
            val images = CallbackList(image(9)) { error("Off-thread inputs must not be enumerated.") }
            val failure = AtomicReference<Throwable?>()
            val thread =
                Thread {
                    try {
                        fixture.borrow(owner, images)
                    } catch (caught: Throwable) {
                        failure.set(caught)
                    }
                }
            thread.start()
            thread.join()
            assertTrue(failure.get() is IllegalStateException)
            assertEquals("Sampled-image device operations require the render owner thread.", failure.get()?.message)
            assertEquals(0, images.enumerations)
            assertEquals(0, fixture.manager.retainedResourceCount())
        }
    }

    @Test
    fun failedPollingRejectsBeforeEnumeratingDuplicateListInputs() {
        SampledFixture().use { fixture ->
            val owner = fixture.manager.openOwner()
            fixture.borrow(owner, listOf(image(10)))
            fixture.driver.signalAll()
            val fence = fixture.driver.fences.single()
            fence.closeFailures = 1
            val images = CallbackList(image(11)) { error("Failed polling must precede enumeration.") }
            assertThrows(IllegalStateException::class.java) { fixture.borrow(owner, images) }
            assertEquals(0, images.enumerations)
            assertEquals(1, fixture.uploads)
            fixture.manager.poll()
            fixture.borrow(owner, listOf(image(11)))
            assertEquals(2, fixture.uploads)
        }
    }

    @Test
    fun unchangedPreparedRequestsKeepOwnerCapacityAndExactFallbackAccounting() {
        SampledFixture().use { fixture ->
            val owner = fixture.manager.openOwner()
            val images = List(257) { image(it) }
            val requests = FabricMinecraftSampledImageRequests((images + images).asSequence())
            fixture.borrow(owner, requests)
            assertEquals(257, fixture.misses)
            assertEquals(256, fixture.uploads)
            fixture.borrow(owner, requests)
            assertEquals(256, fixture.hits)
            assertEquals(258, fixture.misses)
            assertEquals(256, fixture.uploads)
            assertEquals(0, fixture.evictions)
            assertEquals(256, fixture.manager.retainedResourceCount())
            fixture.manager.release(owner)
            fixture.driver.signalAll()
            fixture.manager.poll()
            assertEquals(0, fixture.manager.retainedResourceCount())
            assertEquals(0L, fixture.manager.retainedResourceBytes())
        }
    }

    @Test
    fun preparedRequestsPreserveUploadOrderCountersAndIndependentOwnerReferences() {
        SampledFixture().use { fixture ->
            val firstOwner = fixture.manager.openOwner()
            val secondOwner = fixture.manager.openOwner()
            val first = image(1)
            val equalPixels = image(1)
            val last = image(2)
            val requests = FabricMinecraftSampledImageRequests(sequenceOf(last, first, last, equalPixels, first))
            fixture.borrow(firstOwner, requests)
            assertEquals(3, fixture.misses)
            assertEquals(3, fixture.uploads)
            assertSame(last, fixture.acquiredImages[0])
            assertSame(first, fixture.acquiredImages[1])
            assertSame(equalPixels, fixture.acquiredImages[2])

            fixture.borrow(firstOwner, requests)
            fixture.borrow(secondOwner, requests)
            assertEquals(6, fixture.hits)
            assertEquals(3, fixture.misses)
            assertEquals(3, fixture.uploads)
            assertEquals(0, fixture.evictions)
            fixture.manager.release(firstOwner)
            fixture.driver.signalAll()
            fixture.manager.poll()
            assertEquals(3, fixture.manager.retainedResourceCount())
            fixture.manager.release(secondOwner)
            fixture.manager.poll()
            assertEquals(0, fixture.manager.retainedResourceCount())
            assertEquals(listOf(1, 1, 1), fixture.resources.map { it.closeCalls })
        }
    }

    @Test
    fun preparedRequestsRecheckSupportAndReloadWithoutRetainingNativeAvailability() {
        var supported = false
        SampledFixture { supported }.use { fixture ->
            val owner = fixture.manager.openOwner()
            val source = image(3)
            val requests = FabricMinecraftSampledImageRequests(sequenceOf(source, source))
            fixture.borrow(owner, requests)
            assertEquals(1, fixture.misses)
            assertEquals(0, fixture.uploads)
            supported = true
            fixture.borrow(owner, requests)
            assertEquals(2, fixture.misses)
            assertEquals(1, fixture.uploads)
            fixture.driver.signalAll()
            fixture.manager.reload()
            assertEquals(0, fixture.manager.retainedResourceCount())

            fixture.borrow(owner, requests)
            fixture.borrow(owner, requests)
            assertEquals(3, fixture.misses)
            assertEquals(2, fixture.uploads)
            assertEquals(1, fixture.hits)
            fixture.manager.release(owner)
            fixture.driver.signalAll()
            fixture.manager.poll()
            assertEquals(0, fixture.manager.retainedResourceCount())
        }
    }

    @Test
    fun preparedNestedPinsSurviveIntermediateConsumptionAndReentrantScreenRelease() {
        SampledFixture().use { fixture ->
            val owner = fixture.manager.openOwner()
            val source = image(4)
            val requests = FabricMinecraftSampledImageRequests(sequenceOf(source, source))
            fixture.manager.borrow(owner, requests, {}, {}, {}, {}).use { outer ->
                assertEquals(1, outer.entries.single().pins)
                fixture.manager.borrow(owner, requests, {}, {}, {}, {}).use { inner ->
                    assertEquals(2, inner.entries.single().pins)
                    inner.queued(source)
                    fixture.manager.consumed()
                    fixture.driver.signalAll()
                    fixture.manager.poll()
                    assertEquals(2, inner.entries.single().pins)
                }
                assertEquals(1, outer.entries.single().pins)
                outer.queued(source)
                fixture.manager.release(owner)
                assertEquals(0, fixture.resources.single().closeCalls)
            }
            assertEquals(0, fixture.resources.single().closeCalls)
            fixture.manager.consumed()
            assertEquals(0, fixture.resources.single().closeCalls)
            fixture.driver.signalAll()
            fixture.manager.poll()
            assertEquals(0, fixture.manager.retainedResourceCount())
            assertEquals(1, fixture.resources.single().closeCalls)
            assertThrows(IllegalStateException::class.java) { fixture.borrow(owner, requests) }
        }
    }

    @Test
    fun preparedRequestsBalancePinsAfterSubmissionAndAccountingCallbackFailures() {
        SampledFixture().use { fixture ->
            val owner = fixture.manager.openOwner()
            val source = image(5)
            val requests = FabricMinecraftSampledImageRequests(sequenceOf(source, source))
            val primary = IllegalArgumentException("Injected sampled-image callback failure.")
            assertSame(
                primary,
                assertThrows(IllegalArgumentException::class.java) {
                    fixture.manager.borrow(owner, requests, {}, {}, { throw primary }, {})
                },
            )
            assertSame(
                primary,
                assertThrows(IllegalArgumentException::class.java) {
                    fixture.manager.borrow(owner, requests, {}, {}, {}, {}).use { borrowed ->
                        assertEquals(1, borrowed.entries.single().pins)
                        throw primary
                    }
                },
            )
            fixture.manager.release(owner)
            fixture.driver.signalAll()
            fixture.manager.poll()
            assertEquals(0, fixture.manager.retainedResourceCount())
            assertEquals(1, fixture.resources.single().closeCalls)
        }
    }

    @Test
    fun duplicateIdentityMissesOnceBeforeTheNextBorrowHits() {
        SampledFixture().use { fixture ->
            val owner = fixture.manager.openOwner()
            val image = image(0)

            fixture.borrow(owner, listOf(image, image))

            assertEquals(0, fixture.hits)
            assertEquals(1, fixture.misses)
            assertEquals(1, fixture.uploads)

            fixture.borrow(owner, listOf(image, image))

            assertEquals(1, fixture.hits)
            assertEquals(1, fixture.misses)
            assertEquals(1, fixture.uploads)
        }
    }

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

    private class CallbackList(
        private val image: DrawImage,
        private val onEnumeration: () -> Unit,
    ) : AbstractList<DrawImage>() {
        var enumerations = 0
        override val size: Int = 1

        override fun get(index: Int): DrawImage {
            require(index == 0)
            return image
        }

        override fun iterator(): Iterator<DrawImage> {
            enumerations += 1
            onEnumeration()
            return listOf(image).iterator()
        }
    }

    private class SampledFixture(
        supports: (DrawImage) -> Boolean = { true },
    ) : AutoCloseable {
        val driver = Driver()
        val resources = ArrayList<Resource>()
        val acquiredImages = ArrayList<DrawImage>()
        val manager =
            FabricMinecraftSampledImageDevice(driver, supports) { image, retain ->
                acquiredImages.add(image)
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

        fun borrow(
            owner: FabricMinecraftSampledImageDevice.Owner,
            requests: FabricMinecraftSampledImageRequests,
        ) {
            manager
                .borrow(owner, requests, { hits += 1 }, { misses += 1 }, { uploads += 1 }, { evictions += 1 })
                .use { borrowed -> assertEquals(requests.images.count { owner.images.containsKey(it) }, borrowed.entries.size) }
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
