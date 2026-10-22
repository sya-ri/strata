package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.loadMinecraftUiImage
import dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.Minecraft
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.inputStream

/**
 * Verifies the advancement-inspired downstream component through Fabric and headless paths.
 *
 * The caller owns the loaded client, selected profile, and output directory.
 */
internal object MinecraftProgressParity {
    /**
     * Requires exact full-frame equality while using assets loaded from Minecraft's active resource manager.
     *
     * @param context loaded loaded-client context.
     * @param profile immutable active-resource UI profile.
     * @param output contained build directory receiving evidence images.
     * @return detached verified headless pixels.
     * @throws AssertionError when Fabric differs from headless output.
     * @throws Throwable when resource, host, rendering, screenshot, or filesystem work fails.
     */
    @OptIn(InternalStrataRuntimeApi::class)
    internal fun run(
        context: MinecraftLoadedTestContext,
        profile: MinecraftUiProfile,
        output: Path,
    ): HeadlessImage {
        val assets = loadAssets(context)
        resizeMinecraftTestWindow(context, IntSize(viewport.width, viewport.height))
        context.runOnClient { minecraft -> minecraft.resizeGui() }
        val headless = renderHeadless(profile, assets)
        val headlessPath = output.resolve("strata-progress-headless.png")
        Files.write(headlessPath, headless.encodePng())
        context.movePointerRaw(0.0, 0.0)
        context.setScreen { createMinecraftScreen(definition(assets), profile, parent = null) }
        context.waitForScreen(FabricMinecraftScreen::class.java)
        context.waitTicks(2)
        NativeImage.read(headlessPath.inputStream()).use { expected ->
            context.assertScreenshotEquals(
                expected,
                "strata-progress-fabric",
                output,
                IntSize(viewport.width, viewport.height),
            )
        }
        closeFabricScreen(context)
        return headless
    }

    private fun loadAssets(context: MinecraftLoadedTestContext): ProgressAssets =
        context.computeOnClient {
            ProgressAssets(
                loadMinecraftUiImage(ResourceId("minecraft", "textures/gui/advancements/window.png")),
                loadMinecraftUiImage(ResourceId("minecraft", "textures/gui/advancements/backgrounds/stone.png")),
                loadMinecraftUiImage(ResourceId("minecraft", "textures/gui/sprites/advancements/task_frame_obtained.png")),
                loadMinecraftUiImage(ResourceId("minecraft", "textures/gui/sprites/advancements/task_frame_unobtained.png")),
            ).also { assets ->
                require(assets.window.size == IntSize(256, 256)) { "The active advancement window has the wrong size." }
                require(assets.background.size == IntSize(16, 16)) { "The active advancement background has the wrong size." }
                require(assets.obtained.size == IntSize(26, 26) && assets.unobtained.size == IntSize(26, 26)) {
                    "The active advancement task frame has the wrong size."
                }
            }
        }

    @OptIn(InternalStrataRuntimeApi::class)
    private fun renderHeadless(
        profile: MinecraftUiProfile,
        assets: ProgressAssets,
    ): HeadlessImage =
        createMinecraftUiHost(definition(assets), profile, fontBackend = LwjglMinecraftFontBackendFactory).use { host ->
            host.attach()
            val frame = host.frame(viewport)
            rasterizeHeadless(frame.drawCommands, viewport)
        }

    private fun definition(assets: ProgressAssets) =
        createProgressScreenDefinition(
            ImageSource.Pixels(assets.window),
            ImageSource.Pixels(assets.background),
            ImageSource.Pixels(assets.obtained),
            ImageSource.Pixels(assets.unobtained),
        )

    private fun closeFabricScreen(context: MinecraftLoadedTestContext) {
        context.runOnClient { minecraft ->
            val current = MinecraftClientScreenAccess.currentScreen(minecraft)
            if (current is FabricMinecraftScreen) current.onClose()
        }
        context.waitFor { minecraft -> (MinecraftClientScreenAccess.currentScreen(minecraft) is FabricMinecraftScreen).not() }
    }

    private data class ProgressAssets(
        val window: DrawImage,
        val background: DrawImage,
        val obtained: DrawImage,
        val unobtained: DrawImage,
    )

    private val viewport = IntSize(320, 180)
}
