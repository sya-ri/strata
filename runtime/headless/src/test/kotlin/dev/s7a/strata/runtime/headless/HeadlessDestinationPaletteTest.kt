package dev.s7a.strata.runtime.headless

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies deterministic command-local palette admission without adding a diagnostic runtime API.
 * Raster parity is independently covered by [HeadlessSampledRasterParityTest].
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class HeadlessDestinationPaletteTest {
    @Test
    fun earlyDestinationChangesNeverAllocateChannelRowsOrReadmitThem() {
        for (prefix in listOf(1, 2, 16, 31)) {
            for (destination in listOf(0, 0x001337AA, 0x804A6789.toInt(), 0xFF234567.toInt())) {
                val color = PaletteProbe()
                repeat(prefix) { color.blend(source(it), destination) }
                assertNull(color.rows())
                color.blend(source(prefix), destination xor 1)
                repeat(512) { color.blend(source(it), destination) }
                assertNull(color.rows())
            }
        }
    }

    @Test
    fun uniformAdmissionKeepsSixteenRowsAndReleasesThemOnAnyArgbChange() {
        for (destination in listOf(0, 0x001337AA, 0x804A6789.toInt(), 0xFF234567.toInt())) {
            val color = PaletteProbe()
            repeat(31) { color.blend(source(it), destination) }
            assertNull(color.rows())
            color.blend(source(31), destination)
            assertEquals(1, checkNotNull(color.rows()).count { it != null })
            repeat(1024) { color.blend(source(it), destination) }
            val rows = checkNotNull(color.rows()).filterNotNull()
            assertEquals(16, rows.size)
            rows.forEach { assertEquals(768, it.size) }
            assertEquals(0, color.remainingSamples())
            color.blend(source(1025), destination xor 1)
            assertNull(color.rows())
            repeat(512) { color.blend(source(it), destination xor 1) }
            assertNull(color.rows())
        }
    }

    @Test
    fun repeatedPairsDiscardedSourcesAndZeroAlphaResultsDoNotAdmitTables() {
        val repeated = PaletteProbe()
        repeat(1024) { repeated.blend(0x807195B3.toInt(), 0xFF234567.toInt()) }
        assertEquals(31, repeated.remainingSamples())
        assertNull(repeated.rows())
        val discarded = PaletteProbe(cutoff = 1f)
        repeat(1024) { discarded.blend(source(it), 0xFF234567.toInt()) }
        assertEquals(32, discarded.remainingSamples())
        assertNull(discarded.rows())
        val zero = PaletteProbe(tint = 0x01020406)
        repeat(1024) { zero.blend(0x01000000 or (it and 0xFFFFFF), 0x001337AA) }
        assertEquals(32, zero.remainingSamples())
        assertNull(zero.rows())
    }

    @Test
    fun frameFailureClosesSharedScratchAfterUniformPaletteAdmission() {
        val size = IntSize(96, 64)
        val image = createDrawImage(size, IntArray(size.width * size.height, ::source))
        val bounds = IntRect(0, 0, size.width, size.height)
        val rectangle = FloatRect(0f, 0f, size.width.toFloat(), size.height.toFloat())
        val command = DrawCommand.SampledImage(image, rectangle, rectangle, ArgbColor(0x80A4C6E8.toInt()), 0f)
        val scratch = HeadlessRasterScratch()
        assertThrows(IllegalStateException::class.java) {
            scratch.use {
                rasterizeHeadlessInto(listOf(command), bounds, 1, IntArray(size.width * size.height), it)
                assertTrue(0 < it.retainedBytes)
                error("Injected failure between frame regions.")
            }
        }
        assertEquals(0, scratch.retainedBytes)
        assertThrows(IllegalStateException::class.java) { scratch.weights(-1) }
    }

    private fun source(index: Int): Int = (((index % 253) + 1) shl 24) or (index * 73471 and 0xFFFFFF)

    private class PaletteProbe(
        tint: Int = 0x80A4C6E8.toInt(),
        cutoff: Float = 0f,
    ) {
        // This literal names a reflected JVM implementation class, rather than a domain discriminator.
        @Suppress("StringLiteralComparison")
        private val type = SampledImageRasterizer::class.java.declaredClasses.single { it.simpleName == "SampledColor" }
        private val integer = checkNotNull(Int::class.javaPrimitiveType)
        private val color =
            type
                .getDeclaredConstructor(integer, checkNotNull(Float::class.javaPrimitiveType), checkNotNull(Boolean::class.javaPrimitiveType), SampledSourceWeights::class.java)
                .apply { isAccessible = true }
                .newInstance(tint, cutoff, true, null)
        private val blend = type.getDeclaredMethod("blend", integer, integer).apply { isAccessible = true }
        private val tables = type.getDeclaredField("blendTables").apply { isAccessible = true }
        private val remaining = type.getDeclaredField("remainingPaletteSamples").apply { isAccessible = true }

        /**
         * Drives the real command-local color state without creating diagnostic public contracts.
         */
        fun blend(
            source: Int,
            destination: Int,
        ) {
            blend.invoke(color, source, destination)
        }

        /**
         * Observes primitive row allocation directly, independently of timing and GC behavior.
         */
        @Suppress("UNCHECKED_CAST")
        fun rows(): Array<IntArray?>? = tables.get(color) as Array<IntArray?>?

        /**
         * Reports bounded scalar admission state for skipped-work checks.
         */
        fun remainingSamples(): Int = remaining.getInt(color)
    }
}
