package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.PlayerSkinSource
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.loadCurrentMinecraftPlayerSkin
import dev.s7a.strata.runtime.minecraft.fabric.loadMinecraftUiImage
import dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotComparisonAlgorithm
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotComparisonOptions
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.social.SocialInteractionsScreen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import org.apache.commons.lang3.function.FailableConsumer
import org.apache.commons.lang3.function.FailableFunction
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Predicate
import kotlin.io.path.inputStream

/**
 * Runs the complete native, Fabric, and headless Social Interactions parity scenario.
 *
 * The loaded-client caller owns the client thread, active single-player connection, output directory, and profile lifetime.
 */
internal object MinecraftSocialParity {
    /**
     * Captures the real one-player screen and requires exact full-frame equality with the primitive composition through both Strata backends.
     *
     * @param context loaded Fabric client GameTest context.
     * @param profile immutable active-resource Minecraft UI profile.
     * @param output contained build directory receiving evidence images.
     * @return detached verified headless pixels.
     * @throws AssertionError when geometry, draw order, assets, or any pixel differs.
     * @throws Throwable when client, resource, rendering, or filesystem work fails.
     */
    @OptIn(InternalStrataRuntimeApi::class)
    @Suppress("LongMethod")
    internal fun run(
        context: ClientGameTestContext,
        profile: MinecraftUiProfile,
        output: Path,
    ): HeadlessImage {
        val assets = loadAssets(context)
        context.input.resizeWindow(viewport.width, viewport.height)
        context.runOnClient(FailableConsumer<Minecraft, RuntimeException> { minecraft -> minecraft.resizeGui() })
        context.input.setCursorPos(0.0, 0.0)
        context.setScreen { DeterministicSocialInteractionsScreen() }
        context.waitForScreen(DeterministicSocialInteractionsScreen::class.java)
        context.waitTicks(2)
        val nativePath =
            context.takeScreenshot(
                TestScreenshotOptions
                    .of("strata-social-native")
                    .disableCounterPrefix()
                    .withSize(viewport.width, viewport.height)
                    .withDestinationDir(output),
            )
        val headless =
            NativeImage.read(nativePath.inputStream()).use { native ->
                require(native.getWidth() == viewport.width && native.getHeight() == viewport.height) {
                    "The native Social Interactions capture has the wrong size."
                }
                val rendered = renderHeadless(profile, assets)
                Files.write(output.resolve("strata-social-headless.png"), rendered.encodePng())
                requireExactPixels(native, rendered)
                context.setScreen { createMinecraftScreen(definition(assets), profile, parent = null) }
                context.waitForScreen(FabricMinecraftScreen::class.java)
                context.waitTicks(2)
                context.assertScreenshotEquals(
                    TestScreenshotComparisonOptions
                        .of(native)
                        .withAlgorithm(TestScreenshotComparisonAlgorithm.exact())
                        .saveWithFileName("strata-social-fabric")
                        .disableCounterPrefix()
                        .withSize(viewport.width, viewport.height)
                        .withDestinationDir(output),
                )
                rendered
            }
        closeFabricScreen(context)
        return headless
    }

    private fun loadAssets(context: ClientGameTestContext): SocialAssets =
        context.computeOnClient(
            FailableFunction<Minecraft, SocialAssets, RuntimeException> {
                val skin = loadCurrentMinecraftPlayerSkin()
                val fixtureSkin = loadMinecraftUiImage(ResourceId("minecraft", "textures/entity/player/slim/efe.png"))
                require(it.gameProfile.name == "Player0") { "The Social showcase requires the fixed Player0 test identity." }
                require(skin.size == fixtureSkin.size && skin.copyArgb().contentEquals(fixtureSkin.copyArgb())) {
                    "The Social showcase requires the fixed original Efe skin used by independent headless generation."
                }
                SocialAssets(
                    loadMinecraftUiImage(ResourceId("minecraft", "textures/gui/sprites/social_interactions/background.png")),
                    loadMinecraftUiImage(ResourceId("minecraft", "textures/gui/sprites/icon/search.png")),
                    skin,
                    it.gameProfile.name,
                )
            },
        )

    @OptIn(InternalStrataRuntimeApi::class)
    private fun renderHeadless(
        profile: MinecraftUiProfile,
        assets: SocialAssets,
    ): HeadlessImage {
        val host = createMinecraftUiHost(definition(assets), profile, LwjglMinecraftFontBackendFactory)
        return try {
            host.attach()
            val frame = host.frame(viewport)
            val expectedPlayerLabels =
                setOf(
                    UiText.Literal("${assets.playerName} - New World - 1 player"),
                    UiText.Literal(assets.playerName),
                )
            val actualLabels = frame.semantics.mapNotNull { entry -> entry.semantics.label }.toSet()
            require(actualLabels.containsAll(expectedPlayerLabels)) {
                "The headless Social Interactions definition did not retain the active production profile name '${assets.playerName}'."
            }
            rasterizeHeadless(frame.drawCommands, viewport)
        } finally {
            host.close()
        }
    }

    private fun definition(assets: SocialAssets) =
        createSocialScreenDefinition(
            panel = ImageSource.Pixels(assets.panel),
            searchIcon = ImageSource.Pixels(assets.search),
            playerSkin = PlayerSkinSource.Pixels(assets.skin),
            playerName = assets.playerName,
        )

    private fun requireExactPixels(
        native: NativeImage,
        headless: HeadlessImage,
    ) {
        require(headless.size == viewport) { "The headless Social Interactions image has the wrong size." }
        for (y in 0 until viewport.height) {
            for (x in 0 until viewport.width) {
                val expected = native.getPixel(x, y)
                val actual = headless.argbAt(x, y)
                if (actual == expected) continue
                throw AssertionError(
                    "Headless Social Interactions pixel at ($x,$y) was 0x${actual.toUInt().toString(16)}, expected native 0x${expected.toUInt().toString(16)}.",
                )
            }
        }
    }

    private fun closeFabricScreen(context: ClientGameTestContext) {
        context.runOnClient(
            FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                val current = MinecraftClientScreenAccess.currentScreen(minecraft)
                if (current is FabricMinecraftScreen) current.onClose()
            },
        )
        context.waitFor(Predicate<Minecraft> { minecraft -> (MinecraftClientScreenAccess.currentScreen(minecraft) is FabricMinecraftScreen).not() })
    }

    private class DeterministicSocialInteractionsScreen : SocialInteractionsScreen() {
        override fun extractBackground(
            graphics: GuiGraphicsExtractor,
            mouseX: Int,
            mouseY: Int,
            partialTick: Float,
        ) {
            graphics.fill(0, 0, width, height, opaqueBlack)
            Screen.extractMenuBackgroundTexture(graphics, Screen.MENU_BACKGROUND, 0, 0, 0.0f, 0.0f, width, height)
            val marginX = (width - 238) / 2
            val windowHeight = maxOf(52, height - 144)
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, socialBackground, marginX + 3, 64, 236, windowHeight + 16)
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, searchIcon, marginX + 13, 76, 12, 12)
        }
    }

    private data class SocialAssets(
        val panel: DrawImage,
        val search: DrawImage,
        val skin: DrawImage,
        val playerName: String,
    )

    private val viewport = IntSize(320, 240)
    private val opaqueBlack = 0xFF000000.toInt()
    private val socialBackground = Identifier.withDefaultNamespace("social_interactions/background")
    private val searchIcon = Identifier.withDefaultNamespace("icon/search")
}
