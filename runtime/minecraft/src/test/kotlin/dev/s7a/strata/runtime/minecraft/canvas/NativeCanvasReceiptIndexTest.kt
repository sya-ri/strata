package dev.s7a.strata.runtime.minecraft.canvas

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.PlatformDrawCommand
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Verifies detached lookup association, original validation order and concurrent CPU-only capture without live devices. */
@OptIn(InternalStrataRuntimeApi::class)
internal class NativeCanvasReceiptIndexTest {
    @Test
    fun repeatedPlacementsMatchAnIndependentOrderedReceiptTableAtEveryMembershipSize() {
        for (size in listOf(0, 1, 16, 64)) {
            val tokens = List(size) { token(it.toLong()) }
            val images = List(size) { index -> createDrawImage(IntSize(2, 2), IntArray(4) { 0xFF112200.toInt() or (index * 4 + it) }) }
            val receipts = tokens.indices.map { NativeCanvasSnapshot(tokens[it], images[it]) }.reversed()
            for (occurrences in if (size == 0) listOf(0) else listOf(size, 64, 4096)) {
                val commands = List(occurrences) { index -> DrawCommand.Platform(tokens[index % size], IntRect(index % 8, 0, index % 8 + 1, 1)) }
                val captured = NativeCanvasPresentation(1L, 1L, commands, receipts).capture()
                assertEquals(occurrences, captured.size)
                captured.forEachIndexed { index, command ->
                    val blit = command as DrawCommand.BlitImagePixels
                    assertSame(images[index % size], blit.image)
                    assertEquals(IntRect(0, 0, 2, 2), blit.source)
                    assertEquals(commands[index].bounds, blit.destination)
                }
            }
        }
    }

    @Test
    fun duplicateReceiptsAreRejectedOnlyWhenTheirExactTokenIsRequested() {
        val requested = token(1L)
        val unused = token(2L)
        val image = createDrawImage(IntSize(2, 2), intArrayOf(-1, -2, -3, -4))
        val matching = NativeCanvasSnapshot(requested, image)
        val duplicate = NativeCanvasSnapshot(unused, image)
        val command = DrawCommand.Platform(requested, IntRect(0, 0, 1, 1))
        val presentation = NativeCanvasPresentation(1L, 1L, listOf(command), listOf(duplicate, matching, duplicate, duplicate))
        assertSame(image, (presentation.capture().single() as DrawCommand.BlitImagePixels).image)
        val unusedOnly = NativeCanvasPresentation(1L, 2L, listOf(DrawCommand.PushClip(IntRect(0, 0, 1, 1))), listOf(duplicate, duplicate))
        assertEquals(unusedOnly.drawCommands, unusedOnly.capture())
        for (copies in listOf(2, 3, 4)) {
            val ambiguous = NativeCanvasPresentation(1L, 3L, listOf(command), List(copies) { matching })
            assertEquals("Native canvas generation has no unique matching immutable snapshot.", failure(ambiguous))
        }
    }

    @Test
    fun commandOrderStillSelectsTheFirstValidationFailureBeforeAnyCaptureOutput() {
        val first = token(1L)
        val second = token(2L)
        val image = createDrawImage(IntSize(2, 2), IntArray(4) { -1 })
        val wrongExtent = createDrawImage(IntSize(1, 1), intArrayOf(-1))
        val receipts = listOf(NativeCanvasSnapshot(first, wrongExtent), NativeCanvasSnapshot(second, image), NativeCanvasSnapshot(second, image))
        val wrong = DrawCommand.Platform(first, IntRect(0, 0, 1, 1))
        val duplicate = DrawCommand.Platform(second, IntRect(1, 0, 2, 1))
        val foreign = DrawCommand.Platform(Unknown, IntRect(2, 0, 3, 1))
        assertEquals("Native canvas snapshot extent does not match its generation.", failure(NativeCanvasPresentation(1L, 1L, listOf(wrong, duplicate, foreign), receipts)))
        assertEquals("Native canvas generation has no unique matching immutable snapshot.", failure(NativeCanvasPresentation(1L, 2L, listOf(duplicate, wrong, foreign), receipts)))
        assertEquals("Portable capture requires a committed native canvas token and its snapshot.", failure(NativeCanvasPresentation(1L, 3L, listOf(foreign, wrong, duplicate), receipts)))
        val uncommitted = NativeCanvasPresentation(1L, 4L, listOf(foreign), receipts, hasUncommittedCanvases = true)
        assertEquals("Portable capture requires a committed generation for every requested canvas.", failure(uncommitted))
    }

    @Test
    fun scalarIdenticalForeignTokensNeverBorrowAnotherReceipt() {
        val original = token(1L)
        val foreign = token(1L)
        val image = createDrawImage(IntSize(2, 2), IntArray(4) { -1 })
        val other = token(2L)
        val presentation = NativeCanvasPresentation(1L, 1L, listOf(DrawCommand.Platform(foreign, IntRect(0, 0, 1, 1))), listOf(NativeCanvasSnapshot(original, image), NativeCanvasSnapshot(other, image)))
        assertEquals("Native canvas generation has no unique matching immutable snapshot.", failure(presentation))
    }

    @Test
    fun physicalTexelsAndClipOrderRemainExactAcrossConcurrentDetachedCaptures() {
        val first = token(1L)
        val second = token(2L)
        val pixels = intArrayOf(0xFF112233.toInt(), 0xFF445566.toInt(), 0xFF778899.toInt(), 0xFFAABBCC.toInt())
        val image = createDrawImage(IntSize(2, 2), pixels)
        val other = createDrawImage(IntSize(2, 2), IntArray(4) { -1 })
        val push = DrawCommand.PushClip(IntRect(0, 0, 1, 1))
        val commands = listOf(push, DrawCommand.Platform(first, IntRect(0, 0, 1, 1)), DrawCommand.PopClip)
        val presentation = NativeCanvasPresentation(1L, 1L, commands, listOf(NativeCanvasSnapshot(second, other), NativeCanvasSnapshot(first, image)))
        val tasks =
            List(8) {
                FutureTask {
                    var result = emptyList<DrawCommand>()
                    repeat(64) {
                        result = presentation.capture()
                        assertSame(push, result.first())
                        assertSame(DrawCommand.PopClip, result.last())
                        assertSame(image, (result[1] as DrawCommand.BlitImagePixels).image)
                        assertArrayEquals(pixels, rasterizeHeadless(result, IntSize(1, 1), 2).copyArgb())
                    }
                    result
                }.also { Thread(it).start() }
            }
        tasks.forEach { assertEquals(3, it.get(30L, TimeUnit.SECONDS).size) }
        assertEquals(commands, presentation.drawCommands)
    }

    private fun token(attachment: Long): NativeCanvasToken = NativeCanvasToken(1L, attachment, 1L, IntSize(2, 2))

    private fun failure(presentation: NativeCanvasPresentation): String? = assertThrows(IllegalStateException::class.java) { presentation.capture() }.message

    private object Unknown : PlatformDrawCommand
}
