@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.PlayerSkinSource
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.resource.parseUuid
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Actual public sync and deterministic async hosts verify layer pixels, identity, invalidation and ownership.
 * Source layouts are already normalized by the public Pixels/Ready contract; legacy 64 by 32 inputs remain invalid.
 */
internal class MinecraftPlayerHeadLayerOwnershipTest {
    @Test
    fun hiddenThenVisibleThenHiddenRetainsOneFaceAndOnlyOneRequestedHat() {
        PlayerHeadLayerFixture.Mode.entries.forEach { mode ->
            listOf(10, 31, 127).forEach { size ->
                PlayerHeadLayerFixture(mode, size = size).use { fixture ->
                    fixture.attach()
                    val painter = PlayerHeadCacheProbe.painters(fixture.host).single()
                    val hidden = fixture.frame()
                    PlayerHeadPixelReference.verify(hidden.drawCommands, fixture.skin, size, false)
                    val face = (hidden.drawCommands.single() as DrawCommand.SampledImage).image
                    assertSame(face, PlayerHeadCacheProbe.snapshot(painter).face)
                    assertNull(PlayerHeadCacheProbe.snapshot(painter).hat)
                    val savedPixels = face.copyArgb()

                    fixture.hat(true)
                    val visible = fixture.frame()
                    PlayerHeadPixelReference.verify(visible.drawCommands, fixture.skin, size, true)
                    val images = visible.drawCommands.filterIsInstance<DrawCommand.SampledImage>()
                    val hat = images.last().image
                    assertSame(face, images.first().image)
                    assertSame(hat, PlayerHeadCacheProbe.snapshot(painter).hat)
                    fixture.hat(false)
                    val hiddenAgain = fixture.frame()
                    assertSame(face, (hiddenAgain.drawCommands.single() as DrawCommand.SampledImage).image)
                    assertSame(hat, PlayerHeadCacheProbe.snapshot(painter).hat)
                    fixture.hat(true)
                    val visibleAgain = fixture.frame().drawCommands.filterIsInstance<DrawCommand.SampledImage>()
                    assertSame(face, visibleAgain.first().image)
                    assertSame(hat, visibleAgain.last().image)
                    assertArrayEquals(savedPixels, face.copyArgb())
                    fixture.close()
                    assertEmpty(painter)
                    assertEquals(0, fixture.platform.activeCount)
                }
            }
        }
    }

    @Test
    fun visibleColdPreparationAndNearLimitPixelsMatchIndependentAlphaReference() {
        PlayerHeadLayerFixture.Mode.entries.forEach { mode ->
            listOf(10, 31, 127, 1023).forEach { size ->
                PlayerHeadLayerFixture(mode, size = size, showHat = true).use { fixture ->
                    fixture.attach()
                    val frame = fixture.frame()
                    PlayerHeadPixelReference.verify(frame.drawCommands, fixture.skin, size, true)
                    val cache = PlayerHeadCacheProbe.snapshot(PlayerHeadCacheProbe.painters(fixture.host).single())
                    assertEquals(IntSize(size, size), checkNotNull(cache.face).size)
                    assertEquals(IntSize(size, size), checkNotNull(cache.hat).size)
                    assertEquals(size.toLong() * size * 2, checkNotNull(cache.face).copyArgb().size.toLong() + checkNotNull(cache.hat).copyArgb().size)
                }
            }
        }
    }

    @Test
    fun valueEqualReplacementUsesNewSourceIdentityInBothPublicPaths() {
        PlayerHeadLayerFixture.Mode.entries.forEach { mode ->
            PlayerHeadLayerFixture(mode, showHat = true).use { fixture ->
                fixture.attach()
                val firstSkin = fixture.skin
                val firstFrame = fixture.frame()
                val first = firstFrame.drawCommands.filterIsInstance<DrawCommand.SampledImage>()
                val saved = first.map { it.image.copyArgb() }
                val equal = PlayerHeadPixelReference.skin()
                assertEquals(firstSkin, equal)
                assertNotSame(firstSkin, equal)
                fixture.replace(equal)
                val replacement = fixture.frame()
                PlayerHeadPixelReference.verify(replacement.drawCommands, equal, 10, true)
                val next = replacement.drawCommands.filterIsInstance<DrawCommand.SampledImage>()
                first.zip(next).forEach { (previous, current) -> assertNotSame(previous.image, current.image) }
                assertSame(equal, PlayerHeadCacheProbe.snapshot(PlayerHeadCacheProbe.painters(fixture.host).single()).skin)
                first.zip(saved).forEach { (command, pixels) -> assertArrayEquals(pixels, command.image.copyArgb()) }
            }
        }
    }

