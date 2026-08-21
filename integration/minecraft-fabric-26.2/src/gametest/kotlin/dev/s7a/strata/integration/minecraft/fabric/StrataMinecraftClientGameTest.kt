package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import dev.s7a.strata.dsl.Box
import dev.s7a.strata.dsl.Column
import dev.s7a.strata.dsl.Row
import dev.s7a.strata.dsl.Spacer
import dev.s7a.strata.dsl.buildUi
import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.fillMaxSize
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.MinecraftUiContext
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.createMinecraftScreenDefinition
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.extractMinecraftUiProfile
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotComparisonAlgorithm
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotComparisonOptions
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.apache.commons.lang3.function.FailableConsumer
import org.apache.commons.lang3.function.FailableFunction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.function.Predicate
import kotlin.io.path.inputStream

/**
 * Proves exact Minecraft 26.2 native, Fabric-adapter, and headless pixels for the shipped component showcase scene.
 *
 * The test runs on Fabric's client GameTest thread and performs all Minecraft and common-host operations on the client thread through [ClientGameTestContext].
 * It reads the active 26.2 assets and font, uses native [Button] components as the oracle, and writes only deterministic evidence below the configured build directory.
 */
public class StrataMinecraftClientGameTest : FabricClientGameTest {
    /**
     * Executes the exact three-path comparison and writes the verified headless frame.
     *
     * @param context Fabric client GameTest context owning window, client-thread, and screenshot operations.
     * @throws AssertionError when version, geometry, or any native/Fabric/headless pixel differs.
     * @throws Throwable when Minecraft resource, rendering, input, or filesystem work fails.
     */
    @OptIn(InternalStrataRuntimeApi::class)
    override fun runTest(context: ClientGameTestContext) {
        context.restoreDefaultGameOptions()
        context.input.resizeWindow(viewport.width, viewport.height)
        context.runOnClient(
            FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                minecraft.options.guiScale().set(1)
                minecraft.options.forceUnicodeFont().set(false)
                require(minecraft.options.languageCode == "en_us") {
                    "Minecraft parity requires the en_us language profile."
                }
                minecraft.resizeGui()
            },
        )
        context.input.setCursorPos(pointer.x.toDouble(), pointer.y.toDouble())

        val profile =
            context.computeOnClient(
                FailableFunction<Minecraft, MinecraftUiProfile, RuntimeException> { extractMinecraftUiProfile() },
            )
        val output = parityOutput()
        Files.createDirectories(output)

        context.setScreen { NativeParityScreen() }
        context.waitForScreen(NativeParityScreen::class.java)
        context.waitTicks(2)
        val nativePath =
            context.takeScreenshot(
                TestScreenshotOptions
                    .of("strata-native")
                    .disableCounterPrefix()
                    .withSize(viewport.width, viewport.height)
                    .withDestinationDir(output),
            )

        NativeImage.read(nativePath.inputStream()).use { native ->
            requireImageSize(native)
            val headless =
                context.computeOnClient(
                    FailableFunction<Minecraft, HeadlessImage, RuntimeException> { renderHeadless(profile) },
                )
            Files.write(output.resolve("strata-headless.png"), headless.encodePng())
            requireExactPixels(native, headless)

            context.setScreen { createMinecraftScreen(createDefinition(), profile, parent = null) }
            context.waitForScreen(FabricMinecraftScreen::class.java)
            context.waitTicks(2)
            context.assertScreenshotEquals(
                TestScreenshotComparisonOptions
                    .of(native)
                    .withAlgorithm(TestScreenshotComparisonAlgorithm.exact())
                    .saveWithFileName("strata-fabric")
                    .disableCounterPrefix()
                    .withSize(viewport.width, viewport.height)
                    .withDestinationDir(output),
            )
            writeParityEvidence(output, headless)
        }

