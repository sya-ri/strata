package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.MinecraftAssets
import dev.s7a.strata.runtime.minecraft.MinecraftScreenDefinition
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.extractMinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.fabric.loadCurrentMinecraftPlayerSkin
import dev.s7a.strata.runtime.minecraft.fabric.loadMinecraftUiImage
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import it.unimi.dsi.fastutil.booleans.BooleanConsumer
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotComparisonAlgorithm
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotComparisonOptions
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.ObjectSelectionList
import net.minecraft.client.gui.components.PlayerFaceExtractor
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.client.gui.screens.DirectJoinServerScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.PlayerSkin
import net.minecraft.world.inventory.ChestMenu
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
 * It reads the active 26.2 assets, font, and selected player skin, uses native [Button] and [PlayerFaceExtractor] rendering as the oracle, and writes only deterministic evidence below the configured build directory.
 */
@Suppress("TooManyFunctions")
public class StrataMinecraftClientGameTest : FabricClientGameTest {
    /**
     * Executes the exact three-path comparison and writes the verified headless frame.
     *
     * @param context Fabric client GameTest context owning window, client-thread, and screenshot operations.
     * @throws AssertionError when version, geometry, or any native/Fabric/headless pixel differs.
     * @throws Throwable when Minecraft resource, rendering, input, or filesystem work fails.
     */
    @OptIn(InternalStrataRuntimeApi::class)
    @Suppress("LongMethod")
    override fun runTest(context: ClientGameTestContext) {
        context.restoreDefaultGameOptions()
        context.input.resizeWindow(viewport.width, viewport.height)
        context.runOnClient(
            FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                minecraft.options.guiScale().set(1)
                minecraft.options.forceUnicodeFont().set(false)
                minecraft.options.showAutosaveIndicator().set(false)
                require(ParityLocale.parse(minecraft.options.languageCode) === ParityLocale.EnglishUnitedStates) {
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

        context.setScreen { DeterministicConfirmScreen() }
        context.waitForScreen(DeterministicConfirmScreen::class.java)
        context.waitTicks(2)
        val nativePath =
            context.takeScreenshot(
                TestScreenshotOptions
                    .of("strata-native")
                    .disableCounterPrefix()
                    .withSize(viewport.width, viewport.height)
                    .withDestinationDir(output),
            )

        val confirmHeadless =
            NativeImage.read(nativePath.inputStream()).use { native ->
                requireImageSize(native, viewport)
                val headless =
                    context.computeOnClient(
                        FailableFunction<Minecraft, HeadlessImage, RuntimeException> { renderHeadless(profile, createConfirmScreenDefinition(), viewport) },
                    )
                Files.write(output.resolve("strata-headless.png"), headless.encodePng())
                requireExactPixels(native, headless)

                context.setScreen { createMinecraftScreen(createConfirmScreenDefinition(), profile, parent = null) }
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
                headless
            }

        closeFabricScreen(context)

        context.setScreen { DeterministicScrollScreen() }
        context.waitForScreen(DeterministicScrollScreen::class.java)
        context.waitTicks(2)
        val scrollNativePath =
            context.takeScreenshot(
                TestScreenshotOptions
                    .of("strata-scroll-native")
                    .disableCounterPrefix()
                    .withSize(viewport.width, viewport.height)
                    .withDestinationDir(output),
            )

        NativeImage.read(scrollNativePath.inputStream()).use { native ->
            requireImageSize(native, viewport)
            val scrollHeadless =
                context.computeOnClient(
                    FailableFunction<Minecraft, HeadlessImage, RuntimeException> { renderHeadless(profile, createScrollScreenDefinition(), viewport) },
                )
            Files.write(output.resolve("strata-scroll-headless.png"), scrollHeadless.encodePng())
            requireExactPixels(native, scrollHeadless)

            context.setScreen { createMinecraftScreen(createScrollScreenDefinition(), profile, parent = null) }
            context.waitForScreen(FabricMinecraftScreen::class.java)
            context.waitTicks(2)
            context.assertScreenshotEquals(
                TestScreenshotComparisonOptions
                    .of(native)
                    .withAlgorithm(TestScreenshotComparisonAlgorithm.exact())
                    .saveWithFileName("strata-scroll-fabric")
                    .disableCounterPrefix()
                    .withSize(viewport.width, viewport.height)
                    .withDestinationDir(output),
            )
            runDirectJoinParity(context, profile, output, confirmHeadless, scrollHeadless)
        }

        closeFabricScreen(context)
    }

    @OptIn(InternalStrataRuntimeApi::class)
    private fun runIndustrialAssetParity(
        context: ClientGameTestContext,
        profile: MinecraftUiProfile,
        output: Path,
    ): HeadlessImage {
        val panel =
            context.computeOnClient(
                FailableFunction<Minecraft, DrawImage, RuntimeException> {
                    loadMinecraftUiImage(
                        MinecraftAssets.resource(
                            "strata_test",
                            "textures/gui/industrial_panel.png",
                        ),
                    )
                },
            )
        require(panel.size == industrialAssetSize) { "The test Mod industrial resource has an unexpected size." }
        context.input.resizeWindow(industrialViewport.width, industrialViewport.height)
        context.runOnClient(
            FailableConsumer<Minecraft, RuntimeException> { minecraft -> minecraft.resizeGui() },
        )
        val headless =
            context.computeOnClient(
                FailableFunction<Minecraft, HeadlessImage, RuntimeException> {
                    renderHeadless(profile, createIndustrialScreenDefinition(panel), industrialViewport, industrialPointer)
                },
            )
        val headlessPath = output.resolve("strata-industrial-headless.png")
        Files.write(headlessPath, headless.encodePng())
        context.input.setCursorPos(industrialPointer.x.toDouble(), industrialPointer.y.toDouble())
        context.setScreen { createMinecraftScreen(createIndustrialScreenDefinition(panel), profile, parent = null) }
        context.waitForScreen(FabricMinecraftScreen::class.java)
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
        closeFabricScreen(context)
        return headless
    }

    @OptIn(InternalStrataRuntimeApi::class)
    private fun runPlayerHeadParity(
        context: ClientGameTestContext,
        profile: MinecraftUiProfile,
        output: Path,
    ): HeadlessImage {
        val skin =
            context.computeOnClient(
                FailableFunction<Minecraft, DrawImage, RuntimeException> { loadCurrentMinecraftPlayerSkin() },
            )
        context.input.resizeWindow(playerHeadViewport.width, playerHeadViewport.height)
        context.runOnClient(
            FailableConsumer<Minecraft, RuntimeException> { minecraft -> minecraft.resizeGui() },
        )
        context.input.setCursorPos(0.0, 0.0)
        context.setScreen { DeterministicPlayerHeadScreen(checkNotNull(Minecraft.getInstance().player).skin) }
        context.waitForScreen(DeterministicPlayerHeadScreen::class.java)
        context.waitTicks(2)
        val nativePath =
            context.takeScreenshot(
                TestScreenshotOptions
                    .of("strata-player-head-native")
                    .disableCounterPrefix()
                    .withSize(playerHeadViewport.width, playerHeadViewport.height)
                    .withDestinationDir(output),
            )
        val headless =
            NativeImage.read(nativePath.inputStream()).use { native ->
                requireImageSize(native, playerHeadViewport)
                val rendered =
                    context.computeOnClient(
                        FailableFunction<Minecraft, HeadlessImage, RuntimeException> {
                            renderHeadless(profile, createPlayerHeadScreenDefinition(skin), playerHeadViewport)
                        },
                    )
                Files.write(output.resolve("strata-player-head-headless.png"), rendered.encodePng())
                requireExactPixels(native, rendered)

                context.setScreen { createMinecraftScreen(createPlayerHeadScreenDefinition(skin), profile, parent = null) }
                context.waitForScreen(FabricMinecraftScreen::class.java)
                context.waitTicks(2)
                context.assertScreenshotEquals(
                    TestScreenshotComparisonOptions
                        .of(native)
                        .withAlgorithm(TestScreenshotComparisonAlgorithm.exact())
                        .saveWithFileName("strata-player-head-fabric")
                        .disableCounterPrefix()
                        .withSize(playerHeadViewport.width, playerHeadViewport.height)
                        .withDestinationDir(output),
                )
                rendered
            }
        closeFabricScreen(context)
        return headless
    }

    private fun closeFabricScreen(context: ClientGameTestContext) {
        context.runOnClient(
            FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                val current = minecraft.gui.screen()
                if (current is FabricMinecraftScreen) current.onClose()
            },
        )
        context.waitFor(Predicate<Minecraft> { minecraft -> (minecraft.gui.screen() is FabricMinecraftScreen).not() })
    }

    @OptIn(InternalStrataRuntimeApi::class)
    private fun renderHeadless(
        profile: MinecraftUiProfile,
        definition: MinecraftScreenDefinition,
        viewport: IntSize,
        pointerPosition: IntOffset = pointer,
    ): HeadlessImage {
        val host = createMinecraftUiHost(definition, profile)
        host.attach()
        return try {
            host.frame(viewport)
            host.dispatchPointer(PointerEvent.Move(pointerPosition))
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

    private fun requireImageSize(
        image: NativeImage,
        expected: IntSize,
    ) {
        if (image.getWidth() == expected.width && image.getHeight() == expected.height) return
        throw AssertionError("Native screenshot size was ${image.getWidth()}x${image.getHeight()}, expected ${expected.width}x${expected.height}.")
    }

    private fun requireExactPixels(
        native: NativeImage,
        headless: HeadlessImage,
    ) {
        val expected = IntSize(native.getWidth(), native.getHeight())
        if ((headless.size == expected).not()) {
            throw AssertionError("Headless size was ${headless.size}, expected $expected.")
        }
        for (y in 0 until expected.height) {
            for (x in 0 until expected.width) {
                val expected = native.getPixel(x, y)
                val actual = headless.argbAt(x, y)
                if (actual == expected) continue
                throw AssertionError(
                    "Headless pixel at ($x,$y) was 0x${actual.toUInt().toString(16)}, expected native 0x${expected.toUInt().toString(16)}.",
                )
            }
        }
    }

    @Suppress("LongMethod", "LongParameterList")
    private fun writeParityEvidence(
        output: Path,
        confirm: HeadlessImage,
        scroll: HeadlessImage,
        directJoin: HeadlessImage,
        containerBackground: HeadlessImage,
        slot: HeadlessImage,
        industrial: HeadlessImage,
        playerHead: HeadlessImage,
        social: HeadlessImage,
        progress: HeadlessImage,
    ) {
        val imageDirectory = output.resolve("components")
        Files.createDirectories(imageDirectory)
        val screenDirectory = output.resolve("screens")
        Files.createDirectories(screenDirectory)
        val sources =
            mapOf(
                ParityScene.Confirm to createDrawImage(confirm.size, confirm.copyArgb()),
                ParityScene.Scroll to createDrawImage(scroll.size, scroll.copyArgb()),
                ParityScene.DirectJoin to createDrawImage(directJoin.size, directJoin.copyArgb()),
                ParityScene.Slot to createDrawImage(slot.size, slot.copyArgb()),
                ParityScene.Industrial to createDrawImage(industrial.size, industrial.copyArgb()),
                ParityScene.PlayerHead to createDrawImage(playerHead.size, playerHead.copyArgb()),
            )
        val pngHashes = LinkedHashMap<ParityCrop, String>()
        for (crop in ParityCrop.entries) {
            val source = sources.getValue(crop.scene)
            val image =
                rasterizeHeadless(
                    listOf(
                        DrawCommand.BlitImage(
                            source,
                            IntRect(
                                crop.origin.x,
                                crop.origin.y,
                                crop.origin.x + crop.size.width,
                                crop.origin.y + crop.size.height,
                            ),
                            IntRect(0, 0, crop.size.width, crop.size.height),
                        ),
                    ),
                    crop.size,
                )
            val png = image.encodePng()
            Files.write(imageDirectory.resolve("${crop.slug}.png"), png)
            pngHashes[crop] = sha256(png)
        }
        val screenPngHashes =
            mapOf(
                ParityScreen.Social to social.encodePng(),
                ParityScreen.Inventory to Files.readAllBytes(output.resolve("strata-inventory-slot-fabric.png")),
                ParityScreen.Industrial to industrial.encodePng(),
                ParityScreen.Progress to progress.encodePng(),
            ).mapValues { (screen, png) ->
                Files.write(screenDirectory.resolve("${screen.slug}.png"), png)
                sha256(png)
            }
        val receipt =
            buildString {
                append("minecraft.version=26.2\n")
                append("viewport.width=320\n")
                append("viewport.height=180\n")
                append("gui.scale=1\n")
                append("locale=en_us\n")
                append("native.fabric.headless.argb.sha256=")
                append(sha256Argb(confirm))
                append('\n')
                append("native.fabric.headless.scroll.argb.sha256=")
                append(sha256Argb(scroll))
                append('\n')
                append("native.fabric.headless.direct-join.argb.sha256=")
                append(sha256Argb(directJoin))
                append('\n')
                append("native.fabric.headless.container-background.argb.sha256=")
                append(sha256Argb(containerBackground))
                append('\n')
                append("native.fabric.headless.slot.argb.sha256=")
                append(sha256Argb(slot))
                append('\n')
                append("fabric.headless.industrial.argb.sha256=")
                append(sha256Argb(industrial))
                append('\n')
                append("native.fabric.headless.player-head.argb.sha256=")
                append(sha256Argb(playerHead))
                append('\n')
                append("native.fabric.headless.social.argb.sha256=")
                append(sha256Argb(social))
                append('\n')
                append("fabric.headless.progress.argb.sha256=")
                append(sha256Argb(progress))
                append('\n')
                ParityCrop.entries.forEach { crop ->
                    append("component.")
                    append(crop.slug)
                    append(".png.sha256=")
                    append(pngHashes.getValue(crop))
                    append('\n')
                }
                ParityScreen.entries.forEach { screen ->
                    append("screen.")
                    append(screen.slug)
                    append(".png.sha256=")
                    append(screenPngHashes.getValue(screen))
                    append('\n')
                }
            }
        Files.write(output.resolve("receipt.properties"), receipt.toByteArray(StandardCharsets.UTF_8))
    }

    @OptIn(InternalStrataRuntimeApi::class)
    private fun runDirectJoinParity(
        context: ClientGameTestContext,
        profile: MinecraftUiProfile,
        output: Path,
        confirm: HeadlessImage,
        scroll: HeadlessImage,
    ) {
        closeFabricScreen(context)
        context.input.resizeWindow(directJoinViewport.width, directJoinViewport.height)
        context.runOnClient(
            FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                minecraft.options.lastMpIp = directJoinAddress
                minecraft.resizeGui()
            },
        )
        context.input.setCursorPos(pointer.x.toDouble(), pointer.y.toDouble())
        context.setScreen { DeterministicDirectJoinScreen() }
        context.waitForScreen(DeterministicDirectJoinScreen::class.java)
        context.runOnClient(
            FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                checkNotNull(minecraft.gui.screen()).setFocused(null)
            },
        )
        context.waitTicks(2)
        val nativePath =
            context.takeScreenshot(
                TestScreenshotOptions
                    .of("strata-direct-join-native")
                    .disableCounterPrefix()
                    .withSize(directJoinViewport.width, directJoinViewport.height)
                    .withDestinationDir(output),
            )
        NativeImage.read(nativePath.inputStream()).use { native ->
            requireImageSize(native, directJoinViewport)
            val directJoin =
                context.computeOnClient(
                    FailableFunction<Minecraft, HeadlessImage, RuntimeException> {
                        renderHeadless(profile, createDirectJoinScreenDefinition(), directJoinViewport)
                    },
                )
            Files.write(output.resolve("strata-direct-join-headless.png"), directJoin.encodePng())
            requireExactPixels(native, directJoin)

            context.setScreen { createMinecraftScreen(createDirectJoinScreenDefinition(), profile, parent = null) }
            context.waitForScreen(FabricMinecraftScreen::class.java)
            context.waitTicks(2)
            context.assertScreenshotEquals(
                TestScreenshotComparisonOptions
                    .of(native)
                    .withAlgorithm(TestScreenshotComparisonAlgorithm.exact())
                    .saveWithFileName("strata-direct-join-fabric")
                    .disableCounterPrefix()
                    .withSize(directJoinViewport.width, directJoinViewport.height)
                    .withDestinationDir(output),
            )
            runContainerParity(context, profile, output, confirm, scroll, directJoin)
        }
        closeFabricScreen(context)
    }

    @OptIn(InternalStrataRuntimeApi::class)
    @Suppress("LongMethod", "LongParameterList")
    private fun runContainerParity(
        context: ClientGameTestContext,
        profile: MinecraftUiProfile,
        output: Path,
        confirm: HeadlessImage,
        scroll: HeadlessImage,
        directJoin: HeadlessImage,
    ) {
        closeFabricScreen(context)
        val world = context.worldBuilder().setUseConsistentSettings(true).create()
        try {
            context.input.resizeWindow(containerViewport.width, containerViewport.height)
            context.runOnClient(
                FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                    minecraft.resizeGui()
                },
            )
            context.input.setCursorPos(containerOutsidePointer.x.toDouble(), containerOutsidePointer.y.toDouble())
            context.setScreen {
                val inventory = checkNotNull(Minecraft.getInstance().player).inventory
                inventory.clearContent()
                DeterministicContainerScreen(inventory)
            }
            context.waitForScreen(DeterministicContainerScreen::class.java)
            context.input.setCursorPos(containerOutsidePointer.x.toDouble(), containerOutsidePointer.y.toDouble())
            context.waitTicks(2)
            val backgroundNativePath =
                context.takeScreenshot(
                    TestScreenshotOptions
                        .of("strata-container-background-native")
                        .disableCounterPrefix()
                        .withSize(containerViewport.width, containerViewport.height)
                        .withDestinationDir(output),
                )
            context.input.setCursorPos(containerPointer.x.toDouble(), containerPointer.y.toDouble())
            context.waitTicks(2)
            val slotNativePath =
                context.takeScreenshot(
                    TestScreenshotOptions
                        .of("strata-slot-native")
                        .disableCounterPrefix()
                        .withSize(containerViewport.width, containerViewport.height)
                        .withDestinationDir(output),
                )

            NativeImage.read(backgroundNativePath.inputStream()).use { backgroundNative ->
                NativeImage.read(slotNativePath.inputStream()).use { slotNative ->
                    requireImageSize(backgroundNative, containerViewport)
                    requireImageSize(slotNative, containerViewport)
                    val containerBackground =
                        context.computeOnClient(
                            FailableFunction<Minecraft, HeadlessImage, RuntimeException> {
                                renderHeadless(profile, createContainerBackgroundScreenDefinition(), containerViewport, containerOutsidePointer)
                            },
                        )
                    val slot =
                        context.computeOnClient(
                            FailableFunction<Minecraft, HeadlessImage, RuntimeException> {
                                renderHeadless(profile, createSlotScreenDefinition(), containerViewport, containerPointer)
                            },
                        )
                    Files.write(output.resolve("strata-container-background-headless.png"), containerBackground.encodePng())
                    Files.write(output.resolve("strata-slot-headless.png"), slot.encodePng())
                    requireExactPixels(backgroundNative, containerBackground)
                    requireExactPixels(slotNative, slot)

                    context.input.setCursorPos(containerOutsidePointer.x.toDouble(), containerOutsidePointer.y.toDouble())
                    context.setScreen { createMinecraftScreen(createContainerBackgroundScreenDefinition(), profile, parent = null) }
                    context.waitForScreen(FabricMinecraftScreen::class.java)
                    context.input.setCursorPos(containerOutsidePointer.x.toDouble(), containerOutsidePointer.y.toDouble())
                    context.waitTicks(2)
                    context.assertScreenshotEquals(
                        TestScreenshotComparisonOptions
                            .of(backgroundNative)
                            .withAlgorithm(TestScreenshotComparisonAlgorithm.exact())
                            .saveWithFileName("strata-container-background-fabric")
                            .disableCounterPrefix()
                            .withSize(containerViewport.width, containerViewport.height)
                            .withDestinationDir(output),
                    )
                    closeFabricScreen(context)

                    context.input.setCursorPos(containerPointer.x.toDouble(), containerPointer.y.toDouble())
                    context.setScreen { createMinecraftScreen(createSlotScreenDefinition(), profile, parent = null) }
                    context.waitForScreen(FabricMinecraftScreen::class.java)
                    context.input.setCursorPos(containerPointer.x.toDouble(), containerPointer.y.toDouble())
                    context.waitTicks(2)
                    context.assertScreenshotEquals(
                        TestScreenshotComparisonOptions
                            .of(slotNative)
                            .withAlgorithm(TestScreenshotComparisonAlgorithm.exact())
                            .saveWithFileName("strata-slot-fabric")
                            .disableCounterPrefix()
                            .withSize(containerViewport.width, containerViewport.height)
                            .withDestinationDir(output),
                    )
                    InventorySlotSynchronizationGameTest.run(context, profile, output)
                    closeFabricScreen(context)
                    val industrial = runIndustrialAssetParity(context, profile, output)
                    val playerHead = runPlayerHeadParity(context, profile, output)
                    val social = MinecraftSocialParity.run(context, profile, output)
                    val progress = MinecraftProgressParity.run(context, profile, output)
                    writeParityEvidence(output, confirm, scroll, directJoin, containerBackground, slot, industrial, playerHead, social, progress)
                }
            }
            closeFabricScreen(context)
        } finally {
            world.close()
        }
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

    private class DeterministicConfirmScreen :
        ConfirmScreen(
            BooleanConsumer { _ -> },
            Component.literal(confirmTitle),
            Component.literal(confirmMessage),
        ) {
        override fun extractBackground(
            graphics: GuiGraphicsExtractor,
            mouseX: Int,
            mouseY: Int,
            partialTick: Float,
        ) {
            extractMenuBackground(graphics)
        }
    }

    private class DeterministicScrollScreen : Screen(Component.literal("Strata Scroll parity")) {
        override fun init() {
            addRenderableWidget(DeterministicSelectionList(checkNotNull(minecraft)))
        }

        override fun extractBackground(
            graphics: GuiGraphicsExtractor,
            mouseX: Int,
            mouseY: Int,
            partialTick: Float,
        ) {
            extractMenuBackground(graphics)
        }
    }

    private class DeterministicDirectJoinScreen :
        DirectJoinServerScreen(
            EmptyParentScreen(),
            BooleanConsumer { _ -> },
            ServerData("Strata parity", directJoinAddress, ServerData.Type.OTHER),
        ) {
        override fun extractBackground(
            graphics: GuiGraphicsExtractor,
            mouseX: Int,
            mouseY: Int,
            partialTick: Float,
        ) {
            extractMenuBackground(graphics)
        }
    }

    private class DeterministicContainerScreen(
        inventory: Inventory,
    ) : ContainerScreen(
            ChestMenu.threeRows(1, inventory),
            inventory,
            Component.literal("Chest"),
        ) {
        override fun extractBackground(
            graphics: GuiGraphicsExtractor,
            mouseX: Int,
            mouseY: Int,
            partialTick: Float,
        ) {
            graphics.fill(0, 0, width, height, opaqueBlack)
            Screen.extractMenuBackgroundTexture(
                graphics,
                Screen.MENU_BACKGROUND,
                0,
                0,
                0.0f,
                0.0f,
                width,
                height,
            )
            val panelWidth = 176
            val panelHeight = 168
            val left = (width - panelWidth) / 2
            val top = (height - panelHeight) / 2
            val upperHeight = 71
            graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                containerBackgroundIdentifier,
                left,
                top,
                0.0f,
                0.0f,
                panelWidth,
                upperHeight,
                256,
                256,
            )
            graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                containerBackgroundIdentifier,
                left,
                top + upperHeight,
                0.0f,
                126.0f,
                panelWidth,
                96,
                256,
                256,
            )
        }
    }

    private class EmptyParentScreen : Screen(Component.empty())

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

    private class DeterministicSelectionList(
        minecraft: Minecraft,
    ) : ObjectSelectionList<DeterministicEntry>(minecraft, 320, 94, 33, 18) {
        init {
            listEntries.forEach { label -> addEntry(DeterministicEntry(label)) }
        }

        override fun getRowWidth(): Int = 270
    }

    private class DeterministicEntry(
        private val label: String,
    ) : ObjectSelectionList.Entry<DeterministicEntry>() {
        override fun extractContent(
            graphics: GuiGraphicsExtractor,
            mouseX: Int,
            mouseY: Int,
            hovered: Boolean,
            partialTick: Float,
        ) {
            graphics.centeredText(Minecraft.getInstance().font, Component.literal(label), 160, getContentYMiddle() - 4, -1)
        }

        override fun getNarration(): Component = Component.literal(label)
    }

    private enum class ParityLocale(
        val code: String,
    ) {
        EnglishUnitedStates("en_us"),
        ;

        companion object {
            fun parse(value: String): ParityLocale =
                requireNotNull(entries.singleOrNull { locale -> locale.code == value }) {
                    "Minecraft parity requires a recognized locale."
                }
        }
    }

    private companion object {
        private val viewport = IntSize(320, 180)
        private val directJoinViewport = IntSize(320, 240)
        private val containerViewport = IntSize(320, 240)
        private val pointer = IntOffset(100, 110)
        private val containerOutsidePointer = IntOffset(0, 0)
        private val containerPointer = IntOffset(80, 54)

        @Suppress("MayBeConstant")
        private val directJoinAddress = "play.example.net"

        @Suppress("MayBeConstant")
        private val confirmTitle = "Confirm action"

        @Suppress("MayBeConstant")
        private val confirmMessage = "Continue with this action?"
        private val listEntries =
            listOf(
                "Entry 01",
                "Entry 02",
                "Entry 03",
                "Entry 04",
                "Entry 05",
                "Entry 06",
                "Entry 07",
                "Entry 08",
                "Entry 09",
                "Entry 10",
                "Entry 11",
                "Entry 12",
            )
        private val opaqueBlack = 0xFF000000.toInt()
        private val hexFormat: HexFormat = HexFormat.of()
        private val containerBackgroundIdentifier = Identifier.withDefaultNamespace("textures/gui/container/generic_54.png")
        private val industrialViewport = IntSize(320, 180)
        private val industrialPointer = IntOffset.Zero
        private val industrialAssetSize = IntSize(1254, 1254)
        private val playerHeadViewport = IntSize(64, 64)
    }

    private enum class ParityCrop(
        val slug: String,
        val scene: ParityScene,
        val origin: IntOffset,
        val size: IntSize,
    ) {
        Overview("overview", ParityScene.Confirm, IntOffset.Zero, IntSize(320, 180)),
        Text("text", ParityScene.Confirm, IntOffset(85, 50), IntSize(150, 20)),
        Button("button", ParityScene.Confirm, IntOffset(8, 105), IntSize(150, 20)),
        Scroll("scroll", ParityScene.Scroll, IntOffset(0, 33), IntSize(320, 94)),
        TextField("text-field", ParityScene.DirectJoin, IntOffset(60, 116), IntSize(200, 20)),
        Slot("slot", ParityScene.Slot, IntOffset(76, 50), IntSize(24, 24)),
        Image("image", ParityScene.Industrial, IntOffset(144, 30), IntSize(32, 32)),
        PlayerHead("player-head", ParityScene.PlayerHead, IntOffset(20, 20), IntSize(24, 24)),
    }

    private enum class ParityScene {
        Confirm,
        Scroll,
        DirectJoin,
        Slot,
        Industrial,
        PlayerHead,
    }

    private enum class ParityScreen(
        val slug: String,
    ) {
        Social("social"),
        Inventory("inventory"),
        Industrial("industrial"),
        Progress("progress"),
    }
}