    @Test
    fun sizeChangesInvalidateBothLayersAndNearestControlsRetainOnlyTheOriginalSkin() {
        PlayerHeadLayerFixture.Mode.entries.forEach { mode ->
            PlayerHeadLayerFixture(mode, showHat = true).use { fixture ->
                fixture.attach()
                val first = fixture.frame().drawCommands.filterIsInstance<DrawCommand.SampledImage>()
                val painter = PlayerHeadCacheProbe.painters(fixture.host).single()
                fixture.resize(11)
                val resized = fixture.frame()
                PlayerHeadPixelReference.verify(resized.drawCommands, fixture.skin, 11, true)
                first.zip(resized.drawCommands.filterIsInstance<DrawCommand.SampledImage>()).forEach { (previous, current) -> assertNotSame(previous.image, current.image) }
                fixture.resize(16)
                PlayerHeadPixelReference.verify(fixture.frame().drawCommands, fixture.skin, 16, true)
                assertEmpty(painter)
                fixture.resize(31)
                PlayerHeadPixelReference.verify(fixture.frame().drawCommands, fixture.skin, 31, true)
                assertNotSame(first.first().image, PlayerHeadCacheProbe.snapshot(painter).face)
            }
            listOf(8, 16, 24).forEach { size ->
                PlayerHeadLayerFixture(mode, size = size, showHat = true, entry = PlayerHeadLayerFixture.Entry.TypedScale).use { fixture ->
                    fixture.attach()
                    PlayerHeadPixelReference.verify(fixture.frame().drawCommands, fixture.skin, size, true)
                    assertEmpty(PlayerHeadCacheProbe.painters(fixture.host).single())
                    fixture.hat(false)
                    PlayerHeadPixelReference.verify(fixture.frame().drawCommands, fixture.skin, size, false)
                }
            }
        }
    }

    @Test
    fun detachReentryAndKeyedRemovalReleaseOnlyTheirOwnCurrentImages() {
        PlayerHeadLayerFixture.Mode.entries.forEach { mode ->
            val shared = PlayerHeadPixelReference.skin()
            PlayerHeadLayerFixture(mode, shared, showHat = true).use { first ->
                PlayerHeadLayerFixture(mode, shared, showHat = true).use { second ->
                    first.attach()
                    second.attach()
                    val firstPainter = PlayerHeadCacheProbe.painters(first.host).single()
                    val secondPainter = PlayerHeadCacheProbe.painters(second.host).single()
                    first.frame()
                    second.frame()
                    val original = PlayerHeadCacheProbe.snapshot(firstPainter)
                    val independent = PlayerHeadCacheProbe.snapshot(secondPainter)
                    assertNotSame(original.face, independent.face)
                    assertNotSame(original.hat, independent.hat)
                    first.detach()
                    assertEmpty(firstPainter)
                    assertSame(independent.face, PlayerHeadCacheProbe.snapshot(secondPainter).face)
                    first.attach()
                    first.frame()
                    assertNotSame(original.face, PlayerHeadCacheProbe.snapshot(firstPainter).face)
                    first.present(false)
                    assertTrue(first.frame().drawCommands.isEmpty())
                    assertEmpty(firstPainter)
                    assertTrue(PlayerHeadCacheProbe.painters(first.host).isEmpty())
                    first.present(true)
                    PlayerHeadPixelReference.verify(first.frame().drawCommands, shared, 10, true)
                    val reinserted = PlayerHeadCacheProbe.painters(first.host).single()
                    assertNotSame(firstPainter, reinserted)
                    first.close()
                    assertEmpty(reinserted)
                    assertSame(independent.face, PlayerHeadCacheProbe.snapshot(secondPainter).face)
                    second.close()
                    assertEmpty(secondPainter)
                    assertEquals(first.platform.acquisitions, first.platform.releases)
                    assertEquals(second.platform.acquisitions, second.platform.releases)
                }
            }
        }
    }

