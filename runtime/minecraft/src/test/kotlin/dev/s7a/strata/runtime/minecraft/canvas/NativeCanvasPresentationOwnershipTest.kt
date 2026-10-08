package dev.s7a.strata.runtime.minecraft.canvas

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Proves the internal completed-list handoff and public preparation's caller-membership isolation.
 * Lifecycle changes may replace live generations but never alter detached earlier commands or capture pixels.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class NativeCanvasPresentationOwnershipTest {
    @Test
    fun completedMembershipIsTransferredWithoutAnotherCommandOrReceiptCopy() {
        val image = createDrawImage(IntSize(1, 1), intArrayOf(0xFF123456.toInt()))
        val tokens = List(16) { NativeCanvasToken(1L, it.toLong() + 1L, 1L, image.size) }
        val commands = buildList { tokens.forEach { add(DrawCommand.Platform(it, IntRect(0, 0, 1, 1))) } }
        val receipts = buildList { tokens.forEach { add(NativeCanvasSnapshot(it, image)) } }
        val presentation = NativeCanvasPresentation(1L, 1L, commands, receipts)

        assertSame(commands, presentation.drawCommands)
        val receiptField = NativeCanvasPresentation::class.java.getDeclaredField("snapshots")
        receiptField.isAccessible = true
        assertSame(receipts, receiptField.get(presentation))
        presentation.capture().forEach { assertSame(image, (it as DrawCommand.BlitImagePixels).image) }
    }

    @Test
    fun prepareDetachesMutableInputsForZeroOneAndManyCanvasesWithinSmallAndLargePortableLists() {
        for (canvasCount in listOf(0, 1, 16)) {
            for (portableCount in listOf(1, 10_000)) {
                NativeCanvasFixture().use { fixture ->
                    val requests = List(canvasCount) { fixture.frame(fixture.tree()).single() }
                    val fill = DrawCommand.FillRectangle(IntRect(0, 0, 2, 2), ArgbColor(0xFF102030.toInt()))
                    val commands =
                        buildList {
                            add(DrawCommand.PushClip(IntRect(0, 0, 1, 2)))
                            repeat(portableCount) { add(fill) }
                            addAll(requests)
                            add(DrawCommand.PopClip)
                            add(fill)
                        }.toMutableList()
                    val presentation = fixture.device.prepare(commands, FrameTime(1L), 1)
                    val original = presentation.drawCommands.toList()
                    val captured = presentation.capture()
                    val pixels = rasterizeHeadless(captured, IntSize(2, 2)).copyArgb()
                    commands.clear()

                    assertEquals(portableCount + canvasCount + 3, presentation.drawCommands.size)
                    assertEquals(original, presentation.drawCommands)
                    assertEquals(captured, presentation.capture())
                    assertArrayEquals(pixels, rasterizeHeadless(presentation.capture(), IntSize(2, 2)).copyArgb())
                    fixture.device.cancel(presentation)
                }
            }
        }
    }

    @Test
    fun oldMembershipAndPixelsSurviveReusedTargetsReloadFailureAndTerminalRelease() {
        val fixture = NativeCanvasFixture()
        try {
            val tree = fixture.tree()
            val first = fixture.prepare(tree)
            val commands = first.drawCommands
            val captured = first.capture()
            val pixels = rasterizeHeadless(captured, IntSize(2, 2)).copyArgb()
            fixture.submit(first)
            repeat(8) { index ->
                fixture.driver.signalAll()
                fixture.producers.single().color = 0xFF000000.toInt() or index
                val next = fixture.prepare(tree)
                fixture.submit(next)
                assertSame(commands, first.drawCommands)
                assertEquals(captured, first.capture())
                assertArrayEquals(pixels, rasterizeHeadless(first.capture(), IntSize(2, 2)).copyArgb())
            }
            fixture.device.reload()
            fixture.driver.signalAll()
            val replacement = fixture.prepare(tree)
            fixture.submit(replacement)
            fixture.driver.signalAll()
            fixture.producers.last().renderFailure = IllegalStateException("Rejected replacement capture")
            assertThrows(IllegalStateException::class.java) { fixture.prepare(tree) }
            fixture.close()

            assertEquals(0, fixture.device.retainedTargetCount())
            assertSame(commands, first.drawCommands)
            assertEquals(captured, first.capture())
            assertArrayEquals(pixels, rasterizeHeadless(first.capture(), IntSize(2, 2)).copyArgb())
        } finally {
            fixture.producers.forEach { it.renderFailure = null }
            fixture.close()
        }
    }
}
