package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.ImageScale
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.ScrollArea
import dev.s7a.strata.component.ScrollState
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.fillMaxSize
import dev.s7a.strata.modifier.imageBackground
import dev.s7a.strata.modifier.scaleToFit
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDevices
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.ui.UiDefinition
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * Checks real loaded presentation of all singleton source axes and unchanged controls in one bounded mosaic.
 * Each 80 by 40 panel has independent original scalar/nearest/ARGB pixels; no candidate rasterizer supplies the oracle.
 * Source replacement, fractional fit/scroll, cropped edges and complete GUI densities one through four remain visible.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftSingleAxisImageBackgroundGameTest {
    private val physical = IntSize(1920, 1080)
    private val panel = IntSize(80, 40)
    private val background = ArgbColor(0xFF1A2B3C.toInt())

    /**
     * Recreates full screenshots at every scale and awaits actual terminal native release after each mosaic.
     * The surrounding runner restores the original viewport; all scene state is created on its client owner.
     */
    internal fun run(
        context: MinecraftCanvasTestContext,
        profile: MinecraftUiProfile,
    ) {
        for (scale in 1..4) verify(context, profile, scale)
    }

    @Suppress("TooGenericExceptionCaught") // The complete scene preserves primary assertion failures through independent native cleanup.
    private fun verify(context: MinecraftCanvasTestContext, profile: MinecraftUiProfile, scale: Int) {
        context.waitFor { released() }
        context.configureViewport(physical, scale)
        val logical = IntSize(physical.width / scale, physical.height / scale)
        val scene = context.onClient { Scene() }
        val screen = context.onClient { createMinecraftScreen(scene.definition(), profile, parent = null) }
        var failure: Throwable? = null
        try {
            context.onClient { context.setScreen(screen) }
            MinecraftCanvasFrameFence.awaitCompletedFrame(context, screen, MinecraftCanvasFrameFence.hostFrameCount(context, screen))
            var completed = MinecraftCanvasFrameFence.hostFrameCount(context, screen)
            context.onClient { scene.scroll.scrollTo(10.25) }
            MinecraftCanvasFrameFence.awaitCompletedFrame(context, screen, completed)
            MinecraftCanvasFrameFence.awaitCompletedFrame(context, screen, MinecraftCanvasFrameFence.hostFrameCount(context, screen))
            val old = context.onClient { screen.captureCanvasFrame() }
            val oldPixels = context.onClient { rasterizeHeadless(old, logical, scale).copyArgb() }
            repeat(2) { generation ->
                if (generation != 0) {
                    completed = MinecraftCanvasFrameFence.hostFrameCount(context, screen)
                    context.onClient { scene.revision.value = 1 }
                    MinecraftCanvasFrameFence.awaitCompletedFrame(context, screen, completed)
                    MinecraftCanvasFrameFence.awaitCompletedFrame(context, screen, MinecraftCanvasFrameFence.hostFrameCount(context, screen))
                }
                val expected = context.onClient { MinecraftTileBackgroundReference.pixels(scene.original(logical), logical, scale) }
                val screenshot = context.takeScreenshot("single-axis-background-$scale-$generation", physical)
                val actual = checkNotNull(ImageIO.read(screenshot.toFile()))
                check(actual.width == physical.width && actual.height == physical.height)
                for (y in 0 until physical.height) {
                    for (x in 0 until physical.width) {
                        check(actual.getRGB(x, y) == expected[y * physical.width + x]) {
                            "Original scalar background pixels differ at ($x, $y): $screenshot"
                        }
                    }
                }
                check(context.onClient { rasterizeHeadless(old, logical, scale).copyArgb().contentEquals(oldPixels) })
            }
            Files.writeString(
                context.outputDirectory.resolve("single-axis-background-$scale.properties"),
                "cases=16\npanelWidth=80\npanelHeight=40\nguiScale=$scale\nsourceRevisions=2\noriginalScalarPixelOracle=true\nfractionalFitAndScroll=true\ntolerance=0\nresult=passed\n",
            )
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            runCanvasTestCleanup(
                failure,
                { context.onClient { context.setScreen(null) } },
                { context.onClient { screen.close() } },
                { context.waitFor { released() } },
            )
        }
    }

    private fun released(): Boolean = NativeCanvasDevices.retainedTargetCount() == 0 && NativeCanvasDevices.retainedGuiResourceSetCount() == 0 && NativeCanvasDevices.retainedManagedGuiResourceCount() == 0 && NativeCanvasDevices.retainedManagedGuiResourceBytes() == 0L

    /**
     * Pure client-owned immutable image pairs and one current revision; no native texture or frame history is retained.
     */
    private class Scene {
        val revision = mutableStateOf(0)
        val scroll = ScrollState()
        private val images = Case.entries.associateWith { case -> List(2) { phase -> image(case, phase) } }

        /**
         * Creates the real bounded component mosaic from this current immutable image revision.
         */
        fun definition(): UiDefinition =
            UiDefinition("Singleton-axis background native acceptance") {
                val phase = revision.value
                Stack(Modifier.Empty.background(background)) {
                    Column {
                        for (row in 0..3) {
                            Row {
                                for (column in 0..3) {
                                    val case = Case.entries[row * 4 + column]
                                    val image = images.getValue(case)[phase]
                                    Stack(Modifier.Empty.size(panel.width, panel.height)) {
                                        when (case.mapping) {
                                            Mapping.Fractional -> {
                                                Stack(Modifier.Empty.fillMaxSize().scaleToFit(IntSize(63, 31), allowUpscaling = true)) {
                                                    Stack(Modifier.Empty.fillMaxSize().imageBackground(ImageSource.Pixels(image), ImageScale.Tile)) {}
                                                }
                                            }

                                            Mapping.Scroll -> {
                                                ScrollArea(scroll, Modifier.Empty.size(panel.width, panel.height)) {
                                                    Stack(Modifier.Empty.size(panel.width, 100).imageBackground(ImageSource.Pixels(image), ImageScale.Tile)) {}
                                                }
                                            }

                                            else -> {
                                                val base = Modifier.Empty.size(case.extent.width, case.extent.height)
                                                val tiled =
                                                    if (case.mapping == Mapping.NineSlice) base.imageBackground(ImageSource.Pixels(image), Insets.all(1)) else base.imageBackground(ImageSource.Pixels(image), if (case.mapping == Mapping.Stretch) ImageScale.Stretch else ImageScale.Tile)
                                                Stack(tiled) {
                                                    if (case == Case.MixedContent) Spacer(Modifier.Empty.size(7, 5).background(ArgbColor(0x80442288.toInt())))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

        /**
         * Reconstructs the frozen original cells and global fit/scroll coordinates for independent pixel verification.
         */
        fun original(viewport: IntSize): List<DrawCommand> =
            buildList {
                add(DrawCommand.FillRectangle(IntRect(0, 0, viewport.width, viewport.height), background))
                Case.entries.forEachIndexed { index, case ->
                    val offset = IntOffset(index % 4 * panel.width, index / 4 * panel.height)
                    val image = images.getValue(case)[revision.value]
                    add(DrawCommand.PushClip(IntRect(offset.x, offset.y, offset.x + panel.width, offset.y + panel.height)))
                    val commands =
                        when (case.mapping) {
                            Mapping.Fractional -> {
                                MinecraftTileBackgroundReference.fitted(image, IntSize(63, 31), panel, offset)
                            }

                            Mapping.Scroll -> {
                                MinecraftTileBackgroundReference.scalar(image, IntSize(panel.width, 100)).map { blit ->
                                    val source = blit.source
                                    val destination = blit.destination
                                    DrawCommand.SampledImage(image, FloatRect(source.left.toFloat(), source.top.toFloat(), source.right.toFloat(), source.bottom.toFloat()), FloatRect((offset.x + destination.left).toFloat(), (offset.y + destination.top - 10.25).toFloat(), (offset.x + destination.right).toFloat(), (offset.y + destination.bottom - 10.25).toFloat()), ArgbColor(-1), 0f)
                                }
                            }

                            Mapping.Stretch -> {
                                listOf(DrawCommand.BlitImage(image, IntRect(0, 0, image.size.width, image.size.height), IntRect(0, 0, case.extent.width, case.extent.height)))
                            }

                            Mapping.NineSlice -> {
                                nineSlice(image)
                            }

                            Mapping.Tile -> {
                                MinecraftTileBackgroundReference.scalar(image, case.extent)
                            }
                        }
                    commands.forEach { command ->
                        add(
                            if (command is DrawCommand.BlitImage) {
                                val bounds = command.destination
                                command.copy(destination = IntRect(offset.x + bounds.left, offset.y + bounds.top, offset.x + bounds.right, offset.y + bounds.bottom))
                            } else {
                                command
                            },
                        )
                    }
                    if (case == Case.MixedContent) add(DrawCommand.FillRectangle(IntRect(offset.x, offset.y, offset.x + 7, offset.y + 5), ArgbColor(0x80442288.toInt())))
                    add(DrawCommand.PopClip)
                }
            }

        private fun nineSlice(image: DrawImage): List<DrawCommand> {
            val xs = listOf(0, 1, panel.width - 1, panel.width)
            val ys = listOf(0, 1, panel.height - 1, panel.height)
            return buildList {
                for (row in 0..2) {
                    for (column in 0..2) add(DrawCommand.BlitImage(image, IntRect(column, row, column + 1, row + 1), IntRect(xs[column], ys[row], xs[column + 1], ys[row + 1])))
                }
            }
        }

        private fun image(
            case: Case,
            phase: Int,
        ): DrawImage {
            val alphas = intArrayOf(0, 1, 64, 128, 254, 255)
            return createDrawImage(
                case.source,
                IntArray(case.source.width * case.source.height) { index ->
                    if (case == Case.EqualMulti) 0x80112233.toInt() xor (phase * 0x00070707) else (alphas[(index + phase + 3) % alphas.size] shl 24) or ((index * 73471 + phase * 7919 + 0x123456) and 0xFFFFFF)
                },
            )
        }
    }

    private enum class Mapping { Tile, Stretch, NineSlice, Fractional, Scroll }

    private enum class Case(
        val source: IntSize,
        val mapping: Mapping = Mapping.Tile,
        val extent: IntSize = panel,
    ) {
        SinglePixel(IntSize(1, 1)),
        WidthOne(IntSize(1, 8)),
        HeightOne(IntSize(8, 1)),
        WidthOneLong(IntSize(1, 257)),
        HeightOneLong(IntSize(257, 1)),
        SinglePixelSmall(IntSize(1, 1), extent = IntSize(7, 5)),
        WidthOneSmall(IntSize(1, 8), extent = IntSize(7, 5)),
        HeightOneSmall(IntSize(8, 1), extent = IntSize(7, 5)),
        ZeroArea(IntSize(1, 1), extent = IntSize.Zero),
        MultiPattern(IntSize(2, 3)),
        EqualMulti(IntSize(2, 2)),
        Stretch(IntSize(2, 3), Mapping.Stretch),
        NineSlice(IntSize(3, 3), Mapping.NineSlice),
        FractionalOriginal(IntSize(1, 8), Mapping.Fractional),
        MixedContent(IntSize(1, 8)),
        ScrollPartial(IntSize(1, 8), Mapping.Scroll),
    }
}