    @Test
    fun aLongReplacementHistoryRetainsOnlyOneCurrentGenerationPerOwner() {
        PlayerHeadLayerFixture.Mode.entries.forEach { mode ->
            PlayerHeadLayerFixture(mode).use { fixture ->
                fixture.attach()
                val painter = PlayerHeadCacheProbe.painters(fixture.host).single()
                repeat(128) { generation ->
                    fixture.resize(if (generation % 2 == 0) 10 else 31)
                    fixture.replace(PlayerHeadPixelReference.skin(generation))
                    fixture.hat(false)
                    PlayerHeadPixelReference.verify(fixture.frame().drawCommands, fixture.skin, fixture.size, false)
                    val hidden = PlayerHeadCacheProbe.snapshot(painter)
                    assertSame(fixture.skin, hidden.skin)
                    assertNull(hidden.hat)
                    fixture.hat(true)
                    PlayerHeadPixelReference.verify(fixture.frame().drawCommands, fixture.skin, fixture.size, true)
                    val visible = PlayerHeadCacheProbe.snapshot(painter)
                    assertSame(hidden.face, visible.face)
                    assertEquals(IntSize(fixture.size, fixture.size), checkNotNull(visible.hat).size)
                    assertEquals(1, PlayerHeadCacheProbe.painters(fixture.host).size)
                    assertEquals(if (mode == PlayerHeadLayerFixture.Mode.Async) 1 else 0, fixture.platform.activeCount)
                }
                fixture.close()
                assertEmpty(painter)
            }
        }
    }

    @Test
    fun invalidSourcesAndLogicalSizesFailBeforeAFrameCanBePublished() {
        PlayerHeadLayerFixture.Mode.entries.forEach { mode ->
            listOf(IntSize.Zero, IntSize(64, 32), IntSize(63, 64)).forEach { extent ->
                val invalid = createDrawImage(extent, IntArray(extent.width * extent.height))
                PlayerHeadLayerFixture(mode, invalid).use { fixture ->
                    assertThrows(IllegalArgumentException::class.java) { fixture.attach() }
                    assertEquals(0, fixture.platform.activeCount)
                }
            }
            listOf(0, -1, 1025).forEach { size ->
                PlayerHeadLayerFixture(mode, size = size).use { fixture ->
                    assertThrows(IllegalArgumentException::class.java) { fixture.attach() }
                    assertEquals(0, fixture.platform.activeCount)
                }
            }
        }
    }

    @Test
    fun pendingFailedAndReadyStatesHaveExactFallbackPlacementAndCutoffs() {
        PlayerHeadLayerFixture(PlayerHeadLayerFixture.Mode.Async, readyAtAttach = false).use { fixture ->
            fixture.attach()
            val painter = PlayerHeadCacheProbe.painters(fixture.host).single()
            assertEquals(IntRect(3, 3, 7, 7), (fixture.frame().drawCommands.single() as DrawCommand.FillRectangle).bounds)
            assertEmpty(painter)
            fixture.platform.enqueue(MinecraftPlayerSkinBinding.Snapshot.Ready(fixture.skin))
            assertSame(MinecraftPlayerSkinBinding.Snapshot.Pending, fixture.platform.binding.snapshot())
            PlayerHeadPixelReference.verify(fixture.frame().drawCommands, fixture.skin, 10, false)
            val oldFace = PlayerHeadCacheProbe.snapshot(painter).face
            fixture.platform.enqueue(MinecraftPlayerSkinBinding.Snapshot.Failed)
            assertSame(oldFace, PlayerHeadCacheProbe.snapshot(painter).face)
            assertEquals(IntRect(2, 2, 8, 8), (fixture.frame().drawCommands.single() as DrawCommand.FillRectangle).bounds)
            assertEmpty(painter)
            fixture.platform.enqueue(MinecraftPlayerSkinBinding.Snapshot.Ready(fixture.skin))
            PlayerHeadPixelReference.verify(fixture.frame().drawCommands, fixture.skin, 10, false)
            assertNotSame(oldFace, PlayerHeadCacheProbe.snapshot(painter).face)
        }
    }

