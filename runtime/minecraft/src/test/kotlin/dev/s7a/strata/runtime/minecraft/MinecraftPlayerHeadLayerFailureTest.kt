package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.render.PlatformDrawCommand
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Fault injection at immutable-image construction verifies transactional preparation before command publication.
 * Successful production sampling is checked by the independent public-host pixel fixtures.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftPlayerHeadLayerFailureTest {
    @Test
    fun aFailedNewVisibleGenerationPublishesNoCommandsOrPartialCache() {
        val factory = ImageFactory()
        val painter = MinecraftPlayerHeadPainter(factory::create)
        val oldSkin = PlayerHeadPixelReference.skin()
        val old = RecordingScope(10)
        painter.paint(old, oldSkin, 10, true)
        val oldPixels = old.commands.map { it.image.copyArgb() }
        val replacement = PlayerHeadPixelReference.skin(1)
        factory.failAt = factory.calls + 2
        val next = RecordingScope(31)

        assertSame(factory.failure, assertThrows(IllegalStateException::class.java) { painter.paint(next, replacement, 31, true) })
        assertEquals(0, next.commands.size)
        assertEmpty(painter)
        old.commands.zip(oldPixels).forEach { (command, pixels) -> assertArrayEquals(pixels, command.image.copyArgb()) }

        factory.failAt = null
        painter.paint(next, replacement, 31, true)
        PlayerHeadPixelReference.verify(next.commands, replacement, 31, true)
        assertEquals(6, factory.calls)
        assertSame(replacement, PlayerHeadCacheProbe.snapshot(painter).skin)
    }

    @Test
    fun failedLaterHatKeepsTheValidFaceAndRetriesOnlyTheMissingLayer() {
        val factory = ImageFactory()
        val painter = MinecraftPlayerHeadPainter(factory::create)
        val skin = PlayerHeadPixelReference.skin()
        val hidden = RecordingScope(10)
        painter.paint(hidden, skin, 10, false)
        val face = hidden.commands.single().image
        assertEquals(1, factory.calls)
        assertNull(PlayerHeadCacheProbe.snapshot(painter).hat)
        factory.failAt = 2
        val failed = RecordingScope(10)

        assertSame(factory.failure, assertThrows(IllegalStateException::class.java) { painter.paint(failed, skin, 10, true) })
        assertEquals(0, failed.commands.size)
        assertSame(face, PlayerHeadCacheProbe.snapshot(painter).face)
        assertNull(PlayerHeadCacheProbe.snapshot(painter).hat)

        factory.failAt = null
        val visible = RecordingScope(10)
        painter.paint(visible, skin, 10, true)
        val hat = visible.commands.last().image
        assertSame(face, visible.commands.first().image)
        assertEquals(3, factory.calls)
        repeat(4) {
            painter.paint(RecordingScope(10), skin, 10, false)
            val again = RecordingScope(10)
            painter.paint(again, skin, 10, true)
            assertSame(face, again.commands.first().image)
            assertSame(hat, again.commands.last().image)
        }
        assertEquals(3, factory.calls)
        painter.clear()
        assertEmpty(painter)
    }

    @Test
    fun failedFirstFaceAndEqualPixelReplacementReleaseThePreviousGeneration() {
        val factory = ImageFactory()
        val painter = MinecraftPlayerHeadPainter(factory::create)
        val first = PlayerHeadPixelReference.skin()
        val equal = PlayerHeadPixelReference.skin()
        painter.paint(RecordingScope(10), first, 10, true)
        assertEquals(first, equal)
        factory.failAt = 3
        val scope = RecordingScope(10)

        assertSame(factory.failure, assertThrows(IllegalStateException::class.java) { painter.paint(scope, equal, 10, false) })
        assertEquals(0, scope.commands.size)
        assertEmpty(painter)
    }

    @Test
    fun nearestSamplingNeverInvokesTheFilteredImageFactory() {
        val painter = MinecraftPlayerHeadPainter { _, _ -> error("Nearest sampling must retain the source.") }
        val skin = PlayerHeadPixelReference.skin()
        listOf(8, 16, 24, 1032).forEach { size ->
            val scope = RecordingScope(size)
            painter.paint(scope, skin, size, true)
            PlayerHeadPixelReference.verify(scope.commands, skin, size, true)
            assertEmpty(painter)
        }
    }

    private fun assertEmpty(painter: MinecraftPlayerHeadPainter) {
        val snapshot = PlayerHeadCacheProbe.snapshot(painter)
        assertNull(snapshot.skin)
        assertNull(snapshot.face)
        assertNull(snapshot.hat)
    }

    private class ImageFactory {
        var calls = 0
        var failAt: Int? = null
        val failure = IllegalStateException("Image construction failed.")

        fun create(
            size: IntSize,
            pixels: IntArray,
        ): DrawImage {
            calls += 1
            if (calls == failAt) throw failure
            return createDrawImage(size, pixels)
        }
    }

    private class RecordingScope(
        extent: Int,
    ) : PaintScope {
        override val size = IntSize(extent, extent)
        val commands = ArrayList<DrawCommand.SampledImage>()

        override fun sampledImage(
            image: DrawImage,
            source: FloatRect,
            localDestination: FloatRect,
            tint: ArgbColor,
            alphaCutoff: Float,
        ) {
            commands += DrawCommand.SampledImage(image, source, localDestination, tint, alphaCutoff)
        }

        override fun fillRectangle(
            localBounds: IntRect,
            color: ArgbColor,
        ): Unit = error("Unexpected rectangle.")

        override fun blitImage(
            image: DrawImage,
            source: IntRect,
            localDestination: IntRect,
        ): Unit = error("Unexpected blit.")

        override fun drawPlatform(
            command: PlatformDrawCommand,
            localBounds: IntRect,
        ): Unit = error("Unexpected platform command.")
    }
}
