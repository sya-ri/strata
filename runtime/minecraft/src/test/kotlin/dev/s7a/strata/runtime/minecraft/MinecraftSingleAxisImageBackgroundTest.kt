@file:Suppress("DEPRECATION") // Verify the compatibility host entry point used by current consumers.

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.ImageScale
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.PlayerSkinSource
import dev.s7a.strata.component.ScrollArea
import dev.s7a.strata.component.ScrollState
import dev.s7a.strata.component.SlotBinding
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.integration.minecraft.fabric.MinecraftTileBackgroundReference
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.ModifierElement
import dev.s7a.strata.modifier.ModifierNodeType
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.fillMaxSize
import dev.s7a.strata.modifier.imageBackground
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.scaleToFit
import dev.s7a.strata.modifier.semantics
import dev.s7a.strata.modifier.size
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.ModifierNode
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.spi.ComponentRuntimeBridge
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Exercises ordinary Tile backgrounds through real profile hosts and an independent scalar/pixel reference.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftSingleAxisImageBackgroundTest {
    @Test
    fun genuineProducerCountsAndCropsCoverBothSingleAxesAndSmallExactIncompleteAndZeroOutputs() {
        for (sourceSize in listOf(IntSize(1, 1), IntSize(1, 8), IntSize(8, 1), IntSize(1, 257), IntSize(257, 1))) {
            val source = patterned(sourceSize)
            for (size in listOf(IntSize(1, 1), IntSize(8, 8), IntSize(17, 13), IntSize(320, 180), IntSize(641, 367), IntSize(0, 4), IntSize(4, 0))) {
                val commands = MinecraftTileProducerProbe.collect(source, size, ImageScale.Tile)
                val columns = if (sourceSize.width == 1) 1 else (size.width + sourceSize.width - 1) / sourceSize.width
                val rows = if (sourceSize.height == 1) 1 else (size.height + sourceSize.height - 1) / sourceSize.height
                assertEquals(if (size.width == 0 || size.height == 0) 0 else columns * rows, commands.size)
                for ((image, crop, destination) in commands) {
                    assertSame(source, image)
                    assertEquals(IntRect(0, 0, minOf(sourceSize.width, destination.width), minOf(sourceSize.height, destination.height)), crop)
                }
                if (commands.isNotEmpty()) {
                    assertEquals(size.width, commands.last().third.right)
                    assertEquals(size.height, commands.last().third.bottom)
                }
            }
        }
        val vertical = MinecraftTileProducerProbe.collect(patterned(IntSize(1, 8)), IntSize(320, 180), ImageScale.Tile)
        assertEquals(IntRect(0, 0, 1, 4), vertical.last().second)
        assertEquals(IntRect(0, 176, 320, 180), vertical.last().third)
    }

    @Test
    fun multiTexelEqualPixelsStretchAndNineSliceRemainIndependentControls() {
        val equal = createDrawImage(IntSize(2, 2), IntArray(4) { 0x80112233.toInt() })
        val size = IntSize(17, 13)
        val tiles = MinecraftTileProducerProbe.collect(equal, size, ImageScale.Tile)
        assertEquals(63, tiles.size)
        assertEquals(MinecraftTileBackgroundReference.scalar(equal, size).map { Triple(it.image, it.source, it.destination) }, tiles)
        val stretched = MinecraftTileProducerProbe.collect(equal, size, ImageScale.Stretch).single()
        assertSame(equal, stretched.first)
        assertEquals(IntRect(0, 0, 2, 2), stretched.second)
        assertEquals(IntRect(0, 0, 17, 13), stretched.third)
        val nine = patterned(IntSize(3, 3))
        createMinecraftUiHost(
            ScreenDefinition("NineSlice control") { Stack(Modifier.Empty.imageBackground(ImageSource.Pixels(nine), Insets.all(1))) {} },
            MinecraftProfileFixture.create(),
        ).use { host ->
            host.attach()
            val frame = host.frame(size)
            assertEquals(9, frame.drawCommands.size)
            assertSame(frame, host.frame(size))
        }
    }

    @Test
    fun integerOutputMatchesIndependentModuloAndOrderedArgbOverHeterogeneousBackgroundsAtAllDensities() {
        for (sourceSize in listOf(IntSize(1, 1), IntSize(1, 8), IntSize(8, 1), IntSize(1, 65), IntSize(65, 1), IntSize(2, 3))) {
            for (size in listOf(IntSize(17, 13), IntSize(320, 180))) {
                val image = patterned(sourceSize)
                val background = patterned(size, phase = 3)
                val bounds = IntRect(0, 0, size.width, size.height)
                val prefix = DrawCommand.BlitImage(background, bounds, bounds)
                backgroundHost(image).use { host ->
                    host.attach()
                    val frame = host.frame(size)
                    for (density in 1..4) {
                        val expected = MinecraftTileBackgroundReference.modulo(image, background, size, density)
                        assertArrayEquals(expected, rasterizeHeadless(listOf(prefix) + frame.drawCommands, size, density).copyArgb(), "source=" + sourceSize + " density=" + density)
                        assertArrayEquals(expected, MinecraftTileBackgroundReference.pixels(listOf(prefix) + MinecraftTileBackgroundReference.scalar(image, size), size, density))
                    }
                    assertSame(frame, host.frame(size))
                }
            }
        }
        for (alpha in listOf(0, 1, 64, 128, 254, 255)) {
            val size = IntSize(19, 11)
            val image = createDrawImage(IntSize(1, 1), intArrayOf((alpha shl 24) or 0xB56231))
            val background = patterned(size)
            backgroundHost(image).use { host ->
                host.attach()
                val bounds = IntRect(0, 0, 19, 11)
                val commands = listOf(DrawCommand.BlitImage(background, bounds, bounds)) + host.frame(size).drawCommands
                assertArrayEquals(MinecraftTileBackgroundReference.modulo(image, background, size, 1), rasterizeHeadless(commands, size).copyArgb())
            }
        }
    }

    @Test
    fun fractionalFitsKeepExactScalarCellsSharedEdgesAndPixelCenterClipsWhenTheViewportChanges() {
        val design = IntSize(19, 13)
        for (sourceSize in listOf(IntSize(1, 1), IntSize(1, 8), IntSize(8, 1), IntSize(2, 3))) {
            val image = patterned(sourceSize)
            backgroundHost(image, design).use { host ->
                host.attach()
                val original = host.frame(design)
                val oldPixels = rasterizeHeadless(original.drawCommands, design).copyArgb()
                for (viewport in listOf(IntSize(13, 9), IntSize(8, 5), IntSize(38, 26), IntSize(20, 15), design)) {
                    val actual = host.frame(viewport)
                    assertFractionalPixels(image, design, viewport, actual.drawCommands)
                    assertSame(actual, host.frame(viewport))
                    assertArrayEquals(oldPixels, rasterizeHeadless(original.drawCommands, design).copyArgb())
                }
            }
        }
    }

    @Test
    fun dirtyPixelsSizeKeyRemovalDetachAndIndependentHostsPreserveOldFramesInputSemanticsAndScroll() {
        val initial = patterned(IntSize(1, 8))
        val source = ReactiveTestSource<DrawImage?>(initial)
        val scroll = ScrollState()
        var presses = 0
        var evaluations = 0
        val definition =
            ScreenDefinition("Observed singleton tiles") {
                evaluations += 1
                ScrollArea(scroll, Modifier.Empty.size(60, 40)) {
                    Observe(source) { image ->
                        if (image != null) {
                            Stack(
                                Modifier.Empty
                                    .size(60, 100)
                                    .imageBackground(ImageSource.Pixels(image), ImageScale.Tile)
                                    .semantics(Semantics(label = UiText.Literal("Retained action")))
                                    .onPress { presses += 1 },
                                key = ElementKey("image"),
                            ) {}
                        }
                    }
                }
            }
        val size = IntSize(60, 40)
        createMinecraftUiHost(definition, MinecraftProfileFixture.create()).use { host ->
            host.attach()
            host.frame(size)
            scroll.scrollTo(10.25)
            val before = host.frame(size)
            val saved = rasterizeHeadless(before.drawCommands, size).copyArgb()
            assertEquals(InputResult.Consumed, host.dispatchPointer(PointerEvent.Press(IntOffset(5, 5), PointerButton.Primary)))
            source.publish(patterned(IntSize(8, 1), phase = 5))
            val after = host.frame(size)
            assertNotSame(before, after)
            assertFalse(saved.contentEquals(rasterizeHeadless(after.drawCommands, size).copyArgb()))
            assertEquals(before.semantics.map { it.semantics }, after.semantics.map { it.semantics })
            assertEquals(10.25, scroll.metrics.offset)
            assertSame(after, host.frame(size))
            assertArrayEquals(saved, rasterizeHeadless(before.drawCommands, size).copyArgb())
            source.publish(null)
            val removed = host.frame(size)
            assertTrue(removed.semantics.isEmpty())
            assertArrayEquals(saved, rasterizeHeadless(before.drawCommands, size).copyArgb())
            source.publish(initial)
            val returned = host.frame(size)
            host.detach()
            assertThrows(IllegalStateException::class.java) { host.frame(size) }
            host.attach()
            val attached = host.frame(size)
            assertArrayEquals(rasterizeHeadless(returned.drawCommands, size).copyArgb(), rasterizeHeadless(attached.drawCommands, size).copyArgb())
            assertEquals(InputResult.Consumed, host.dispatchPointer(PointerEvent.Press(IntOffset(5, 5), PointerButton.Primary)))
            assertEquals(2, presses)
            assertIndependentHostFrame(initial, host, size)
        }
        val closedEvaluations = evaluations
        source.publish(patterned(IntSize(1, 1)))
        assertEquals(closedEvaluations, evaluations)
    }

    @Test
    fun backgroundBeforeContentAndPublicSourceCutoffRemainOrdered() {
        val first = patterned(IntSize(1, 8))
        val next = patterned(IntSize(8, 1), phase = 5)
        val source = ReactiveTestSource(first)
        var published = false
        val callback =
            CallbackElement {
                if (published.not()) {
                    published = true
                    source.publish(next)
                }
            }
        val size = IntSize(17, 13)
        createMinecraftUiHost(
            ScreenDefinition("Source cutoff") {
                Observe(source) { image ->
                    Stack(Modifier.Empty.then(callback).imageBackground(ImageSource.Pixels(image), ImageScale.Tile)) {
                        Spacer(Modifier.Empty.size(3, 2).background(ArgbColor(0x80442288.toInt())))
                    }
                }
            },
            MinecraftProfileFixture.create(),
        ).use { host ->
            host.attach()
            val before = host.frame(size)
            val overlay = createDrawImage(IntSize(1, 1), intArrayOf(0x80442288.toInt()))
            val content = DrawCommand.BlitImage(overlay, IntRect(0, 0, 1, 1), IntRect(0, 0, 3, 2))
            assertArrayEquals(MinecraftTileBackgroundReference.pixels(MinecraftTileBackgroundReference.scalar(first, size) + content, size, 1), rasterizeHeadless(before.drawCommands, size).copyArgb())
            val after = host.frame(size)
            assertArrayEquals(MinecraftTileBackgroundReference.pixels(MinecraftTileBackgroundReference.scalar(next, size) + content, size, 1), rasterizeHeadless(after.drawCommands, size).copyArgb())
            assertSame(after, host.frame(size))
        }
    }

    @Test
    fun resourceReplacementRetainsCurrentSourceAndTerminalCloseClearsResolverOwnership() {
        val firstId = ResourceId("test", "textures/vertical.png")
        val nextId = ResourceId("test", "textures/horizontal.png")
        val first = patterned(IntSize(1, 8))
        val next = patterned(IntSize(8, 1))
        val source = ReactiveTestSource(firstId)
        val platform = ImagePlatform(mapOf(firstId to first, nextId to next))
        var resolver: Any? = null
        val definition =
            ScreenDefinition("Resource singleton tiles") {
                val runtime = ComponentRuntimeBridge.current()
                val field = runtime.javaClass.getDeclaredField("resourceImages").apply { check(trySetAccessible()) }
                resolver = field.get(runtime)
                Observe(source) { id -> Stack(Modifier.Empty.imageBackground(ImageSource.Resource(id), ImageScale.Tile)) {} }
            }
        val host = createMinecraftUiHost(definition, MinecraftProfileFixture.create(), platform)
        host.attach()
        val size = IntSize(17, 13)
        val before = host.frame(size)
        val saved = rasterizeHeadless(before.drawCommands, size).copyArgb()
        source.publish(nextId)
        val after = host.frame(size)
        assertEquals(3, after.drawCommands.size)
        assertTrue(after.drawCommands.filterIsInstance<DrawCommand.BlitImage>().all { it.image === next })
        assertArrayEquals(saved, rasterizeHeadless(before.drawCommands, size).copyArgb())
        assertEquals(2, platform.calls)
        host.close()
        host.close()
        assertEquals(1, platform.closes)
        val retained = checkNotNull(resolver)
        val images = retained.javaClass.getDeclaredField("images").apply { check(trySetAccessible()) }
        assertTrue((images.get(retained) as Map<*, *>).isEmpty())
    }

    /**
     * Checks the same independent pixels and scalar cells for one fitted viewport at every tested density.
     */
    private fun assertFractionalPixels(
        image: DrawImage,
        design: IntSize,
        viewport: IntSize,
        actualCommands: List<DrawCommand>,
    ) {
        val reference = MinecraftTileBackgroundReference.fitted(image, design, viewport)
        val background = patterned(viewport, phase = 4)
        val bounds = IntRect(0, 0, viewport.width, viewport.height)
        val prefix =
            listOf(
                DrawCommand.BlitImage(background, bounds, bounds),
                DrawCommand.PushFractionalClip(FloatRect(0.25f, 0.75f, viewport.width - 0.5f, viewport.height - 0.25f)),
                DrawCommand.PushClip(IntRect(1, 1, viewport.width - 1, viewport.height - 1)),
            )
        val suffix = listOf(DrawCommand.PopClip, DrawCommand.PopClip)
        for (density in 1..4) {
            val expected = MinecraftTileBackgroundReference.pixels(prefix + reference + suffix, viewport, density)
            val actualPixels = rasterizeHeadless(prefix + actualCommands + suffix, viewport, density).copyArgb()
            if (viewport == design) {
                assertArrayEquals(MinecraftTileBackgroundReference.pixels(prefix + MinecraftTileBackgroundReference.scalar(image, design) + suffix, viewport, density), actualPixels)
            } else {
                assertArrayEquals(expected, actualPixels, "source=" + image.size + " viewport=" + viewport + " density=" + density)
                assertEquals(reference, actualCommands)
            }
        }
    }

    /**
     * Confirms that resizing the primary host preserves another host's unchanged frame identity.
     */
    private fun assertIndependentHostFrame(
        image: DrawImage,
        host: MinecraftUiHost,
        size: IntSize,
    ) {
        backgroundHost(image).use { independent ->
            independent.attach()
            val independentFrame = independent.frame(size)
            host.frame(IntSize(61, 41))
            assertSame(independentFrame, independent.frame(size))
        }
    }

    private fun backgroundHost(
        image: DrawImage,
        design: IntSize? = null,
    ): MinecraftUiHost =
        createMinecraftUiHost(
            ScreenDefinition("Single-axis background") {
                val scale = design?.let { Modifier.Empty.scaleToFit(it, allowUpscaling = true) } ?: Modifier.Empty
                Stack(scale) { Stack(Modifier.Empty.fillMaxSize().imageBackground(ImageSource.Pixels(image), ImageScale.Tile)) {} }
            },
            MinecraftProfileFixture.create(),
        )

    private fun patterned(
        size: IntSize,
        phase: Int = 0,
    ): DrawImage {
        val alphas = intArrayOf(0, 1, 64, 128, 254, 255)
        return createDrawImage(
            size,
            IntArray(size.width * size.height) { index ->
                (alphas[(index + phase + 3) % alphas.size] shl 24) or ((index * 73471 + phase * 7919 + 0x123456) and 0xFFFFFF)
            },
        )
    }

    /**
     * Test-only synchronous callback used to publish an external revision during paint.
     */
    private class CallbackElement(
        val callback: () -> Unit,
    ) : ModifierElement {
        override val type: ModifierNodeType<*, *> get() = TYPE

        /**
         * Stable callback modifier token.
         */
        companion object {
            val TYPE: ModifierNodeType<CallbackElement, CallbackNode> =
                ModifierNodeType(CallbackElement::class, CallbackNode::class, validateLocal = {}, createNode = { CallbackNode(it.callback) }, updateNode = { _, _, _ -> DirtyMask.None })
        }
    }

    /**
     * Invokes the fixture callback only in the owning paint phase.
     */
    private class CallbackNode(
        val callback: () -> Unit,
    ) : ModifierNode(),
        PaintNode {
        override fun paint(scope: PaintScope) = callback()
    }

    /**
     * Minimal owner transferred to the resource host; no native renderer is required.
     */
    private class ImagePlatform(
        val images: Map<ResourceId, DrawImage>,
    ) : MinecraftUiPlatform {
        var calls = 0
        var closes = 0

        override fun inventorySlot(binding: SlotBinding): MinecraftInventorySlotBinding = error("No inventory slot")

        override fun playerSkin(source: PlayerSkinSource): MinecraftPlayerSkinBinding = error("No skin")

        override fun image(resource: ResourceId): DrawImage {
            calls += 1
            return images.getValue(resource)
        }

        override fun refresh() = Unit

        override fun close() {
            if (closes == 0) closes += 1
        }
    }
}