    @Test
    fun supersededCompletionsAndAllLookupLocatorsPreserveIndependentBindingOwnership() {
        PlayerHeadLayerFixture(PlayerHeadLayerFixture.Mode.Async, showHat = true).use { fixture ->
            fixture.attach()
            fixture.frame()
            val old = fixture.platform.binding
            val completion = old.savedObserver()
            val painter = PlayerHeadCacheProbe.painters(fixture.host).single()
            listOf(
                PlayerSkinSource.CurrentPlayer,
                PlayerSkinSource.Uuid(parseUuid("0d767d95-c2f3-4601-8754-0c7cd29405bb")),
                PlayerSkinSource.Name("ReplacementPlayer"),
            ).forEach { source ->
                fixture.source(source)
                PlayerHeadPixelReference.verify(fixture.frame().drawCommands, fixture.skin, 10, true)
                completion()
                assertTrue(old.closed)
                assertEquals(1, fixture.platform.activeCount)
                assertSame(fixture.skin, PlayerHeadCacheProbe.snapshot(painter).skin)
            }
            fixture.close()
            completion()
            assertEmpty(painter)
            assertEquals(fixture.platform.acquisitions, fixture.platform.releases)
        }
    }

    @Test
    fun publicInputSemanticsAndCaughtReentryStayStableAcrossHeadUpdates() {
        PlayerHeadLayerFixture.Mode.entries.forEach { mode ->
            val probe = MinecraftHostProbe()
            PlayerHeadLayerFixture(mode, probe = probe).use { fixture ->
                fixture.attach()
                assertEquals(InputResult.Ignored, fixture.host.dispatchPointer(PointerEvent.Move(IntOffset(11, 0))))
                val reentry = ArrayList<Throwable>()
                fixture.platform.onRefresh = {
                    reentry += assertThrows(IllegalStateException::class.java) { fixture.frame() }
                }
                listOf(10, 31, 16).forEach { size ->
                    fixture.resize(size)
                    fixture.hat(true)
                    fixture.replace(PlayerHeadPixelReference.skin(size))
                    val frame = fixture.frame()
                    val entry = frame.semantics.single()
                    assertEquals(UiText.Literal("minecraft-host"), entry.semantics.label)
                    assertEquals(IntRect(size, 0, size + 2, 1), entry.bounds)
                    PlayerHeadPixelReference.verify(frame.drawCommands.filterIsInstance<DrawCommand.SampledImage>(), fixture.skin, size, true)
                    assertEquals(InputResult.Consumed, fixture.host.dispatchPointer(PointerEvent.Move(IntOffset(size + 1, 0))))
                }
                assertEquals(3, reentry.size)
                assertEquals(3, probe.inputCalls)
            }
        }
    }

    @Test
    fun downstreamPaintFailureReleasesPreparedLayersAndPreservesTheExactPrimary() {
        PlayerHeadLayerFixture.Mode.entries.forEach { mode ->
            val failure = IllegalStateException("Downstream paint failed.")
            val probe = MinecraftHostProbe(paintFailure = failure)
            PlayerHeadLayerFixture(mode, showHat = true, probe = probe).use { fixture ->
                fixture.attach()
                val painter = PlayerHeadCacheProbe.painters(fixture.host).single()
                assertSame(failure, assertThrows(IllegalStateException::class.java) { fixture.frame() })
                assertEmpty(painter)
                assertEquals(0, fixture.platform.activeCount)
                assertEquals(fixture.platform.acquisitions, fixture.platform.releases)
            }
        }
    }

    @Test
    fun detachmentBeforeFirstFrameResumesOneBindingAndKeepsTheRetainedPainter() {
        PlayerHeadLayerFixture.Mode.entries.forEach { mode ->
            PlayerHeadLayerFixture(mode, showHat = true).use { fixture ->
                val perAttachment = if (mode == PlayerHeadLayerFixture.Mode.Async) 1 else 0
                fixture.attach()
                val painter = PlayerHeadCacheProbe.painters(fixture.host).single()
                assertEquals(perAttachment, fixture.platform.acquisitions)
                assertEmpty(painter)
                fixture.detach()
                assertEmpty(painter)
                assertEquals(0, fixture.platform.activeCount)
                assertEquals(perAttachment, fixture.platform.releases)
                fixture.attach()
                assertSame(painter, PlayerHeadCacheProbe.painters(fixture.host).single())
                assertEquals(2 * perAttachment, fixture.platform.acquisitions)
                val frame = fixture.frame()
                PlayerHeadPixelReference.verify(frame.drawCommands, fixture.skin, fixture.size, true)
                fixture.close()
                assertEmpty(painter)
                assertEquals(fixture.platform.acquisitions, fixture.platform.releases)
            }
        }
    }

    private fun assertEmpty(painter: MinecraftPlayerHeadPainter) {
        val cache = PlayerHeadCacheProbe.snapshot(painter)
        assertNull(cache.skin)
        assertNull(cache.face)
        assertNull(cache.hat)
    }
}
