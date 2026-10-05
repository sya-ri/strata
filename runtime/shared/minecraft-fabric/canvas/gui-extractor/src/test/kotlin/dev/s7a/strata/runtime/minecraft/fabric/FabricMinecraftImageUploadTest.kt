package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Verifies packed native pixel order, row order, alpha preservation and checked preflight without loading a game.
 */
internal class FabricMinecraftImageUploadTest {
    @Test
    fun copyPreservesAllArgbBitsAndNeverWritesOutsideItsExtent() {
        val storage = ByteBuffer.allocateDirect(8 * Int.SIZE_BYTES).order(ByteOrder.nativeOrder())
        val address = MemoryUtil.memAddress(storage)
        storage.putInt(0, 0x12345678)
        storage.putInt(7 * Int.SIZE_BYTES, 0x23456789)
        val pixels = intArrayOf(0, -1, 0x80FF0000.toInt(), 0x4000FF00, 0x010000FF, 0x00123456)
        writeFabricMinecraftArgbPixels(address + Int.SIZE_BYTES, IntSize(3, 2)) { x, y -> pixels[y * 3 + x] }
        val expected = intArrayOf(0, -1, 0x800000FF.toInt(), 0x4000FF00, 0x01FF0000, 0x00563412)
        expected.forEachIndexed { index, value -> assertEquals(value, storage.getInt((index + 1) * Int.SIZE_BYTES)) }
        assertEquals(0x12345678, storage.getInt(0))
        assertEquals(0x23456789, storage.getInt(7 * Int.SIZE_BYTES))
    }

    @Test
    fun invalidInputFailsBeforeReadingPixels() {
        val pixel: (Int, Int) -> Int = { _, _ -> error("Invalid extents must not read pixels") }
        assertThrows(IllegalArgumentException::class.java) { writeFabricMinecraftArgbPixels(0L, IntSize(1, 1), pixel) }
        assertThrows(IllegalArgumentException::class.java) { writeFabricMinecraftArgbPixels(1L, IntSize(0, 1), pixel) }
        assertThrows(ArithmeticException::class.java) { writeFabricMinecraftArgbPixels(1L, IntSize(Int.MAX_VALUE, 2), pixel) }
        assertThrows(ArithmeticException::class.java) { writeFabricMinecraftArgbPixels(Long.MAX_VALUE, IntSize(1, 1), pixel) }
    }
}
