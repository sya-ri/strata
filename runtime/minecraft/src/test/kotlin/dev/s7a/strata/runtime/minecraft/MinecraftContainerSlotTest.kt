@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.Image
import dev.s7a.strata.component.Slot
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.containerBackground
import dev.s7a.strata.modifier.size
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies exact generic-container and Slot geometry, command order, hover, and text-label style.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftContainerSlotTest {
    @Test
    fun containerBackgroundUsesExactNativeSizesAndTwoBlits() {
        listOf(1, 3, 6).forEach { rows ->
            val texture = image(IntSize(256, 256), 0xFF202020.toInt())
            val host =
                host(MinecraftProfileFixture.create(containerBackground = texture)) {
                    evaluateComponentTree { Stack(modifier = Modifier.Empty.containerBackground(rows)) {} }
                }
            val expectedHeight = Math.addExact(114, Math.multiplyExact(rows, 18))
            host.attach()
            val frame = host.frame(IntSize(176, expectedHeight))
            val commands = frame.drawCommands.filterIsInstance<DrawCommand.BlitImage>()
            val upperHeight = Math.addExact(Math.multiplyExact(rows, 18), 17)
            assertEquals(2, commands.size)
            assertEquals(IntRect(0, 0, 176, upperHeight), commands[0].source)
            assertEquals(IntRect(0, 0, 176, upperHeight), commands[0].destination)
            assertEquals(IntRect(0, 126, 176, 222), commands[1].source)
            assertEquals(IntRect(0, upperHeight, 176, Math.addExact(upperHeight, 96)), commands[1].destination)
            commands.forEach { command -> assertSame(texture, command.image) }
            assertEquals(IntSize(176, expectedHeight), frame.size)
            host.close()
        }
    }

    @Test
    fun containerRowsAndExactConstraintsAreValidated() {
        listOf(0, 7).forEach { rows ->
            val host = host { evaluateComponentTree { Stack(modifier = Modifier.Empty.containerBackground(rows)) {} } }
            assertThrows(IllegalArgumentException::class.java) { host.attach() }
            host.close()
        }
        val host = host { evaluateComponentTree { Stack(modifier = Modifier.Empty.containerBackground(3)) {} } }
        host.attach()
        assertThrows(IllegalArgumentException::class.java) { host.frame(IntSize(175, 168)) }
        host.close()
    }

    @Test
    fun containerBackgroundUsesOneStableModifierTypeAndExactUpdateMasks() {
        val firstImage = image(IntSize(256, 256), 0xFF202020.toInt())
        val secondImage = image(IntSize(256, 256), 0xFF303030.toInt())
        val first = createMinecraftContainerBackgroundModifier(firstImage, 3)
        val equal = createMinecraftContainerBackgroundModifier(firstImage, 3)
        val imageChanged = createMinecraftContainerBackgroundModifier(secondImage, 3)
        val rowsChanged = createMinecraftContainerBackgroundModifier(secondImage, 4)
        val node = first.type.createErased(first)

        assertSame(first.type, equal.type)
        assertEquals(DirtyMask.None, first.type.updateErased(first, equal, node))
        assertEquals(DirtyMask.of(DirtyPhase.Paint), first.type.updateErased(equal, imageChanged, node))
        assertEquals(DirtyMask.of(DirtyPhase.Measure), first.type.updateErased(imageChanged, rowsChanged, node))
    }

    @Test
    fun emptySlotIsCommandFreeUntilHoverAndUsesTheNativeExpandedHitRegion() {
        val back = image(IntSize(24, 24), 0xFF101010.toInt())
        val front = image(IntSize(24, 24), 0xFF202020.toInt())
        val host = host(MinecraftProfileFixture.create(slotHighlightBack = back, slotHighlightFront = front)) { evaluateComponentTree { Slot() } }
        host.attach()
        assertTrue(host.frame(IntSize(18, 18)).drawCommands.isEmpty())

        assertSame(InputResult.Ignored, host.dispatchPointer(PointerEvent.Move(IntOffset(17, 17))))
        val hovered = host.frame(IntSize(18, 18)).drawCommands.filterIsInstance<DrawCommand.BlitImage>()
        assertEquals(2, hovered.size)
        assertSame(back, hovered[0].image)
        assertSame(front, hovered[1].image)
        assertEquals(IntRect(0, 0, 24, 24), hovered[0].source)
        assertEquals(IntRect(-3, -3, 21, 21), hovered[0].destination)
        assertEquals(hovered[0].destination, hovered[1].destination)

        assertSame(InputResult.Ignored, host.dispatchPointer(PointerEvent.Move(IntOffset(18, 17))))
        assertTrue(host.frame(IntSize(18, 18)).drawCommands.isEmpty())
        host.close()
    }

    @Test
    fun highlightedSlotPaintsBackThenItemThenFront() {
        val back = image(IntSize(24, 24), 0xFF101010.toInt())
        val frontPixels = IntArray(24 * 24) { 0x00000000 }
        frontPixels[3 + 3 * 24] = 0xFF303030.toInt()
        val front = createDrawImage(IntSize(24, 24), frontPixels)
        val itemColor = ArgbColor(0xFF22D3EE.toInt())
        val host =
            host(MinecraftProfileFixture.create(slotHighlightBack = back, slotHighlightFront = front)) {
                evaluateComponentTree {
                    Slot {
                        Spacer(Modifier.Empty.size(16, 16).background(itemColor))
                    }
                }
            }
        host.attach()
        val initial = host.frame(IntSize(18, 18))
        assertEquals(listOf(DrawCommand.FillRectangle(IntRect(1, 1, 17, 17), itemColor)), initial.drawCommands)

        host.dispatchPointer(PointerEvent.Move(IntOffset(0, 0)))
        val hovered = host.frame(IntSize(18, 18))
        assertEquals(3, hovered.drawCommands.size)
        assertTrue(hovered.drawCommands[0] is DrawCommand.BlitImage)
        assertTrue(hovered.drawCommands[1] is DrawCommand.FillRectangle)
        assertTrue(hovered.drawCommands[2] is DrawCommand.BlitImage)
        val rendered = rasterizeHeadless(hovered.drawCommands, IntSize(18, 18))
        assertEquals(0xFF303030.toInt(), rendered.argbAt(0, 0))
        assertEquals(itemColor.value, rendered.argbAt(1, 1))
        host.close()
    }

    @Test
    fun nonhighlightableSlotNeverPaintsHighlightLayers() {
        val host = host { evaluateComponentTree { Slot(highlightable = false) } }
        host.attach()
        host.frame(IntSize(18, 18))
        host.dispatchPointer(PointerEvent.Move(IntOffset(5, 5)))
        assertTrue(host.frame(IntSize(18, 18)).drawCommands.isEmpty())
        host.close()
    }

    @Test
    fun containerLabelUsesOneShadowFreeDarkCommandPerGlyph() {
        val host = host { evaluateComponentTree { Text("AB", style = TextStyle.ContainerLabel) } }
        host.attach()
        val frame = host.frame(IntSize(5, 9))
        val commands = frame.drawCommands.filterIsInstance<DrawCommand.BlitImage>()
        assertEquals(2, commands.size)
        assertEquals(0xFF404040.toInt(), commands[0].image.argbAt(0, 0))
        assertEquals(0xFF404040.toInt(), commands[1].image.argbAt(1, 0))
        assertEquals(listOf(IntRect(0, 0, 8, 8), IntRect(2, 0, 10, 8)), commands.map { command -> command.destination })
        host.close()
    }

    private fun host(
        profile: MinecraftUiProfile = MinecraftProfileFixture.create(),
        content: () -> Element,
    ): MinecraftUiHost =
        createMinecraftUiHost(
            ScreenDefinition(UiText.Literal("container")) { element(content()) },
            profile,
        )

    private fun image(
        size: IntSize,
        color: Int,
    ): DrawImage = createDrawImage(size, IntArray(Math.multiplyExact(size.width, size.height)) { color })
}