        context.runOnClient(
            FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                val current = minecraft.gui.screen()
                if (current is FabricMinecraftScreen) current.onClose()
            },
        )
        context.waitFor(Predicate<Minecraft> { minecraft -> (minecraft.gui.screen() is FabricMinecraftScreen).not() })
    }

    @OptIn(InternalStrataRuntimeApi::class)
    private fun renderHeadless(profile: MinecraftUiProfile): HeadlessImage {
        val host = createMinecraftUiHost(createDefinition(), profile)
        host.attach()
        return try {
            host.frame(viewport)
            host.dispatchPointer(PointerEvent.Move(pointer))
            val frame = host.frame(viewport)
            val framebufferClear =
                DrawCommand.FillRectangle(
                    IntRect(0, 0, frame.size.width, frame.size.height),
                    ArgbColor(opaqueBlack),
                )
            rasterizeHeadless(listOf(framebufferClear) + frame.drawCommands, frame.size)
        } finally {
            host.close()
        }
    }

    private fun createDefinition() =
        createMinecraftScreenDefinition(UiText.Literal("Strata parity")) { minecraft ->
            createDescription(minecraft)
        }

    private fun createDescription(minecraft: MinecraftUiContext): Element =
        buildUi {
            Box(modifier = Modifier.Empty.size(viewport.width, viewport.height)) {
                element(minecraft.menuBackground(modifier = Modifier.Empty.fillMaxSize()))
                Column(modifier = Modifier.Empty.fillMaxSize()) {
                    Row(modifier = Modifier.Empty.size(viewport.width, panelSize.height)) {
                        overviewPanel(minecraft)
                        rowPanel(minecraft)
                    }
                    Row(modifier = Modifier.Empty.size(viewport.width, panelSize.height)) {
                        columnPanel(minecraft)
                        boxPanel(minecraft)
                    }
                    Row(modifier = Modifier.Empty.size(viewport.width, panelSize.height)) {
                        spacerPanel(minecraft)
                        Spacer(modifier = Modifier.Empty.size(panelSize.width, panelSize.height))
                    }
                }
            }
        }

    private fun requireImageSize(image: NativeImage) {
        if (image.getWidth() == viewport.width && image.getHeight() == viewport.height) return
        throw AssertionError("Native screenshot size was ${image.getWidth()}x${image.getHeight()}, expected ${viewport.width}x${viewport.height}.")
    }

    private fun requireExactPixels(
        native: NativeImage,
        headless: HeadlessImage,
    ) {
        if ((headless.size == viewport).not()) {
            throw AssertionError("Headless size was ${headless.size}, expected $viewport.")
        }
        for (y in 0 until viewport.height) {
            for (x in 0 until viewport.width) {
                val expected = native.getPixel(x, y)
                val actual = headless.argbAt(x, y)
                if (actual == expected) continue
                throw AssertionError(
                    "Headless pixel at ($x,$y) was 0x${actual.toUInt().toString(16)}, expected native 0x${expected.toUInt().toString(16)}.",
                )
            }
        }
    }

    private fun writeParityEvidence(
        output: Path,
        headless: HeadlessImage,
    ) {
        val imageDirectory = output.resolve("components")
        Files.createDirectories(imageDirectory)
        val source = createDrawImage(headless.size, headless.copyArgb())
        val pngHashes = LinkedHashMap<ParityCrop, String>()
        for (crop in ParityCrop.entries) {
            val image =
                rasterizeHeadless(
                    listOf(
                        DrawCommand.BlitImage(
                            source,
                            IntRect(
                                crop.origin.x,
                                crop.origin.y,
                                crop.origin.x + panelSize.width,
                                crop.origin.y + panelSize.height,
                            ),
                            IntRect(0, 0, panelSize.width, panelSize.height),
                        ),
                    ),
                    panelSize,
                )
            val png = image.encodePng()
            Files.write(imageDirectory.resolve("${crop.slug}.png"), png)
            pngHashes[crop] = sha256(png)
        }
        val pixelHash = sha256Argb(headless)
        val receipt =
            buildString {
                append("minecraft.version=26.2\n")
                append("viewport.width=640\n")
                append("viewport.height=540\n")
                append("gui.scale=1\n")
                append("locale=en_us\n")
                append("native.fabric.headless.argb.sha256=")
                append(pixelHash)
                append('\n')
                ParityCrop.entries.forEach { crop ->
                    append("component.")
                    append(crop.slug)
                    append(".png.sha256=")
                    append(pngHashes.getValue(crop))
                    append('\n')
                }
            }
        Files.write(output.resolve("receipt.properties"), receipt.toByteArray(StandardCharsets.UTF_8))
    }

    private fun sha256Argb(image: HeadlessImage): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(4)
        for (y in 0 until image.size.height) {
            for (x in 0 until image.size.width) {
                val pixel = image.argbAt(x, y)
                bytes[0] = (pixel ushr 24).toByte()
                bytes[1] = (pixel ushr 16).toByte()
                bytes[2] = (pixel ushr 8).toByte()
                bytes[3] = pixel.toByte()
                digest.update(bytes)
            }
        }
        return hexFormat.formatHex(digest.digest())
    }

    private fun sha256(bytes: ByteArray): String = hexFormat.formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun parityOutput(): Path {
        val configured = System.getProperty("strata.minecraftParityOutput")
        require(configured.isNullOrBlank().not()) { "The Minecraft parity output directory is not configured." }
        return Path.of(configured)
    }

    private class NativeParityScreen : Screen(Component.literal("Strata parity")) {
        private val overviewButton = button("Continue", 85, 40)
        private val rowLeftButton = button("Left", 325, 40)
        private val rowRightButton = button("Right", 485, 40)
        private val columnEnabledButton = button("Enabled", 85, 220)
        private val columnDisabledButton = button("Disabled", 85, 244).also { it.active = false }
        private val boxTopButton = button("Top start", 320, 180)
        private val boxBottomButton = button("Bottom end", 490, 340)
        private val spacerButton = button("Continue", 85, 440)

        override fun init() {
            addWidget(overviewButton)
            addWidget(rowLeftButton)
            addWidget(rowRightButton)
            addWidget(columnEnabledButton)
            addWidget(columnDisabledButton)
            addWidget(boxTopButton)
            addWidget(boxBottomButton)
            addWidget(spacerButton)
        }

        override fun extractBackground(
            graphics: GuiGraphicsExtractor,
            mouseX: Int,
            mouseY: Int,
            partialTick: Float,
        ) {
            extractMenuBackground(graphics)
        }

        override fun extractRenderState(
            graphics: GuiGraphicsExtractor,
            mouseX: Int,
            mouseY: Int,
            partialTick: Float,
        ) {
            text(graphics, "Overview", 0, 20)
            overviewButton.extractRenderState(graphics, mouseX, mouseY, partialTick)
            text(graphics, "Row", 320, 20)
            rowLeftButton.extractRenderState(graphics, mouseX, mouseY, partialTick)
            rowRightButton.extractRenderState(graphics, mouseX, mouseY, partialTick)
            text(graphics, "Column", 0, 200)
            columnEnabledButton.extractRenderState(graphics, mouseX, mouseY, partialTick)
            columnDisabledButton.extractRenderState(graphics, mouseX, mouseY, partialTick)
            text(graphics, "Box", 320, 265)
            boxTopButton.extractRenderState(graphics, mouseX, mouseY, partialTick)
            boxBottomButton.extractRenderState(graphics, mouseX, mouseY, partialTick)
            text(graphics, "Spacer", 0, 380)
            spacerButton.extractRenderState(graphics, mouseX, mouseY, partialTick)
        }

        private fun text(
            graphics: GuiGraphicsExtractor,
            value: String,
            panelX: Int,
            y: Int,
        ) {
            val x = panelX + (panelSize.width - font.width(value)) / 2
            graphics.text(font, value, x, y, opaqueWhite, true)
        }

        private fun button(
            label: String,
            x: Int,
            y: Int,
        ): Button =
            Button
                .builder(Component.literal(label)) {}
                .bounds(x, y, buttonSize.width, buttonSize.height)
                .build()
    }

    private companion object {
        private val viewport = IntSize(640, 540)
        private val panelSize = IntSize(320, 180)
        private val buttonSize = IntSize(150, 20)
        private val pointer = IntOffset(100, 50)
        private val opaqueBlack = 0xFF000000.toInt()
        private val opaqueWhite = 0xFFFFFFFF.toInt()
        private val hexFormat: HexFormat = HexFormat.of()
    }

    private enum class ParityCrop(
        val slug: String,
        val origin: IntOffset,
    ) {
        Overview("overview", IntOffset(0, 0)),
        Row("row", IntOffset(320, 0)),
        Column("column", IntOffset(0, 180)),
        Box("box", IntOffset(320, 180)),
        Spacer("spacer", IntOffset(0, 360)),
    }
}
