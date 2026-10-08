@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Actual asynchronous host failure paths claim image and observer ownership before external release callbacks.
 */
internal class MinecraftPlayerHeadBindingFailureTest {
    @Test
    fun subscriptionFailureStillClosesTheBindingAfterEarlyImageRelease() {
        PlayerHeadLayerFixture(PlayerHeadLayerFixture.Mode.Async, showHat = true).use { fixture ->
            fixture.attach()
            fixture.frame()
            val painter = PlayerHeadCacheProbe.painters(fixture.host).single()
            val binding = fixture.platform.binding
            val observer = binding.savedObserver()
            val first = EqualFailure("Subscription")
            val second = EqualFailure("Binding")
            binding.subscriptionFailure = first
            binding.closeFailure = second
            var releaseObserved = false
            binding.beforeClose = {
                assertEmpty(painter)
                observer()
                releaseObserved = true
            }

            assertSame(first, assertThrows(EqualFailure::class.java) { fixture.detach() })
            assertTrue(releaseObserved)
            assertTrue(binding.closed)
            assertEquals(0, fixture.platform.activeCount)
            assertEquals(1, fixture.platform.releases)
            assertEquals(1, first.suppressed.size)
            assertSame(second, first.suppressed.single())
            fixture.close()
            observer()
            assertEmpty(painter)
        }
    }

    @Test
    fun cleanupGraphsDoNotAcquireCyclesOrDuplicateFailures() {
        FailureGraph.entries.forEach { graph ->
            PlayerHeadLayerFixture(PlayerHeadLayerFixture.Mode.Async, showHat = true).use { fixture ->
                fixture.attach()
                fixture.frame()
                val painter = PlayerHeadCacheProbe.painters(fixture.host).single()
                val first = IllegalStateException("Subscription")
                val other = IllegalStateException("Binding")
                val second =
                    when (graph) {
                        FailureGraph.Same -> first
                        FailureGraph.PrimaryContainsSecondary -> other.also(first::initCause)
                        FailureGraph.SecondaryContainsPrimary -> other.also { it.initCause(first) }
                        FailureGraph.ExistingCycle -> other.also {
                            first.initCause(it)
                            it.initCause(first)
                        }
                    }
                val binding = fixture.platform.binding
                binding.subscriptionFailure = first
                binding.closeFailure = second

                assertSame(first, assertThrows(IllegalStateException::class.java) { fixture.close() })
                assertEmpty(painter)
                assertEquals(0, first.suppressed.size)
                assertEquals(0, fixture.platform.activeCount)
                assertEquals(1, fixture.platform.releases)
            }
        }
    }

    @Test
    fun invalidInitialSnapshotPreservesValidationFailureWhenAcquiredBindingCloseFails() {
        val invalid = createDrawImage(IntSize(64, 32), IntArray(64 * 32))
        PlayerHeadLayerFixture(PlayerHeadLayerFixture.Mode.Async, skin = invalid).use { fixture ->
            val cleanup = IllegalStateException("Acquired binding release")
            fixture.platform.onAcquire = { it.closeFailure = cleanup }
            val failure = assertThrows(IllegalArgumentException::class.java) { fixture.attach() }
            assertEquals("PlayerHead requires an exact 64 by 64 skin.", failure.message)
            assertSame(cleanup, failure.suppressed.single())
            assertEquals(1, fixture.platform.acquisitions)
            assertEquals(1, fixture.platform.releases)
            assertEquals(0, fixture.platform.activeCount)
        }
    }

    @Test
    fun invalidLaterSnapshotTerminatesTheOwnerAndReleasesItsPreviousImages() {
        PlayerHeadLayerFixture(PlayerHeadLayerFixture.Mode.Async, showHat = true).use { fixture ->
            fixture.attach()
            fixture.frame()
            val painter = PlayerHeadCacheProbe.painters(fixture.host).single()
            val invalid = createDrawImage(IntSize(63, 64), IntArray(63 * 64))
            fixture.platform.enqueue(MinecraftPlayerSkinBinding.Snapshot.Ready(invalid))

            assertThrows(IllegalArgumentException::class.java) { fixture.frame() }
            assertEmpty(painter)
            assertEquals(0, fixture.platform.activeCount)
            assertEquals(fixture.platform.acquisitions, fixture.platform.releases)
        }
    }

    private fun assertEmpty(painter: MinecraftPlayerHeadPainter) {
        val snapshot = PlayerHeadCacheProbe.snapshot(painter)
        assertNull(snapshot.skin)
        assertNull(snapshot.face)
        assertNull(snapshot.hat)
    }

    private enum class FailureGraph {
        Same,
        PrimaryContainsSecondary,
        SecondaryContainsPrimary,
        ExistingCycle,
    }

    private class EqualFailure(
        message: String,
    ) : IllegalStateException(message) {
        override fun equals(other: Any?): Boolean = other is EqualFailure

        override fun hashCode(): Int = 0
    }
}
