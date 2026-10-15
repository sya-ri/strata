package dev.s7a.strata.runtime.minecraft.canvas

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * Verifies the candidate-only completed-list handoff without weakening the shared defensive and terminal controls.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class NativeCanvasPresentationTransferTest {
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
}
