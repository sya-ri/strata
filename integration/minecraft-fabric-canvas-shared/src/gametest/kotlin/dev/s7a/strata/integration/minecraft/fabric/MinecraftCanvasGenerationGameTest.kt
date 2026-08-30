package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.Canvas
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Stack
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDevices
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.MinecraftCanvasTextureOrigin
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import javax.imageio.ImageIO

/**
 * Captures changing real native output and the exact same presentation's portable receipt at one actual GUI consumer boundary.
 *
 * Native texel assertions use independently known colors before every complete native/headless comparison.
 * Four successive captures at each of two GUI scales exercise alternating source orientation, transparent target reuse, and odd logical extents.
 * Only this explicit acceptance path requests native readback; ordinary Canvas output remains entirely on the GPU.
 * The runner owns bounded detached receipts and PNGs, while source and renderer cleanup stays on the client thread.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftCanvasGenerationGameTest {
    // The loaded acceptance boundary must preserve arbitrary failures while closing independent native owners.

    /**
     * Verifies native same-generation capture for both an external texture and an independent custom renderer.
     *
     * Every native resource and callback is released after its final GPU use before return.
     * Assertion, native, scheduling, and screenshot failures preserve their primary identity through independent cleanup.
     */
    @Suppress("TooGenericExceptionCaught")
    internal fun run(
        context: MinecraftCanvasTestContext,
        profile: MinecraftUiProfile,
    ) {
        context.waitFor { NativeCanvasDevices.retainedTargetCount() == 0 && context.hasOverlay().not() }
        context.configureViewport(viewport, 1)
        val fixture = context.onClient { MinecraftCanvasGenerationFixture(createMinecraftCanvasTestResources()) }
        var screen: FabricMinecraftScreen? = null
        var failure: Throwable? = null
        try {
            val owned = context.onClient { createMinecraftScreen(definition(fixture), profile, parent = null) }
            screen = owned
            context.onClient { context.setScreen(owned) }
            val first = collect(context, owned, fixture, 1)
            verify(first, fixture.backendDescription)
            context.configureViewport(viewport, 2)
            val second = collect(context, owned, fixture, 2)
            verify(second, fixture.backendDescription)
            context.onClient { context.setScreen(null) }
            awaitRetirement(context, fixture)
            verifyDetached(first + second)
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            runCanvasTestCleanup(
                failure,
                {
                    context.onClient {
                        fixture.afterNativeRender = null
                        MinecraftCanvasCaptureTestHooks.reset()
                    }
                },
                { context.onClient { context.setScreen(null) } },
                { context.onClient { screen?.close() ?: Unit } },
                { awaitRetirement(context, fixture) },
                { context.onClient { fixture.close() } },
            )
        }
    }

    private fun definition(fixture: MinecraftCanvasGenerationFixture): ScreenDefinition =
        ScreenDefinition("Native Canvas changing-generation acceptance") {
            Stack(Modifier.Empty.background(ArgbColor(0xFF000000.toInt()))) {
                Row(spacing = 8) {
                    Canvas(fixture.textureSource, canvasSize)
                    Canvas(fixture.rendererSource, canvasSize)
                }
            }
        }

    private fun collect(
        context: MinecraftCanvasTestContext,
        screen: FabricMinecraftScreen,
        fixture: MinecraftCanvasGenerationFixture,
        scale: Int,
    ): List<Capture> {
        val captures = ArrayList<Capture>()
        val physical = IntSize(canvasSize.width * scale, canvasSize.height * scale)
        context.onClient {
            fixture.afterNativeRender = {
                if (fixture.physicalSize == physical && captures.size < 4) {
                    MinecraftCanvasCaptureTestHooks.arm {
                        val index = captures.size
                        val commands = screen.captureCanvasFrame()
                        val generation =
                            Generation(
                                fixture.textureGeneration,
                                fixture.rendererGeneration,
                                fixture.textureOrigin,
                                fixture.expectedTextureArgb,
                                fixture.expectedRendererArgb,
                            )
                        val path = context.outputDirectory.resolve("strata-canvas-native-generation-scale-$scale-$index.png")
                        captures += Capture(generation, scale, commands, captureMinecraftCanvasNativeFrame(path))
                        if (captures.size == 4) fixture.afterNativeRender = null
                    }
                }
            }
        }
        context.waitFor { captures.size == 4 && captures.all { it.native.isDone } }
        return context.onClient { captures.toList() }
    }

    private fun verify(
        captures: List<Capture>,
        backend: String,
    ) {
        check(captures.map { it.generation.textureColor }.toSet() == setOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt())) {
            "Explicit same-generation capture must observe both changing native texture orientations."
        }
        check(captures.map { it.generation.rendererColor }.toSet() == setOf(0xFF008000.toInt(), 0xFF000000.toInt())) {
            "Explicit same-generation capture must observe both native drawing and transparent target reuse."
        }
        captures.forEach { capture ->
            val path = capture.native.join()
            val native = checkNotNull(ImageIO.read(path.toFile())) { "A native consumer capture must produce a readable PNG." }
            check(native.width == viewport.width && native.height == viewport.height)
            check(native.getRGB(capture.scale, capture.scale) == capture.generation.textureColor) {
                "The captured native texture does not match its independent generation-specific color."
            }
            check(native.getRGB(40 * capture.scale, capture.scale) == capture.generation.rendererColor) {
                "The captured native renderer does not match its independent generation-specific color."
            }
            verifyTextureBoundary(native, capture)
            val headless = rasterizeHeadless(capture.commands, logicalViewport(capture.scale), scale = capture.scale)
            for (y in 0 until viewport.height) {
                for (x in 0 until viewport.width) {
                    check(native.getRGB(x, y) == headless.argbAt(x, y)) {
                        "The same-consumer native generation differs from its immutable snapshot at physical texel ($x, $y), scale ${capture.scale}."
                    }
                }
            }
            val stem = path.fileName.toString().removeSuffix(".png")
            Files.write(path.resolveSibling("$stem-headless.png"), headless.encodePng())
            Files.writeString(
                path.resolveSibling("$stem.txt"),
                "case=$stem\nphysical=640x480\nguiScale=${capture.scale}\ntextureGeneration=${capture.generation.texture}\n" +
                    "rendererGeneration=${capture.generation.renderer}\ncaptureBoundary=actual-gui-consumer\ncheckedTexels=307200\nbackend=$backend\n",
            )
        }
    }

    private fun verifyTextureBoundary(
        native: BufferedImage,
        capture: Capture,
    ) {
        val left = canvasSize.width * capture.scale / 2 - 1
        val top = canvasSize.height * capture.scale / 2 - 1
        val colors =
            when (capture.generation.origin) {
                MinecraftCanvasTextureOrigin.TopLeft -> intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFF00.toInt())
                MinecraftCanvasTextureOrigin.BottomLeft -> intArrayOf(0xFF0000FF.toInt(), 0xFFFFFF00.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt())
            }
        for (y in 0..1) {
            for (x in 0..1) {
                check(native.getRGB(left + x, top + y) == colors[y * 2 + x]) {
                    "Native nearest sampling chose the wrong known texel at the center boundary (${left + x}, ${top + y}), " +
                        "scale ${capture.scale}, origin ${capture.generation.origin}."
                }
            }
        }
    }

    private fun verifyDetached(captures: List<Capture>) {
        captures.forEach { capture ->
            val path = capture.native.join()
            val stem = path.fileName.toString().removeSuffix(".png")
            val before = Files.readAllBytes(path.resolveSibling("$stem-headless.png"))
            val after = rasterizeHeadless(capture.commands, logicalViewport(capture.scale), scale = capture.scale).encodePng()
            check(before.contentEquals(after)) { "A captured native generation changed after attachment and GPU storage retirement." }
        }
    }

    private fun awaitRetirement(
        context: MinecraftCanvasTestContext,
        fixture: MinecraftCanvasGenerationFixture,
    ) {
        context.waitFor {
            fixture.textureGeneration == fixture.releasedLeases && fixture.openedRenderers == fixture.closedRenderers &&
                NativeCanvasDevices.retainedTargetCount() == 0
        }
    }

    private fun logicalViewport(scale: Int): IntSize = IntSize(viewport.width / scale, viewport.height / scale)

    private val viewport = IntSize(640, 480)
    private val canvasSize = IntSize(31, 29)

    private data class Generation(
        val texture: Int,
        val renderer: Int,
        val origin: MinecraftCanvasTextureOrigin,
        val textureColor: Int,
        val rendererColor: Int,
    )

    private data class Capture(
        val generation: Generation,
        val scale: Int,
        val commands: List<DrawCommand>,
        val native: CompletableFuture<Path>,
    )
}
