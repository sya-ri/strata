package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import dev.s7a.strata.component.PlayerSkinSource
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.integration.minecraft.fabric.MinecraftParityPresentation.closeFabricScreen
import dev.s7a.strata.integration.minecraft.fabric.MinecraftParityRendering.renderHeadless
import dev.s7a.strata.integration.minecraft.fabric.MinecraftParityRendering.requireExactPixels
import dev.s7a.strata.integration.minecraft.fabric.MinecraftParityRendering.requireImageSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.loadCurrentMinecraftPlayerSkin
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.PlayerFaceExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.PlayerSkin
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.inputStream

/**
 * Compares native player-face rendering with the ordinary and filtered headless/Fabric presentations.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftPlayerHeadParity {
    /**
     * Returns the verified ordinary reference and releases the active Fabric screen on every exit.
     */
    internal fun run(
        context: MinecraftLoadedTestContext,
        profile: MinecraftUiProfile,
        output: Path,
    ): HeadlessImage {
        val result = runCatching { capture(context, profile, output) }
        runCanvasTestCleanup(result.exceptionOrNull(), { closeFabricScreen(context) })
        return result.getOrThrow()
    }

    private fun capture(
        context: MinecraftLoadedTestContext,
        profile: MinecraftUiProfile,
        output: Path,
    ): HeadlessImage {
        val skin =
            context.computeOnClient { loadCurrentMinecraftPlayerSkin() }
        resizeMinecraftTestWindow(context, IntSize(playerHeadViewport.width, playerHeadViewport.height))
        context.runOnClient { minecraft -> minecraft.resizeGui() }
        context.movePointerRaw(0.0, 0.0)
        context.setScreen { DeterministicPlayerHeadScreen(checkNotNull(Minecraft.getInstance().player).skin) }
        context.waitForScreen(DeterministicPlayerHeadScreen::class.java)
        context.waitTicks(2)
        val nativePath =
            context.takeScreenshot(
                "strata-player-head-native",
                output,
                IntSize(playerHeadViewport.width, playerHeadViewport.height),
            )
        val headless =
            NativeImage.read(nativePath.inputStream()).use { native ->
                requireImageSize(native, playerHeadViewport)
                val rendered =
                    context.computeOnClient {
                        renderHeadless(
                            profile,
                            createPlayerHeadShowcaseScreenDefinition(PlayerSkinSource.Pixels(skin)),
                            playerHeadViewport,
                        )
                    }
                Files.write(output.resolve("strata-player-head-headless.png"), rendered.encodePng())
                requireExactPixels(native, rendered)

                context.setScreen {
                    createMinecraftScreen(
                        createPlayerHeadShowcaseScreenDefinition(PlayerSkinSource.Pixels(skin)),
                        profile,
                        parent = null,
                    )
                }
                context.waitForScreen(FabricMinecraftScreen::class.java)
                context.waitTicks(2)
                context.assertScreenshotEquals(
                    native,
                    "strata-player-head-fabric",
                    output,
                    IntSize(playerHeadViewport.width, playerHeadViewport.height),
                )
                closeFabricScreen(context)

                rendered
            }
        verifyFiltered(context, profile, output, skin)
        return headless
    }

    private fun verifyFiltered(
        context: MinecraftLoadedTestContext,
        profile: MinecraftUiProfile,
        output: Path,
        skin: DrawImage,
    ) {
        val filteredDefinition = createFilteredPlayerHeadScreenDefinition(PlayerSkinSource.Pixels(skin))
        val filtered =
            context.computeOnClient {
                renderHeadless(profile, filteredDefinition, playerHeadViewport)
            }
        Files.write(output.resolve("strata-player-head-filtered-headless.png"), filtered.encodePng())
        context.setScreen {
            createMinecraftScreen(
                createFilteredPlayerHeadScreenDefinition(PlayerSkinSource.Pixels(skin)),
                profile,
                parent = null,
            )
        }
        context.waitForScreen(FabricMinecraftScreen::class.java)
        context.waitTicks(2)
        NativeImage.read(output.resolve("strata-player-head-filtered-headless.png").inputStream()).use { expected ->
            context.assertScreenshotEquals(
                expected,
                "strata-player-head-filtered-fabric",
                output,
                IntSize(playerHeadViewport.width, playerHeadViewport.height),
            )
        }
    }

    private class DeterministicPlayerHeadScreen(
        private val skin: PlayerSkin,
    ) : Screen(Component.literal("Player head parity")) {
        override fun extractBackground(
            graphics: GuiGraphicsExtractor,
            mouseX: Int,
            mouseY: Int,
            partialTick: Float,
        ) {
            graphics.fill(0, 0, width, height, opaqueBlack)
        }

        override fun extractRenderState(
            graphics: GuiGraphicsExtractor,
            mouseX: Int,
            mouseY: Int,
            partialTick: Float,
        ) {
            PlayerFaceExtractor.extractRenderState(graphics, skin, 20, 20, 24)
        }
    }

    private val playerHeadViewport = IntSize(64, 64)
    private val opaqueBlack = 0xFF000000.toInt()
}
