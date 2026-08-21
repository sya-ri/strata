package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.dsl.Spacer
import dev.s7a.strata.dsl.buildUi
import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies the retained Minecraft 26.2 menu-list Scroll component.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftScrollTest {
    @Test
    fun scrollPaintsBackgroundContentSeparatorsTrackAndThumbInNativeOrder() {
        val assets = ScrollAssets()
        val host = host(assets.profile())
        host.attach()

        val frame = host.frame(viewport)
        val commands = frame.drawCommands
        val pushIndex = commands.indexOfFirst { command -> command is DrawCommand.PushClip }
        val popIndex = commands.indexOfFirst { command -> command is DrawCommand.PopClip }
        assertTrue(0 < pushIndex)
        assertTrue(pushIndex < popIndex)
        commands.take(pushIndex).forEach { command ->
            check(command is DrawCommand.BlitImage)
            assertSame(assets.listBackground, command.image)
        }
        assertEquals(IntRect(0, 0, viewport.width, viewport.height), (commands[pushIndex] as DrawCommand.PushClip).bounds)
        assertEquals(
            IntRect(25, 2, 295, 182),
            commands
                .subList(pushIndex + 1, popIndex)
                .filterIsInstance<DrawCommand.FillRectangle>()
                .single()
                .bounds,
        )

        val overlay = commands.drop(popIndex + 1).filterIsInstance<DrawCommand.BlitImage>()
        assertEquals(List(10) { assets.headerSeparator }, overlay.take(10).map { command -> command.image })
        assertEquals(List(10) { assets.footerSeparator }, overlay.drop(10).take(10).map { command -> command.image })
        assertEquals(List(6) { assets.scrollbarBackground }, overlay.drop(20).take(6).map { command -> command.image })
        assertEquals(List(4) { assets.scrollbarThumb }, overlay.drop(26).take(4).map { command -> command.image })
        assertEquals(IntRect(303, 0, 309, 1), overlay[20].destination)
        assertEquals(IntRect(303, 93, 309, 94), overlay[25].destination)
        assertEquals(IntRect(303, 0, 309, 1), overlay[26].destination)
        assertEquals(IntRect(303, 47, 309, 48), overlay[29].destination)

        val image = rasterizeHeadless(commands, viewport)
        assertEquals(contentColor.value, image.argbAt(25, 2))
        assertEquals(assets.scrollbarBackground.argbAt(0, 0), image.argbAt(303, 60))
        host.close()
    }

    @Test
    fun wheelScrollUsesRateClampsAtMaximumAndRelocatesCachedContent() {
        val assets = ScrollAssets()
        val host = host(assets.profile())
        host.attach()
        host.frame(viewport)

        assertEquals(InputResult.Consumed, host.dispatchPointer(PointerEvent.Scroll(IntOffset(10, 10), 0.0, 1.0)))
        val oneStep = host.frame(viewport)
        assertEquals(
            IntRect(25, -7, 295, 173),
            oneStep.drawCommands
                .filterIsInstance<DrawCommand.FillRectangle>()
                .single()
                .bounds,
        )
        val oneStepThumb = oneStep.drawCommands.filterIsInstance<DrawCommand.BlitImage>().filter { command -> command.image === assets.scrollbarThumb }
        assertEquals(IntRect(303, 4, 309, 5), oneStepThumb.first().destination)

        assertEquals(InputResult.Consumed, host.dispatchPointer(PointerEvent.Scroll(IntOffset(10, 10), 0.0, Double.MAX_VALUE)))
        val maximum = host.frame(viewport)
        assertEquals(
            IntRect(25, -88, 295, 92),
            maximum.drawCommands
                .filterIsInstance<DrawCommand.FillRectangle>()
                .single()
                .bounds,
        )
        val maximumThumb = maximum.drawCommands.filterIsInstance<DrawCommand.BlitImage>().filter { command -> command.image === assets.scrollbarThumb }
        assertEquals(IntRect(303, 46, 309, 47), maximumThumb.first().destination)
        host.close()
    }

    @Test
    fun primaryScrollbarDragUsesNativeProportionalMovementAndRelease() {
        val assets = ScrollAssets()
        val host = host(assets.profile())
        host.attach()
        host.frame(viewport)

        assertEquals(InputResult.Ignored, host.dispatchPointer(PointerEvent.Press(IntOffset(302, 10), PointerButton.Primary)))
        assertEquals(InputResult.Ignored, host.dispatchPointer(PointerEvent.Press(IntOffset(303, 10), PointerButton.Secondary)))
        assertEquals(InputResult.Consumed, host.dispatchPointer(PointerEvent.Press(IntOffset(303, 10), PointerButton.Primary)))
        assertEquals(
            InputResult.Consumed,
            host.dispatchPointer(PointerEvent.Drag(IntOffset(303, 20), PointerButton.Primary, 0.0, 10.0)),
        )
        val dragged = host.frame(viewport)
        assertEquals(
            IntRect(25, -17, 295, 163),
            dragged.drawCommands
                .filterIsInstance<DrawCommand.FillRectangle>()
                .single()
                .bounds,
        )
        assertEquals(InputResult.Consumed, host.dispatchPointer(PointerEvent.Release(IntOffset(303, 20), PointerButton.Primary)))
        assertEquals(
            InputResult.Ignored,
            host.dispatchPointer(PointerEvent.Drag(IntOffset(303, 30), PointerButton.Primary, 0.0, 10.0)),
        )
        host.close()
    }

    @Test
    fun scrollRejectsUnboundedAxesInvalidRatesAndInvalidContentCardinality() {
        var description: Element? = null
        val host = host(MinecraftProfileFixture.create()) { buildUi { Scroll { Spacer(modifier = Modifier.Empty.size(10, 60)) } }.also { description = it } }
        host.attach()
        host.frame(IntSize(20, 40))
        host.close()

        listOf(
            Constraints(maxWidth = Int.MAX_VALUE, maxHeight = 40),
            Constraints(maxWidth = 20, maxHeight = Int.MAX_VALUE),
        ).forEach { constraints ->
            val tree = UiTree()
            try {
                tree.update(checkNotNull(description))
                assertThrows(IllegalArgumentException::class.java) { tree.measure(constraints) }
            } finally {
                tree.close()
            }
        }

        val invalidRate = host(MinecraftProfileFixture.create()) { buildUi { Scroll(scrollRate = 0) { Spacer() } } }
        assertThrows(IllegalArgumentException::class.java) { invalidRate.attach() }
        invalidRate.close()

        val empty = host(MinecraftProfileFixture.create()) { buildUi { Scroll {} } }
        assertThrows(IllegalArgumentException::class.java) { empty.attach() }
        empty.close()

        val multiple =
            host(MinecraftProfileFixture.create()) {
                buildUi {
                    Scroll {
                        Spacer()
                        Spacer()
                    }
                }
            }
        assertThrows(IllegalArgumentException::class.java) { multiple.attach() }
        multiple.close()
    }

    @Test
    fun nonScrollableContentOmitsScrollbarAndKeepsNativeTwoPixelOrigin() {
        val assets = ScrollAssets()
        val host =
            host(assets.profile()) {
                buildUi {
                    Scroll {
                        Spacer(modifier = Modifier.Empty.size(80, 20).background(contentColor))
                    }
                }
            }
        host.attach()
        val frame = host.frame(IntSize(100, 40))
        assertEquals(
            IntRect(10, 2, 90, 22),
            frame.drawCommands
                .filterIsInstance<DrawCommand.FillRectangle>()
                .single()
                .bounds,
        )
        val images = frame.drawCommands.filterIsInstance<DrawCommand.BlitImage>().map { command -> command.image }
        assertTrue(images.none { image -> image === assets.scrollbarBackground || image === assets.scrollbarThumb })
        assertEquals(InputResult.Consumed, host.dispatchPointer(PointerEvent.Scroll(IntOffset(5, 5), 0.0, 1.0)))
        assertEquals(
            IntRect(10, 2, 90, 22),
            host
                .frame(IntSize(100, 40))
                .drawCommands
                .filterIsInstance<DrawCommand.FillRectangle>()
                .single()
                .bounds,
        )
        host.close()
    }

    private fun host(profile: MinecraftUiProfile): MinecraftUiHost = host(profile) { scrollContent() }

    private fun host(
        profile: MinecraftUiProfile,
        content: () -> Element,
    ): MinecraftUiHost = createMinecraftUiHost(createMinecraftScreenDefinition(UiText.Literal("scroll")) { element(content()) }, profile)

    private fun scrollContent(): Element =
        buildUi {
            Scroll {
                Spacer(modifier = Modifier.Empty.size(270, 180).background(contentColor))
            }
        }

    private class ScrollAssets {
        val listBackground: DrawImage = image(IntSize(16, 16), 0xFF101010.toInt())
        val headerSeparator: DrawImage = image(IntSize(32, 2), 0xFF202020.toInt())
        val footerSeparator: DrawImage = image(IntSize(32, 2), 0xFF303030.toInt())
        val scrollbarBackground: DrawImage = image(IntSize(6, 32), 0xFF404040.toInt())
        val scrollbarThumb: DrawImage = image(IntSize(6, 32), 0xFF505050.toInt())

        fun profile(): MinecraftUiProfile =
            MinecraftProfileFixture.create(
                listBackground = listBackground,
                listHeaderSeparator = headerSeparator,
                listFooterSeparator = footerSeparator,
                scrollbarBackground = scrollbarBackground,
                scrollbarThumb = scrollbarThumb,
            )
    }

    private companion object {
        private val viewport = IntSize(320, 94)
        private val contentColor = ArgbColor(0xFFABCDEF.toInt())

        private fun image(
            size: IntSize,
            color: Int,
        ): DrawImage = createDrawImage(size, IntArray(Math.multiplyExact(size.width, size.height)) { color })
    }
}
