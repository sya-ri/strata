@file:Suppress("DEPRECATION") // Compatibility overloads and regression coverage retain the deprecated screen entry points.

package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.platform.NativeImage
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.integration.minecraft.fabric.MinecraftParityPresentation.activeFabricScreen
import dev.s7a.strata.integration.minecraft.fabric.MinecraftParityPresentation.assertCleanFabricFrameReuse
import dev.s7a.strata.integration.minecraft.fabric.MinecraftParityPresentation.closeFabricScreen
import dev.s7a.strata.integration.minecraft.fabric.MinecraftParityPresentation.readRenderWork
import dev.s7a.strata.integration.minecraft.fabric.MinecraftParityPresentation.renderWork
import dev.s7a.strata.integration.minecraft.fabric.MinecraftParityRendering.renderHeadless
import dev.s7a.strata.integration.minecraft.fabric.MinecraftParityRendering.requireExactPixels
import dev.s7a.strata.integration.minecraft.fabric.MinecraftParityRendering.requireImageSize
import dev.s7a.strata.modifier.size
import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.extractMinecraftUiProfile
import dev.s7a.strata.screen.ScreenDefinition
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
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ChestMenu
import org.apache.commons.lang3.function.FailableConsumer
import org.apache.commons.lang3.function.FailableFunction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CompletableFuture
import java.util.function.Predicate
import kotlin.io.path.inputStream
import dev.s7a.strata.integration.minecraft.fabric.MinecraftPresentationObservation.Snapshot as RenderWork

/**
 * Proves exact native, Fabric-adapter, and headless pixels for the supported Minecraft release.
 *
 * The test runs on Fabric's client GameTest thread and performs all Minecraft and common-host operations on the client thread through [ClientGameTestContext].
 * It reads the active assets, font, and selected player skin, uses native screens as the oracle, and writes only deterministic evidence below the configured build directory.
 */
