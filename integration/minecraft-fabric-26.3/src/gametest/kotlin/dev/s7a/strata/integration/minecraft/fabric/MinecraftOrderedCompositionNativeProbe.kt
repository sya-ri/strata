@file:Suppress("DEPRECATION") // Acceptance uses the same stable compatibility factory as the existing loaded suite.

package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.buffers.GpuBuffer
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.AbstractTexture
import org.apache.commons.lang3.function.FailableConsumer
import org.apache.commons.lang3.function.FailableFunction
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies actual transparent RGBA8 tile outputs against the independent CPU rasterizer after ordinary GUI consumption.
 * The shared whole-frame suite separately compares GPU and CPU GUI presentation of these same ordered image/glyph scenes.
 * Diagnostic readbacks submit their own bounded fences; they never close or replace production native storage.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftOrderedCompositionNativeProbe {
    private val viewport = IntSize(320, 240)

    /**
     * Checks three GUI densities and two independent source revisions, including images overlapping portable glyphs.
     * The Fabric test thread owns sequencing; every native read and preparation replacement runs on the render thread.
     */
    @Suppress("TooGenericExceptionCaught") // Native assertions remain primary while the original viewport and GUI scale are restored.
    internal fun run(context: ClientGameTestContext, profile: MinecraftUiProfile, output: Path) {
        val starting =
            context.computeOnClient(
                FailableFunction<Minecraft, Triple<IntSize, IntSize, Int>, RuntimeException> { minecraft ->
                    val window = minecraft.window
                    Triple(IntSize(window.screenWidth, window.screenHeight), IntSize(window.width, window.height), minecraft.options.guiScale().get())
                },
            )
        var failure: Throwable? = null
        try {
            Files.createDirectories(output)
            for (scale in 1..3) {
                resizeMinecraftTestWindow(context, IntSize(viewport.width * scale, viewport.height * scale))
                context.runOnClient(
                    FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                        minecraft.options.guiScale().set(scale)
                        minecraft.resizeGui()
                    },
                )
                for (revision in listOf(0, 137)) verify(context, profile, output, scale, revision)
            }
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            runCanvasTestCleanup(
                failure,
                { resizeMinecraftTestWindow(context, starting.first, starting.second) },
                {
                    context.runOnClient(
                        FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                            minecraft.options.guiScale().set(starting.third)
                            minecraft.resizeGui()
                        },
                    )
                },
            )
        }
    }

    @Suppress("TooGenericExceptionCaught") // All production owners remain fenced when a diagnostic comparison fails.
    private fun verify(context: ClientGameTestContext, profile: MinecraftUiProfile, output: Path, scale: Int, revision: Int) {
        val screen = context.computeOnClient(FailableFunction<Minecraft, FabricMinecraftScreen, RuntimeException> { createMinecraftScreen(createMinecraftCompositionParityScene(viewport, revision), profile, parent = null) })
        var failure: Throwable? = null
        try {
            context.setScreen { screen }
            context.waitForScreen(FabricMinecraftScreen::class.java)
            context.waitTicks(8)
            val tiles = context.computeOnClient(FailableFunction<Minecraft, Int, RuntimeException> { verifyTiles(screen, scale) })
            Files.writeString(output.resolve("tile-parity-$scale-$revision.properties"), "gpu_tiles=$tiles\ntolerance=0\nresult=passed\n")
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            runCanvasTestCleanup(
                failure,
                { context.runOnClient(FailableConsumer<Minecraft, RuntimeException> { minecraft -> MinecraftClientScreenAccess.setScreen(minecraft, null) }) },
                { context.runOnClient(FailableConsumer<Minecraft, RuntimeException> { screen.close() }) },
            )
        }
    }

    private fun verifyTiles(
        screen: FabricMinecraftScreen,
        scale: Int,
    ): Int {
        RenderSystem.assertOnRenderThread()
        val tiles = MinecraftCompositionParityInputs.portable(screen).filter { MinecraftCompositionParityInputs.composed(it.first) }
        check(tiles.isNotEmpty()) { "Transparent image and glyph scene did not exercise ordered GPU composition." }
        for ((description, owner) in tiles) {
            check(MinecraftCompositionParityInputs.member(description, "scale") == scale) { "Ordered tile must use the requested GUI density." }
            val rasterize = description.javaClass.declaredMethods.single { it.name.startsWith("rasterize") && it.parameterCount == 0 }
            check(rasterize.trySetAccessible())
            val expected = rasterize.invoke(description) as? HeadlessImage ?: error("Independent CPU rasterization returned no image.")
            val view = MinecraftCompositionParityInputs.member(owner, "borrowed") as? AbstractTexture ?: error("Prepared output has no borrowed native texture.")
            val texture = view.getTexture()
            val device = RenderSystem.getDevice()
            device.createBuffer({ "Strata exact ordered tile readback" }, GpuBuffer.USAGE_COPY_DST or GpuBuffer.USAGE_MAP_READ, expected.size.width.toLong() * expected.size.height * 4).use { buffer ->
                val encoder = device.createCommandEncoder()
                encoder.copyTextureToBuffer(texture, buffer, 0L, {}, 0)
                encoder.createFence().use { fence ->
                    encoder.submit()
                    check(fence.awaitCompletion(5_000_000_000L)) { "Ordered tile readback exceeded its bounded native timeout." }
                }
                buffer.map(true, false).use { mapped -> compareBytes(expected, mapped.data()) }
            }
        }
        return tiles.size
    }

    private fun compareBytes(
        expected: HeadlessImage,
        bytes: ByteBuffer,
    ) {
        val pixels = expected.copyArgb()
        pixels.forEachIndexed { index, argb ->
            val offset = index * 4
            val actual = ((bytes.get(offset + 3).toInt() and 255) shl 24) or ((bytes.get(offset).toInt() and 255) shl 16) or ((bytes.get(offset + 1).toInt() and 255) shl 8) or (bytes.get(offset + 2).toInt() and 255)
            check(actual == argb) { "Ordered RGBA8 tile differs at ($index): expected=${argb.toUInt().toString(16)}, actual=${actual.toUInt().toString(16)}" }
        }
    }
}
