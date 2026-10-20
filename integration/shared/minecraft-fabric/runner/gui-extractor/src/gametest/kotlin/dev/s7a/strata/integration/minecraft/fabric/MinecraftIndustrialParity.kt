package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.integration.minecraft.fabric.MinecraftParityPresentation.closeFabricScreen
import dev.s7a.strata.integration.minecraft.fabric.MinecraftParityRendering.renderHeadless
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.loadMinecraftUiImage
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotComparisonAlgorithm
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotComparisonOptions
import net.minecraft.client.Minecraft
import org.apache.commons.lang3.function.FailableConsumer
import org.apache.commons.lang3.function.FailableFunction
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.inputStream

/**
 * Compares the loaded industrial asset's Fabric presentation with the headless reference.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftIndustrialParity {
    /**
     * Returns the verified reference image, releasing the active Fabric screen even when capture or comparison fails.
     */
    internal fun run(
        context: ClientGameTestContext,
        profile: MinecraftUiProfile,
        output: Path,
    ): HeadlessImage {
        val result = runCatching { capture(context, profile, output) }
        runCanvasTestCleanup(result.exceptionOrNull(), { closeFabricScreen(context) })
        return result.getOrThrow()
    }

    private fun capture(
        context: ClientGameTestContext,
        profile: MinecraftUiProfile,
        output: Path,
    ): HeadlessImage {
        val panel = loadPanel(context)
        require(panel.size == industrialAssetSize) { "The test Mod industrial resource has an unexpected size." }
        resizeMinecraftTestWindow(context, IntSize(industrialViewport.width, industrialViewport.height))
        context.runOnClient(
            FailableConsumer<Minecraft, RuntimeException> { minecraft -> minecraft.resizeGui() },
        )
        val headless =
            context.computeOnClient(
                FailableFunction<Minecraft, HeadlessImage, RuntimeException> {
                    renderHeadless(
                        profile,
                        createIndustrialScreenDefinition(
                            panel = ImageSource.Pixels(panel),
                            fuelBinding = null,
                            chargeBinding = null,
                            playerInventory = { null },
                        ),
                        industrialViewport,
                        industrialPointer,
                    )
                },
            )
        val headlessPath = output.resolve("strata-industrial-headless.png")
        Files.write(headlessPath, headless.encodePng())
        context.input.setCursorPos(industrialPointer.x.toDouble(), industrialPointer.y.toDouble())
        context.setScreen {
            createMinecraftScreen(
                createIndustrialScreenDefinition(
                    fuelBinding = null,
                    chargeBinding = null,
                    playerInventory = { null },
                ),
                profile,
                parent = null,
            )
        }
        context.waitForScreen(FabricMinecraftScreen::class.java)
        context.input.setCursorPos(industrialPointer.x.toDouble(), industrialPointer.y.toDouble())
        context.waitTicks(2)
        NativeImage.read(headlessPath.inputStream()).use { expected ->
            context.assertScreenshotEquals(
                TestScreenshotComparisonOptions
                    .of(expected)
                    .withAlgorithm(TestScreenshotComparisonAlgorithm.exact())
                    .saveWithFileName("strata-industrial-fabric")
                    .disableCounterPrefix()
                    .withSize(industrialViewport.width, industrialViewport.height)
                    .withDestinationDir(output),
            )
        }
        return headless
    }

    private fun loadPanel(context: ClientGameTestContext): DrawImage =
        context.computeOnClient(
            FailableFunction<Minecraft, DrawImage, RuntimeException> {
                loadMinecraftUiImage(
                    ResourceId(
                        "strata_test",
                        "textures/gui/coal_generator.png",
                    ),
                )
            },
        )

    private val industrialViewport = IntSize(320, 180)
    private val industrialPointer = IntOffset.Zero
    private val industrialAssetSize = IntSize(1229, 1280)
}
