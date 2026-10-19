@file:Suppress("DEPRECATION") // Exercise the compatibility entry point used by existing consumers.

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.Image
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.ScrollArea
import dev.s7a.strata.component.ScrollState
import dev.s7a.strata.component.Stack
import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.imageBackground
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.scaleToFit
import dev.s7a.strata.modifier.semantics
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * Compares tiled backgrounds to an independent modulo-based pixel oracle, including fractional presentation.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftSingleTexelNineSliceTest {
    @Test
    fun observedImageReplacementInvalidatesPixelsWhileRetainingInputSemanticsAndScroll() {
        val source = ReactiveTestSource(createDrawImage(IntSize(4, 4), IntArray(16) { 0x80335577.toInt() }))
        val scroll = ScrollState()
        var presses = 0
        val definition =
            ScreenDefinition("Observed tiled background") {
                ScrollArea(scroll, Modifier.size(60, 40)) {
                    Observe(source) { image ->
                        Stack(
                            Modifier
                                .size(60, 100)
                                .imageBackground(ImageSource.Pixels(image), Insets.all(1))
                                .semantics(Semantics(label = UiText.Literal("Retained action")))
                                .onPress { presses += 1 },
                        ) {}
                    }
                }
            }
        createMinecraftUiHost(definition, MinecraftProfileFixture.create()).use { host ->
            host.attach()
            val size = IntSize(60, 40)
            host.frame(size)
            scroll.scrollTo(10.0)
            val before = host.frame(size)
            assertEquals(InputResult.Consumed, host.dispatchPointer(PointerEvent.Press(IntOffset(5, 5), PointerButton.Primary)))
            assertEquals(1, presses)
            source.publish(createDrawImage(IntSize(4, 4), IntArray(16) { 0x80442211.toInt() }))
            val after = host.frame(size)
            assertNotSame(before, after)
            assertFalse(rasterizeHeadless(before.drawCommands, size).copyArgb().contentEquals(rasterizeHeadless(after.drawCommands, size).copyArgb()))
            assertEquals(before.semantics.map { it.semantics }, after.semantics.map { it.semantics })
            assertEquals(10.0, scroll.metrics.offset)
            assertSame(after, host.frame(size))
            assertEquals(InputResult.Consumed, host.dispatchPointer(PointerEvent.Press(IntOffset(5, 5), PointerButton.Primary)))
            assertEquals(2, presses)
        }
    }

    @Test
    fun singleTexelCenterHasConstantCommandCountAndReusesCleanFrame() {
        val source = createDrawImage(IntSize(9, 9), IntArray(81) { 0x80335577.toInt() })
        host(source, IntSize(460, 320), false).use { host ->
            host.attach()
            val frame = host.frame(IntSize(460, 320))
            assertFalse(frame.drawCommands.isEmpty())
            assertFalse(9 < frame.drawCommands.size)
            assertSame(frame, host.frame(IntSize(460, 320)))
        }
    }

    @Test
    fun multiTexelPatternsUseBoundedTemplatesAndKeepExactPixels() {
        for (extent in listOf(4, 6, 10)) {
            val source = createDrawImage(IntSize(extent, extent), IntArray(extent * extent) { 0x80335500.toInt() or it })
            val design = IntSize(460, 320)
            host(source, design, false).use { actual ->
                actual.attach()
                val frame = actual.frame(design)
                assertFalse(128 < frame.drawCommands.size, "source=$extent commands=${frame.drawCommands.size}")
                assertSame(frame, actual.frame(design))
                assertArrayEquals(
                    rasterizeHeadless(listOf(DrawCommand.BlitImage(tiledPixels(source, design), IntRect(0, 0, design.width, design.height), IntRect(0, 0, design.width, design.height))), design).copyArgb(),
                    rasterizeHeadless(frame.drawCommands, design).copyArgb(),
                )
            }
        }
    }

    @Test
    fun oneAndTwoTexelAxesPreserveTiledPixelsAtFractionalViewports() {
        verifyPatternPixels(
            listOf(IntSize(3, 3), IntSize(3, 4), IntSize(4, 3), IntSize(4, 4)),
            listOf(IntSize(19, 13), IntSize(13, 9), IntSize(8, 5), IntSize(38, 26)),
        )
    }

    @Test
    fun arbitraryPatternSizesPreserveTiledPixelsAtIntegerPresentationScales() {
        verifyPatternPixels(
            listOf(IntSize(3, 3), IntSize(3, 4), IntSize(4, 3), IntSize(4, 4), IntSize(3, 6), IntSize(6, 3), IntSize(4, 6), IntSize(6, 6), IntSize(5, 7), IntSize(7, 5)),
            listOf(IntSize(19, 13), IntSize(38, 26)),
        )
    }

    @Test
    fun fractionalTilingPreservesPerSliceSamplingAtNearIntegerSourceBoundaries() {
        val source = createDrawImage(IntSize(6, 3), IntArray(18) { 0xFF000000.toInt() or it })
        val design = IntSize(19, 13)
        val viewport = IntSize(8, 5)
        host(source, design, false).use { actualHost ->
            host(tiledPixels(source, design), design, true).use { bakedHost ->
                actualHost.attach()
                bakedHost.attach()
                val actual = rasterizeHeadless(actualHost.frame(viewport).drawCommands, viewport)
                val baked = rasterizeHeadless(bakedHost.frame(viewport).drawCommands, viewport)
                // Fractional destinations are independently rounded to Float, then sampled per command.
                // The tiled command maps just below source x=3; a merged logical image selects x=3.
                assertEquals(source.argbAt(2, 1), actual.argbAt(1, 0))
                assertEquals(source.argbAt(3, 1), baked.argbAt(1, 0))
            }
        }
    }

    @Suppress("NestedBlockDepth") // The finite source/viewport/density matrix keeps both owned hosts scoped to each comparison.
    private fun verifyPatternPixels(sourceSizes: List<IntSize>, viewports: List<IntSize>) {
        for (sourceSize in sourceSizes) {
            val source =
                createDrawImage(
                    sourceSize,
                    IntArray(sourceSize.width * sourceSize.height) { index ->
                        val alpha = listOf(0, 96, 192, 255)[index % 4]
                        (alpha shl 24) or (0x112233 + index * 0x030507)
                    },
                )
            val design = IntSize(19, 13)
            val expected = tiledPixels(source, design)
            for (viewport in viewports) {
                host(source, design, false).use { actualHost ->
                    host(expected, design, true).use { oracleHost ->
                        actualHost.attach()
                        oracleHost.attach()
                        val actual = actualHost.frame(viewport)
                        val oracle = oracleHost.frame(viewport)
                        for (density in 1..4) {
                            assertArrayEquals(
                                rasterizeHeadless(oracle.drawCommands, viewport, density).copyArgb(),
                                rasterizeHeadless(actual.drawCommands, viewport, density).copyArgb(),
                                "source=$sourceSize viewport=$viewport density=$density",
                            )
                            val clip = DrawCommand.PushFractionalClip(FloatRect(1.25f, 0.75f, viewport.width - 1.5f, viewport.height - 0.5f))
                            val inner = DrawCommand.PushClip(IntRect(2, 1, viewport.width - 1, viewport.height - 1))
                            assertArrayEquals(
                                rasterizeHeadless(listOf(clip, inner) + oracle.drawCommands + listOf(DrawCommand.PopClip, DrawCommand.PopClip), viewport, density).copyArgb(),
                                rasterizeHeadless(listOf(clip, inner) + actual.drawCommands + listOf(DrawCommand.PopClip, DrawCommand.PopClip), viewport, density).copyArgb(),
                                "nested clips: source=$sourceSize viewport=$viewport density=$density",
                            )
                        }
                    }
                }
            }
        }
    }

    private fun host(
        source: DrawImage,
        design: IntSize,
        oracle: Boolean,
    ): MinecraftUiHost =
        createMinecraftUiHost(
            ScreenDefinition("Tiled pixels") {
                Stack(Modifier.scaleToFit(design, allowUpscaling = true)) {
                    if (oracle) {
                        Image(ImageSource.Pixels(source), size = design)
                    } else {
                        val border = if (source.size.width == 9) Insets.all(4) else Insets.all(1)
                        Stack(Modifier.size(design.width, design.height).imageBackground(ImageSource.Pixels(source), border)) {}
                    }
                }
            },
            MinecraftProfileFixture.create(),
        )

    private fun tiledPixels(
        source: DrawImage,
        size: IntSize,
    ): DrawImage =
        createDrawImage(
            size,
            IntArray(size.width * size.height) { index ->
                val x = sourceCoordinate(index % size.width, size.width, source.size.width)
                val y = sourceCoordinate(index / size.width, size.height, source.size.height)
                source.argbAt(x, y)
            },
        )

    private fun sourceCoordinate(
        position: Int,
        destination: Int,
        source: Int,
    ): Int =
        when (position) {
            0 -> 0
            destination - 1 -> source - 1
            else -> 1 + (position - 1) % (source - 2)
        }
}
