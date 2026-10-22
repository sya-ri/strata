package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.extractMinecraftUiProfile
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.gui.screens.Screen
import java.nio.file.Files
import java.nio.file.Path

/**
 * Compares ordinary native default-font text with portable and Fabric Text at actual GUI scales one through three.
 * The active resource fonts and language options remain unchanged; owned screens close and the caller's screen/window state is restored on every exit.
 * Additional original Text/TextArea component previews are explicitly labelled headless-only and are newly rasterized at scales two and three.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftTextReadabilitySuite {
    /**
     * Recreates bounded readability evidence using normal native rendering and strict full-frame ARGB equality.
     * Game operations remain on the client thread; a receipt is written only after unchanged input hashes and all six comparisons succeed.
     */
    fun run(
        context: MinecraftLoadedTestContext,
        output: Path,
    ) {
        Files.createDirectories(output)
        Files.deleteIfExists(output.resolve("readability.properties"))
        context.waitFor { MinecraftClientScreenAccess.hasOverlay(it).not() }
        val state =
            context.computeOnClient { minecraft ->
                ClientState(MinecraftClientScreenAccess.currentScreen(minecraft), minecraft.window.width, minecraft.window.height, minecraft.options.guiScale().get())
            }
        val verifiedInputs =
            MinecraftNumericFontCleanup.preserving({
                val profile = context.computeOnClient { extractMinecraftUiProfile() }
                val inputs = context.computeOnClient { MinecraftTextReadabilityEvidence.inputs(it, profile) }
                MinecraftTextReadabilityEvidence.write(output.resolve("inputs.properties"), inputs)
                val assertions = MinecraftFontPixelAssertions(output)
                val images = captureNative(context, output, profile, assertions)
                compareFabric(context, output, profile, images, assertions)
                previews(context, output, profile)
                val after = context.computeOnClient { MinecraftTextReadabilityEvidence.inputs(it, profile) }
                check(inputs == after) { "Active font resources or selections changed during the readability captures." }
                assertions.verify()
                inputs
            }) {
                restore(context, state)
            }
        MinecraftTextReadabilityEvidence.receipt(output, verifiedInputs)
    }

    private fun captureNative(
        context: MinecraftLoadedTestContext,
        output: Path,
        profile: MinecraftUiProfile,
        assertions: MinecraftFontPixelAssertions,
    ): Map<Int, HeadlessImage> {
        context.setScreen { MinecraftNativeTextReadabilityScreen() }
        context.waitForScreen(MinecraftNativeTextReadabilityScreen::class.java)
        val images = mutableMapOf<Int, HeadlessImage>()
        for (scale in 1..3) {
            val size = setScale(context, output, scale, "native-scale-$scale.properties")
            val native = screenshot(context, output, "text-native-$scale", size)
            val portable =
                context.computeOnClient { MinecraftTextReadabilityScene.render(profile, scale) }
            images[scale] = portable
            assertions.compare(native, portable, output.resolve("text-headless-$scale.png"))
        }
        return images
    }

    private fun compareFabric(
        context: MinecraftLoadedTestContext,
        output: Path,
        profile: MinecraftUiProfile,
        images: Map<Int, HeadlessImage>,
        assertions: MinecraftFontPixelAssertions,
    ) {
        val screen =
            context.computeOnClient {
                createMinecraftScreen(MinecraftTextReadabilityScene.definition(), profile, parent = null)
            }
        MinecraftNumericFontCleanup.preserving({
            context.setScreen { screen }
            context.waitForScreen(FabricMinecraftScreen::class.java)
            for (scale in 1..3) {
                val size = setScale(context, output, scale, "fabric-scale-$scale.properties")
                assertions.compare(screenshot(context, output, "text-fabric-$scale", size), checkNotNull(images[scale]), output.resolve("text-headless-$scale.png"))
            }
        }) {
            context.runOnClient { minecraft ->
                MinecraftNumericFontCleanup.preserving({
                    if (MinecraftClientScreenAccess.currentScreen(minecraft) === screen) MinecraftClientScreenAccess.setScreen(minecraft, null)
                }, screen::close)
            }
        }
    }

    private fun setScale(
        context: MinecraftLoadedTestContext,
        output: Path,
        scale: Int,
        evidenceName: String,
    ): IntSize {
        val viewport = MinecraftTextReadabilityScene.viewport
        val size = IntSize(viewport.width * scale, viewport.height * scale)
        resizeMinecraftTestWindow(context, IntSize(size.width, size.height))
        context.runOnClient { minecraft ->
            minecraft.options.guiScale().set(scale)
            minecraft.resizeGui()
            check(minecraft.window.guiScale == scale) { "Native readability GUI scale differs from the requested physical density." }
            check(minecraft.window.guiScaledWidth == viewport.width && minecraft.window.guiScaledHeight == viewport.height)
            MinecraftTextReadabilityEvidence.write(
                output.resolve(evidenceName),
                mapOf(
                    "requested.guiScale" to scale.toString(),
                    "actual.guiScale" to minecraft.window.guiScale.toString(),
                    "logical.width" to minecraft.window.guiScaledWidth.toString(),
                    "logical.height" to minecraft.window.guiScaledHeight.toString(),
                    "physical.width" to minecraft.window.width.toString(),
                    "physical.height" to minecraft.window.height.toString(),
                    "native.graphicsState" to MinecraftNativeFontOracle.graphicsState(),
                ),
            )
        }
        context.movePointerRaw(0.0, 0.0)
        context.waitTicks(3)
        return size
    }

    private fun previews(
        context: MinecraftLoadedTestContext,
        output: Path,
        profile: MinecraftUiProfile,
    ) {
        context.runOnClient {
            for (scale in 2..3) {
                val text = MinecraftTextReadabilityScene.render(profile, scale, createTextShowcaseScreenDefinition(), IntSize(192, 88))
                Files.write(output.resolve("showcase-text-headless-$scale.png"), text.encodePng())
                val area = MinecraftTextReadabilityScene.render(profile, scale, createTextAreaShowcaseScreenDefinition(), IntSize(226, 80))
                Files.write(output.resolve("showcase-text-area-headless-$scale.png"), area.encodePng())
            }
        }
    }

    private fun screenshot(
        context: MinecraftLoadedTestContext,
        output: Path,
        name: String,
        size: IntSize,
    ): Path =
        context.takeScreenshot(
            name,
            output,
            IntSize(size.width, size.height),
        )

    private fun restore(
        context: MinecraftLoadedTestContext,
        state: ClientState,
    ) {
        MinecraftNumericFontCleanup.preserving({
            context.runOnClient { MinecraftClientScreenAccess.setScreen(it, state.screen) }
        }) {
            resizeMinecraftTestWindow(context, IntSize(state.width, state.height))
            context.runOnClient { minecraft ->
                minecraft.options.guiScale().set(state.guiScale)
                minecraft.resizeGui()
            }
        }
    }

    private data class ClientState(
        val screen: Screen?,
        val width: Int,
        val height: Int,
        val guiScale: Int,
    )
}