@Suppress("TooManyFunctions")
public class StrataMinecraftClientGameTest : FabricClientGameTest {
    /**
     * Executes the native/Fabric/headless acceptance comparisons and the dedicated component Fabric/headless comparisons, then writes their verified evidence.
     *
     * @param context Fabric client GameTest context owning window, client-thread, and screenshot operations.
     * @throws AssertionError when version, geometry, or any native/Fabric/headless pixel differs.
     * @throws Throwable when Minecraft resource, rendering, input, or filesystem work fails.
     */
    @OptIn(InternalStrataRuntimeApi::class)
    @Suppress("LongMethod")
    override fun runTest(context: ClientGameTestContext) {
        context.restoreDefaultGameOptions()
        resizeMinecraftTestWindow(context, IntSize(viewport.width, viewport.height))
        context.runOnClient(
            FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                minecraft.options.guiScale().set(1)
                minecraft.options.forceUnicodeFont().set(false)
                minecraft.options.japaneseGlyphVariants().set(false)
                minecraft.options.showAutosaveIndicator().set(false)
                require(ParityLocale.parse(minecraft.options.languageCode) === ParityLocale.EnglishUnitedStates) {
                    "Minecraft parity requires the en_us language profile."
                }
                require(
                    minecraft.options
                        .forceUnicodeFont()
                        .get()
                        .not() &&
                        minecraft.options
                            .japaneseGlyphVariants()
                            .get()
                            .not() &&
                        Language.getInstance().isDefaultRightToLeft.not(),
                ) { "Minecraft parity requires the explicit non-uniform, non-Japanese-variant, left-to-right font options." }
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
        if (System.getProperty("strata.performance.nativeOutput") != null) {
            runMinecraftCanvasTest(context, profile, output)
            return
        }
        verifyProfileCache(context, output)
        MinecraftKeyboardTransitionGameTest(createMinecraftCanvasTestContext(context, output), profile).run()
        MinecraftNativeTextInputGameTest(context, profile, directJoinAddress).run()
        verifyContinuousInput(context, profile, output)
        verifyReactiveRendering(context, profile, output)
        runMinecraftCanvasTest(context, profile, output)
        runSampledImagePixelParity(context, profile, output)
        MinecraftResourceImageMemoizationGameTest.run(context, profile)

        context.setScreen { DeterministicConfirmScreen() }
        context.waitForScreen(DeterministicConfirmScreen::class.java)
        context.input.setCursorPos(pointer.x.toDouble(), pointer.y.toDouble())
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

                context.runOnClient(
                    FailableConsumer<Minecraft, RuntimeException> {
                        createConfirmScreenDefinition().open()
                    },
                )
                context.waitForScreen(FabricMinecraftScreen::class.java)
                context.input.setCursorPos(pointer.x.toDouble(), pointer.y.toDouble())
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
                assertCleanFabricFrameReuse(context)
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
        RemoteNativeGameTest.run(context)
    }

    @OptIn(InternalStrataRuntimeApi::class)
    private fun runSampledImagePixelParity(
        context: ClientGameTestContext,
        profile: MinecraftUiProfile,
        output: Path,
    ) {
        for (scale in 1..4) {
            closeFabricScreen(context)
            val physicalSize = IntSize(viewport.width * scale, viewport.height * scale)
            resizeMinecraftTestWindow(context, IntSize(physicalSize.width, physicalSize.height))
            context.runOnClient(
                FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                    minecraft.options.guiScale().set(scale)
                    minecraft.resizeGui()
                    minecraft.window.setGuiScale(scale)
                    check(minecraft.window.guiScaledWidth == viewport.width && minecraft.window.guiScaledHeight == viewport.height)
                },
            )
            SampledParityScene.entries.forEach { scene -> runSampledImagePixelParityAtScale(context, profile, output, scale, physicalSize, scene) }
        }
        resizeMinecraftTestWindow(context, IntSize(viewport.width, viewport.height))
        context.runOnClient(
            FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                minecraft.options.guiScale().set(1)
                minecraft.resizeGui()
            },
        )
    }

    @OptIn(InternalStrataRuntimeApi::class)
    private fun runSampledImagePixelParityAtScale(
        context: ClientGameTestContext,
        profile: MinecraftUiProfile,
        output: Path,
        scale: Int,
        physicalSize: IntSize,
        scene: SampledParityScene,
    ) {
        closeFabricScreen(context)
        val headless =
            context.computeOnClient(
                FailableFunction<Minecraft, HeadlessImage, RuntimeException> {
                    renderHeadless(profile, scene.definition(viewport), viewport, scale = scale)
                },
            )
        Files.write(output.resolve("${scene.slug}-scale-$scale-headless.png"), headless.encodePng())

        val presentation =
            runCatching {
                context.setScreen {
                    createMinecraftScreen(
                        scene.definition(viewport),
                        profile,
                        parent = null,
                    )
                }
                context.waitForScreen(FabricMinecraftScreen::class.java)
                context.waitFor(
                    Predicate<Minecraft> { minecraft ->
                        0L < readRenderWork(minecraft).renderExtractions && readRenderWork(minecraft).sources.isNotEmpty()
                    },
                )
                val observed = renderWork(context)
                requireSampledImageParityWork(observed, scene)
                val fabricPath =
                    context.takeScreenshot(
                        TestScreenshotOptions
                            .of("${scene.slug}-scale-$scale-fabric")
                            .disableCounterPrefix()
                            .withSize(physicalSize.width, physicalSize.height)
                            .withDestinationDir(output),
                    )
                NativeImage.read(fabricPath.inputStream()).use { fabric ->
                    requireImageSize(fabric, physicalSize)
                    requireExactPixels(fabric, headless)
                }
            }
        runCanvasTestCleanup(presentation.exceptionOrNull(), { closeFabricScreen(context) })
        presentation.getOrThrow()
    }

    private fun requireSampledImageParityWork(
        observed: RenderWork,
        scene: SampledParityScene,
    ) {
        require(observed.portableTextures.isNotEmpty() && observed.sources.isNotEmpty() && 0L < observed.sourceBytes) { "Sampled parity must own real portable and direct native textures: $scene" }
        val requested = MinecraftPresentationObservation.member(checkNotNull(observed.inputs), "sampled") as List<*>
        require(requested.all { image -> observed.sourceImages.any { it === image } }) { "Every admitted sampled source must have actual native ownership: $scene" }
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
        componentShowcases: Map<ComponentShowcase, ComponentShowcaseFrame>,
    ) {
        val imageDirectory = output.resolve("components")
        Files.createDirectories(imageDirectory)
        val screenDirectory = output.resolve("screens")
        Files.createDirectories(screenDirectory)
        val overviewPng = confirm.encodePng()
        Files.write(imageDirectory.resolve("overview.png"), overviewPng)
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
                append("minecraft.version=")
                append(minecraftVersion())
                append('\n')
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
                append("component.overview.png.sha256=")
                append(sha256(overviewPng))
                append('\n')
                ComponentShowcase.entries.forEach { showcase ->
                    val frame = componentShowcases.getValue(showcase)
                    append("component.")
                    append(showcase.slug)
                    append(".viewport.width=")
                    append(showcase.viewport.width)
                    append('\n')
                    append("component.")
                    append(showcase.slug)
                    append(".viewport.height=")
                    append(showcase.viewport.height)
                    append('\n')
                    append("component.")
                    append(showcase.slug)
                    append(".gui.scale=")
                    append(showcase.scale)
                    append('\n')
                    append("component.")
                    append(showcase.slug)
                    append(".fabric.headless.argb.sha256=")
                    append(sha256Argb(frame.image))
                    append('\n')
                    append("component.")
                    append(showcase.slug)
                    append(".png.sha256=")
                    append(sha256(frame.png))
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
        resizeMinecraftTestWindow(context, IntSize(directJoinViewport.width, directJoinViewport.height))
        context.runOnClient(
            FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                minecraft.options.lastMpIp = directJoinAddress
                minecraft.resizeGui()
            },
        )
        context.input.setCursorPos(pointer.x.toDouble(), pointer.y.toDouble())
        context.setScreen { DeterministicDirectJoinScreen(directJoinAddress) }
        context.waitForScreen(DeterministicDirectJoinScreen::class.java)
        context.input.setCursorPos(pointer.x.toDouble(), pointer.y.toDouble())
        context.runOnClient(
            FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                checkNotNull(MinecraftClientScreenAccess.currentScreen(minecraft)).setFocused(null)
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
            context.input.setCursorPos(pointer.x.toDouble(), pointer.y.toDouble())
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
        val result =
            runCatching {
                resizeMinecraftTestWindow(context, IntSize(containerViewport.width, containerViewport.height))
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

                val (containerBackground, slot) =
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
                            containerBackground to slot
                        }
                    }
                InventorySlotSynchronizationGameTest.run(context, profile, output)
                closeFabricScreen(context)
                val industrial = MinecraftIndustrialParity.run(context, profile, output)
                val playerHead = MinecraftPlayerHeadParity.run(context, profile, output)
                val social = MinecraftSocialParity.run(context, profile, output)
                val progress = MinecraftProgressParity.run(context, profile, output)
                val componentShowcases = MinecraftComponentShowcaseParity(context, profile, output).run()
                writeParityEvidence(
                    output,
                    confirm,
                    scroll,
                    directJoin,
                    containerBackground,
                    slot,
                    industrial,
                    playerHead,
                    social,
                    progress,
                    componentShowcases,
                )
            }
        runCanvasTestCleanup(result.exceptionOrNull(), { closeFabricScreen(context) }, world::close)
        result.getOrThrow()
    }

    @OptIn(InternalStrataRuntimeApi::class)
    private fun verifyContinuousInput(
        context: ClientGameTestContext,
        profile: MinecraftUiProfile,
        output: Path,
    ) {
        val probe = context.computeOnClient(FailableFunction<Minecraft, MinecraftContinuousInputProbe, RuntimeException> { MinecraftContinuousInputProbe() })
        val screen = context.computeOnClient(FailableFunction<Minecraft, FabricMinecraftScreen, RuntimeException> { createMinecraftScreen(probe.definition(), profile, parent = null) })
        val outcome =
            runCatching {
                context.runOnClient(FailableConsumer<Minecraft, RuntimeException> { MinecraftClientScreenAccess.setScreen(it, screen) })
                context.waitFor(
                    Predicate<Minecraft> { minecraft ->
                        MinecraftClientScreenAccess.currentScreen(minecraft) === screen && probe.isReady() &&
                            readRenderWork(minecraft).let { 0L < it.renderExtractions && it.inputs != null }
                    },
                )
                context.computeOnClient(
                    FailableFunction<Minecraft, String, RuntimeException> { minecraft ->
                        probe.verify(
                            frameCount = { readRenderWork(minecraft).renderExtractions },
                            scroll = { scrollMinecraftScreen(screen, probe.position) },
                            move = { screen.mouseMoved(probe.position.x.toDouble(), probe.position.y.toDouble()) },
                            click = {
                                val event = MouseButtonEvent(probe.position.x.toDouble(), probe.position.y.toDouble(), MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0))
                                check(screen.mouseClicked(event, false)) { "The Strata content must consume its native primary press." }
                                screen.mouseReleased(event)
                            },
                        )
                    },
                )
            }
        val cleanup = runCatching { context.runOnClient(FailableConsumer<Minecraft, RuntimeException> { screen.onClose() }) }
        val receipt =
            outcome.getOrElse { failure ->
                cleanup.exceptionOrNull()?.let { if (it !== failure) failure.addSuppressed(it) }
                throw failure
            }
        cleanup.getOrThrow()
        Files.writeString(output.resolve("continuous-input.txt"), "minecraftVersion=${minecraftVersion()}\n$receipt")
    }

    @OptIn(InternalStrataRuntimeApi::class)
    private fun verifyReactiveRendering(
        context: ClientGameTestContext,
        profile: MinecraftUiProfile,
        output: Path,
    ) {
        val receipt =
            ReactiveRenderScenario.verify(
                object : ReactiveRenderTestDriver {
                    override fun <T : Any> onClient(action: () -> T): T = context.computeOnClient(FailableFunction<Minecraft, T, RuntimeException> { action() })

                    override fun await(condition: () -> Boolean) {
                        context.waitFor(Predicate<Minecraft> { condition() })
                    }

                    override fun open(definition: ScreenDefinition): FabricMinecraftScreen {
                        val screen = createMinecraftScreen(definition, profile, parent = null)
                        MinecraftClientScreenAccess.setScreen(Minecraft.getInstance(), screen)
                        return screen
                    }

                    override fun work(): ReactiveNativeWork = readRenderWork(Minecraft.getInstance())

                    override fun assertPixels(
                        definition: ScreenDefinition,
                        capture: ReactiveRenderCapture,
                    ) {
                        val expected = onClient { ReactiveRenderPixels.reference(definition, profile, capture) }
                        val screenshot =
                            context.takeScreenshot(
                                TestScreenshotOptions.of("${capture.artifactName}-native").disableCounterPrefix().withDestinationDir(output),
                            )
                        ReactiveRenderPixels.verify(expected, screenshot, output, capture)
                    }

                    override fun closeScreen() {
                        activeFabricScreen(Minecraft.getInstance()).onClose()
                    }
                },
            )
        Files.writeString(output.resolve("reactive-rendering.txt"), "minecraftVersion=${minecraftVersion()}\n$receipt")
    }

    private fun verifyProfileCache(
        context: ClientGameTestContext,
        output: Path,
    ) {
        val probe = MinecraftProfileCacheProbe(MinecraftClientScreenAccess::currentScreen) { MinecraftClientScreenAccess.setScreen(it, null) }
        val outcome =
            runCatching {
                val reload =
                    context.computeOnClient(
                        FailableFunction<Minecraft, CompletableFuture<Void>, RuntimeException> { probe.begin(it) },
                    )
                context.waitFor(Predicate<Minecraft> { reload.isDone })
                reload.join()
                // The reload future completes before LoadingOverlay finishes its native fade-out.
                context.waitFor(Predicate<Minecraft> { MinecraftClientScreenAccess.hasOverlay(it).not() })
                context.runOnClient(FailableConsumer<Minecraft, RuntimeException> { probe.afterReload(it) })
                context.waitFor(Predicate<Minecraft> { probe.collected() })
                probe.writeReceipt(output)
            }
        val cleanup = runCatching { context.runOnClient(FailableConsumer<Minecraft, RuntimeException> { probe.close() }) }
        outcome.exceptionOrNull()?.let { failure ->
            cleanup.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        cleanup.getOrThrow()
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

    private fun minecraftVersion(): String {
        val configured = System.getProperty("strata.minecraftVersion")
        require(configured.isNullOrBlank().not()) { "The Minecraft version is not configured." }
        return configured
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
    }

    private enum class SampledParityScene(
        val slug: String,
        val definition: (IntSize) -> ScreenDefinition,
    ) {
        Ordered("strata-sampled-image", ::createSampledImageParityScreenDefinition),
        Effects("strata-sampled-image-effects", ::createSampledImageEffectsParityScreenDefinition),
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
