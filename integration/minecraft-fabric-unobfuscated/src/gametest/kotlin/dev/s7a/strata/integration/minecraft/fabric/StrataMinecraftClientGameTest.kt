package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.PlayerSkinSource
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.TextField
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.initialFocus
import dev.s7a.strata.modifier.onCharacterInput
import dev.s7a.strata.modifier.onPreedit
import dev.s7a.strata.modifier.onTextInput
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.extractMinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.fabric.loadCurrentMinecraftPlayerSkin
import dev.s7a.strata.runtime.minecraft.fabric.loadMinecraftUiImage
import dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory
import dev.s7a.strata.runtime.render.DrawCommand
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
import net.minecraft.client.gui.components.PlayerFaceExtractor
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.client.gui.screens.DirectJoinServerScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo
import net.minecraft.client.input.PreeditEvent
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.locale.Language
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
import java.util.concurrent.CompletableFuture
import java.util.function.Predicate
import kotlin.io.path.inputStream

/**
 * Proves exact native, Fabric-adapter, and headless pixels for the supported Minecraft release.
 *
 * The test runs on Fabric's client GameTest thread and performs all Minecraft and common-host operations on the client thread through [ClientGameTestContext].
 * It reads the active assets, font, and selected player skin, uses native [Button] and [PlayerFaceExtractor] rendering as the oracle, and writes only deterministic evidence below the configured build directory.
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
        context.input.resizeWindow(viewport.width, viewport.height)
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
        verifyProfileCache(context, output)
        assertNativeTextInputFocus(context, profile)
        verifyContinuousInput(context, profile, output)
        runMinecraftCanvasTest(context, profile, output)
        runSampledImagePixelParity(context, profile, output)

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

                context.runOnClient(
                    FailableConsumer<Minecraft, RuntimeException> {
                        createConfirmScreenDefinition().open()
                    },
                )
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
    }

    @OptIn(InternalStrataRuntimeApi::class)
    private fun runSampledImagePixelParity(
        context: ClientGameTestContext,
        profile: MinecraftUiProfile,
        output: Path,
    ) {
        closeFabricScreen(context)
        val headless =
            context.computeOnClient(
                FailableFunction<Minecraft, HeadlessImage, RuntimeException> {
                    renderHeadless(profile, createSampledImageParityScreenDefinition(viewport), viewport)
                },
            )
        Files.write(output.resolve("strata-sampled-image-headless.png"), headless.encodePng())

        val presentation =
            runCatching {
                context.setScreen {
                    createMinecraftScreen(
                        createSampledImageParityScreenDefinition(viewport),
                        profile,
                        parent = null,
                    )
                }
                context.waitForScreen(FabricMinecraftScreen::class.java)
                context.waitFor(
                    Predicate<Minecraft> { minecraft ->
                        0L < readRenderWork(minecraft).sampledImageDirectHits
                    },
                )
                val observed = renderWork(context)
                requireSampledImageParityWork(observed)
                val fabricPath =
                    context.takeScreenshot(
                        TestScreenshotOptions
                            .of("strata-sampled-image-fabric")
                            .disableCounterPrefix()
                            .withSize(viewport.width, viewport.height)
                            .withDestinationDir(output),
                    )
                NativeImage.read(fabricPath.inputStream()).use { fabric ->
                    requireImageSize(fabric, viewport)
                    requireExactPixels(fabric, headless)
                }
            }
        val cleanup = runCatching { closeFabricScreen(context) }.exceptionOrNull()
        val primary = presentation.exceptionOrNull()
        if (primary != null) {
            if (cleanup != null && primary !== cleanup) primary.addSuppressed(cleanup)
            throw primary
        }
        if (cleanup != null) throw cleanup
    }

    @OptIn(InternalStrataRuntimeApi::class)
    @Suppress("LongMethod")
    private fun assertNativeTextInputFocus(
        context: ClientGameTestContext,
        profile: MinecraftUiProfile,
    ) {
        lateinit var firstState: TextFieldState
        lateinit var secondState: TextFieldState
        lateinit var screen: FabricMinecraftScreen
        lateinit var nativeParent: DeterministicDirectJoinScreen
        var firstPreeditCalls = 0
        var secondPreeditCalls = 0
        context.setScreen {
            firstState = TextFieldState("A")
            secondState = TextFieldState("B")
            nativeParent = DeterministicDirectJoinScreen()
            val definition =
                ScreenDefinition("native input focus") {
                    Column {
                        TextField(
                            firstState,
                            modifier =
                                Modifier.Empty
                                    .initialFocus()
                                    .onPreedit {
                                        firstPreeditCalls += 1
                                        InputResult.Ignored
                                    }.onCharacterInput { event ->
                                        if (event.codePoint == 'X'.code) {
                                            MinecraftClientScreenAccess.setScreen(Minecraft.getInstance(), nativeParent)
                                            InputResult.Consumed
                                        } else {
                                            InputResult.Ignored
                                        }
                                    },
                        )
                        TextField(
                            secondState,
                            modifier =
                                Modifier.Empty.onPreedit {
                                    secondPreeditCalls += 1
                                    InputResult.Ignored
                                },
                        )
                        Spacer(modifier = Modifier.Empty.size(200, 20).onTextInput { InputResult.Ignored })
                    }
                }
            screen = createMinecraftScreen(definition, profile, parent = null)
            screen
        }
        context.waitForScreen(FabricMinecraftScreen::class.java)
        context.waitFor(
            Predicate<Minecraft> { minecraft ->
                MinecraftClientScreenAccess.currentScreen(minecraft) === screen &&
                    0L < readRenderWork(minecraft).framePreparations && nativeTextInputEnabled(minecraft)
            },
        )
        context.waitTicks(2)
        context.runOnClient(
            FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                check(nativeTextInputEnabled(minecraft)) { "Initial editable focus did not enable native text input." }
                check(firstPreeditCalls == 1) { "Unchanged frames must not resubmit native preedit." }
                val preedit = PreeditEvent("日🙂", 3, listOf("日", "🙂"), 1)
                check(screen.preeditUpdated(preedit))
                check(firstState.value == "A") { "Preedit unexpectedly committed text." }
                screen.mouseClicked(MouseButtonEvent(4.0, 24.0, MouseButtonInfo(0, 0)), false)
                check(nativeTextInputEnabled(minecraft))
                check(secondPreeditCalls == 1) { "Switching editable owners did not resubmit native preedit." }
                check(screen.charTyped(CharacterEvent('한'.code)))
                check(firstState.value == "A" && secondState.value == "한B")
                screen.mouseClicked(MouseButtonEvent(4.0, 44.0, MouseButtonInfo(0, 0)), false)
                check(nativeTextInputEnabled(minecraft).not()) { "A passive input observer enabled native text input." }
                check(screen.preeditUpdated(preedit).not())
                screen.mouseClicked(MouseButtonEvent(4.0, 4.0, MouseButtonInfo(0, 0)), false)
                check(nativeTextInputEnabled(minecraft))
                MinecraftClientScreenAccess.setScreen(minecraft, null)
                check(nativeTextInputEnabled(minecraft).not()) { "Detaching a screen retained native text-input focus." }
                check(screen.preeditUpdated(preedit).not())
                MinecraftClientScreenAccess.setScreen(minecraft, screen)
                check(nativeTextInputEnabled(minecraft).not()) { "Reattachment enabled text input before a committed frame." }
            },
        )
        context.waitTicks(2)
        context.runOnClient(
            FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                check(nativeTextInputEnabled(minecraft))
                check(firstState.value == "A" && secondState.value == "한B")
                check(screen.charTyped(CharacterEvent('X'.code)))
                check(MinecraftClientScreenAccess.currentScreen(minecraft) === nativeParent)
                check(nativeTextInputEnabled(minecraft)) { "Deferred Strata removal disabled the next native editor." }
                screen.close()
                check(nativeTextInputEnabled(minecraft)) { "Closing a detached screen disabled the active native editor." }
                nativeParent.setFocused(null)
                MinecraftClientScreenAccess.setScreen(minecraft, null)
                check(nativeTextInputEnabled(minecraft).not())
            },
        )
    }

    private fun nativeTextInputEnabled(minecraft: Minecraft): Boolean {
        val manager = minecraft.textInputManager()
        val enabled = manager.javaClass.getDeclaredField("textInputEnabled")
        check(enabled.trySetAccessible()) { "Native text-input state is inaccessible to the loaded test." }
        return enabled.getBoolean(manager)
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
                        ResourceId(
                            "strata_test",
                            "textures/gui/coal_generator.png",
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
                            renderHeadless(
                                profile,
                                createPlayerHeadScreenDefinition(PlayerSkinSource.Pixels(skin)),
                                playerHeadViewport,
                            )
                        },
                    )
                Files.write(output.resolve("strata-player-head-headless.png"), rendered.encodePng())
                requireExactPixels(native, rendered)

                context.setScreen {
                    createMinecraftScreen(
                        createPlayerHeadScreenDefinition(PlayerSkinSource.Pixels(skin)),
                        profile,
                        parent = null,
                    )
                }
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
                val current = MinecraftClientScreenAccess.currentScreen(minecraft)
                if (current is FabricMinecraftScreen) {
                    MinecraftClientScreenAccess.setScreen(minecraft, null)
                    assertFabricPresentationReleased(current)
                    current.onClose()
                }
            },
        )
        context.waitFor(Predicate<Minecraft> { minecraft -> (MinecraftClientScreenAccess.currentScreen(minecraft) is FabricMinecraftScreen).not() })
    }

    private fun assertFabricPresentationReleased(screen: FabricMinecraftScreen) {
        val fields = FabricMinecraftScreen::class.java.declaredFields.associateBy { field -> field.name }

        fun retained(name: String): Any? {
            val field = fields[name] ?: error("Fabric presentation field is missing: $name")
            check(field.trySetAccessible()) { "Fabric presentation field is inaccessible: $name" }
            return field.get(screen)
        }

        val portableFrames = checkNotNull(retained("portableFrames"))
        val portableCurrent = portableFrames.javaClass.getDeclaredField("current")
        check(portableCurrent.trySetAccessible()) { "The portable presentation cache is inaccessible." }
        require(portableCurrent.get(portableFrames) == null) { "A detached Fabric screen retained portable drawing or texture ownership." }
        require(retained("preparedCommands") == null) { "A detached Fabric screen retained its display list." }
        require(retained("preparedViewport") == null) { "A detached Fabric screen retained its prepared viewport." }
        require((retained("preparedLayers") as List<*>).isEmpty()) { "A detached Fabric screen retained prepared layers." }
        require(retained("pointerPosition") == null) { "A detached Fabric screen retained its native pointer position." }
        require(retained("pointerFrameCommands") == null) { "A detached Fabric screen retained pointer display-list ownership." }
    }

    private fun assertCleanFabricFrameReuse(context: ClientGameTestContext) {
        val beforeBatch =
            context.computeOnClient(
                FailableFunction<Minecraft, RenderWork, RuntimeException> { minecraft ->
                    val before = readRenderWork(minecraft)
                    val screen = activeFabricScreen(minecraft)
                    listOf("pointerPosition", "pointerFrameCommands").forEach { name ->
                        val field = FabricMinecraftScreen::class.java.getDeclaredField(name)
                        check(field.trySetAccessible()) { "Fabric pointer cache field is inaccessible: $name" }
                        field.set(screen, null)
                    }
                    before
                },
            )
        context.waitFor(
            Predicate<Minecraft> { minecraft ->
                val current = readRenderWork(minecraft)
                beforeBatch.pointerDispatches < current.pointerDispatches && beforeBatch.renderExtractions < current.renderExtractions
            },
        )
        val afterBatch = renderWork(context)
        val batchExtractions = afterBatch.renderExtractions - beforeBatch.renderExtractions
        val batchPointerDispatches = afterBatch.pointerDispatches - beforeBatch.pointerDispatches
        require(afterBatch.hostFrames - beforeBatch.hostFrames == batchExtractions + batchPointerDispatches) {
            "Each extraction must produce one host frame plus one after each pointer dispatch: before=$beforeBatch, after=$afterBatch"
        }
        require(afterBatch.refreshPasses - beforeBatch.refreshPasses == batchExtractions) {
            "Repeated host frames in one extraction must share one platform refresh: before=$beforeBatch, after=$afterBatch"
        }

        var previous = afterBatch
        context.waitFor(
            Predicate<Minecraft> { minecraft ->
                val current = readRenderWork(minecraft)
                val settled = previous.renderExtractions < current.renderExtractions && previous.pointerDispatches == current.pointerDispatches
                previous = current
                settled
            },
        )
        val before = renderWork(context)
        context.waitFor(Predicate<Minecraft> { minecraft -> before.renderExtractions < readRenderWork(minecraft).renderExtractions })
        val after = renderWork(context)
        val extractionDelta = after.renderExtractions - before.renderExtractions
        require(0L < extractionDelta) { "The clean-frame verification observed no native render extraction." }
        require(after.hostFrames - before.hostFrames == extractionDelta) {
            "A clean native render extraction must produce exactly one common host frame: before=$before, after=$after"
        }
        require(after.refreshPasses - before.refreshPasses == extractionDelta) {
            "A clean native render extraction must perform exactly one platform refresh: before=$before, after=$after"
        }
        require(after.pointerDispatches == before.pointerDispatches) {
            "A stationary pointer must not be redispatched while the display list is unchanged: before=$before, after=$after"
        }
        require(after.framePreparations == before.framePreparations) {
            "An unchanged display list must not be repartitioned: before=$before, after=$after"
        }
        require(after.rasterizations == before.rasterizations) {
            "An unchanged portable layer must not be rasterized again: before=$before, after=$after"
        }
        require(after.textureUploads == before.textureUploads) {
            "An unchanged portable layer must not be uploaded again: before=$before, after=$after"
        }

        val beforeEquivalentLayers =
            context.computeOnClient(
                FailableFunction<Minecraft, RenderWork, RuntimeException> { minecraft ->
                    val screen = activeFabricScreen(minecraft)
                    val commandsField = FabricMinecraftScreen::class.java.getDeclaredField("preparedCommands")
                    check(commandsField.trySetAccessible()) { "The Fabric prepared command cache is inaccessible." }
                    val commands = commandsField.get(screen) as? List<*> ?: error("The Fabric screen has no prepared command list.")
                    commandsField.set(screen, ArrayList(commands))
                    readRenderWork(minecraft)
                },
            )
        context.waitFor(
            Predicate<Minecraft> { minecraft ->
                beforeEquivalentLayers.renderExtractions < readRenderWork(minecraft).renderExtractions
            },
        )
        val afterEquivalentLayers = renderWork(context)
        require(afterEquivalentLayers.framePreparations == beforeEquivalentLayers.framePreparations + 1L) {
            "A new but equivalent display-list snapshot must be repartitioned exactly once: before=$beforeEquivalentLayers, after=$afterEquivalentLayers"
        }
        require(afterEquivalentLayers.rasterizations == beforeEquivalentLayers.rasterizations) {
            "Equivalent portable layers must reuse their raster textures: before=$beforeEquivalentLayers, after=$afterEquivalentLayers"
        }
        require(afterEquivalentLayers.textureUploads == beforeEquivalentLayers.textureUploads) {
            "Equivalent portable layers must not be uploaded again: before=$beforeEquivalentLayers, after=$afterEquivalentLayers"
        }
    }

    private fun requireSampledImageParityWork(observed: RenderWork) {
        require(0L < observed.rasterizations) { "Sampled-image parity must rasterize portable layers: $observed" }
        require(0L < observed.textureUploads) { "Sampled-image parity must upload portable layers: $observed" }
        require(0L < observed.sampledImageDirectHits) { "Sampled-image parity must reuse a direct texture: $observed" }
        require(0L < observed.sampledImageDirectMisses) { "Sampled-image parity must create a direct texture: $observed" }
        require(0L < observed.sampledImageUploads) { "Sampled-image parity must upload a direct texture: $observed" }
        require(0L < observed.sampledImageDraws) { "Sampled-image parity must execute the native direct path: $observed" }
        require(observed.sampledImageEvictions == 0L) { "Sampled-image parity must not evict its direct texture: $observed" }
        require(observed.sampledImageIneligibleFallbacks == 0L) { "Sampled-image parity must remain eligible for direct drawing: $observed" }
        require(observed.sampledImageCapacityFallbacks == 0L) { "Sampled-image parity must fit the direct cache: $observed" }
        require(0L < observed.sampledImageRetainedEntries) { "Sampled-image parity must retain its direct texture: $observed" }
        require(0L < observed.sampledImageRetainedBytes) { "Sampled-image parity must retain native texture storage: $observed" }
    }

    private fun renderWork(context: ClientGameTestContext): RenderWork =
        context.computeOnClient(
            FailableFunction<Minecraft, RenderWork, RuntimeException> { minecraft -> readRenderWork(minecraft) },
        )

    private fun readRenderWork(minecraft: Minecraft): RenderWork {
        val screen = activeFabricScreen(minecraft)
        val fields = FabricMinecraftScreen::class.java.declaredFields.associateBy { field -> field.name }

        fun counter(name: String): Long {
            val field = fields[name] ?: error("Fabric performance counter is missing: $name")
            check(field.trySetAccessible()) { "Fabric performance counter is inaccessible: $name" }
            return field.getLong(screen)
        }

        val inventoryField = fields["inventory"] ?: error("The Fabric performance screen has no inventory bridge.")
        check(inventoryField.trySetAccessible()) { "The Fabric inventory bridge is inaccessible." }
        val inventory = inventoryField.get(screen)
        val refreshField = inventory.javaClass.getDeclaredField("refreshPassCount")
        check(refreshField.trySetAccessible()) { "The Fabric refresh counter is inaccessible." }

        return RenderWork(
            renderExtractions = counter("renderExtractionCount"),
            hostFrames = counter("hostFrameCount"),
            pointerDispatches = counter("extractedPointerDispatchCount"),
            refreshPasses = refreshField.getLong(inventory),
            framePreparations = counter("framePreparationCount"),
            rasterizations = counter("portableRasterizationCount"),
            textureUploads = counter("textureUploadCount"),
            sampledImageDirectHits = counter("sampledImageDirectHitCount"),
            sampledImageDirectMisses = counter("sampledImageDirectMissCount"),
            sampledImageUploads = counter("sampledImageUploadCount"),
            sampledImageDraws = counter("sampledImageDrawCount"),
            sampledImageEvictions = counter("sampledImageEvictionCount"),
            sampledImageIneligibleFallbacks = counter("sampledImageIneligibleFallbackCount"),
            sampledImageCapacityFallbacks = counter("sampledImageCapacityFallbackCount"),
            sampledImageRetainedEntries = counter("sampledImageRetainedEntryCount"),
            sampledImageRetainedBytes = counter("sampledImageRetainedByteCount"),
        )
    }

    private fun activeFabricScreen(minecraft: Minecraft): FabricMinecraftScreen {
        val activeScreen = MinecraftClientScreenAccess.currentScreen(minecraft)
        return activeScreen as? FabricMinecraftScreen ?: error("The Fabric performance screen is not active.")
    }

    @OptIn(InternalStrataRuntimeApi::class)
    private fun renderHeadless(
        profile: MinecraftUiProfile,
        definition: ScreenDefinition,
        viewport: IntSize,
        pointerPosition: IntOffset = pointer,
        frameTime: FrameTime? = null,
        scale: Int = 1,
    ): HeadlessImage {
        val host = createMinecraftUiHost(definition, profile, LwjglMinecraftFontBackendFactory)
        host.attach()
        return try {
            if (frameTime == null) host.frame(viewport) else host.frame(viewport, FrameTime(0L))
            host.dispatchPointer(PointerEvent.Move(pointerPosition))
            val frame = if (frameTime == null) host.frame(viewport) else host.frame(viewport, frameTime)
            val framebufferClear =
                DrawCommand.FillRectangle(
                    IntRect(0, 0, frame.size.width, frame.size.height),
                    ArgbColor(opaqueBlack),
                )
            rasterizeHeadless(listOf(framebufferClear) + frame.drawCommands, frame.size, scale)
        } finally {
            host.close()
        }
    }

    private fun hasExactPixels(
        native: NativeImage,
        headless: HeadlessImage,
    ): Boolean {
        val expected = IntSize(native.getWidth(), native.getHeight())
        if (headless.size != expected) return false
        for (y in 0 until expected.height) {
            for (x in 0 until expected.width) {
                if (headless.argbAt(x, y) != native.getPixel(x, y)) return false
            }
        }
        return true
    }

    private fun requireImageSize(
        image: NativeImage,
        expected: IntSize,
    ) {
        if (image.getWidth() == expected.width && image.getHeight() == expected.height) return
        throw AssertionError("Native screenshot size was ${image.getWidth()}x${image.getHeight()}, expected ${expected.width}x${expected.height}.")
    }

    private data class RenderWork(
        val renderExtractions: Long,
        val hostFrames: Long,
        val pointerDispatches: Long,
        val refreshPasses: Long,
        val framePreparations: Long,
        val rasterizations: Long,
        val textureUploads: Long,
        val sampledImageDirectHits: Long,
        val sampledImageDirectMisses: Long,
        val sampledImageUploads: Long,
        val sampledImageDraws: Long,
        val sampledImageEvictions: Long,
        val sampledImageIneligibleFallbacks: Long,
        val sampledImageCapacityFallbacks: Long,
        val sampledImageRetainedEntries: Long,
        val sampledImageRetainedBytes: Long,
    )

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

    @OptIn(InternalStrataRuntimeApi::class)
    @Suppress("LongMethod")
    private fun runComponentShowcaseParity(
        context: ClientGameTestContext,
        profile: MinecraftUiProfile,
        output: Path,
    ): Map<ComponentShowcase, ComponentShowcaseFrame> {
        val assets =
            context.computeOnClient(
                FailableFunction<Minecraft, ComponentShowcaseAssets, RuntimeException> {
                    ComponentShowcaseAssets(
                        image =
                            ImageSource.Pixels(
                                loadMinecraftUiImage(
                                    ResourceId(
                                        "strata_test",
                                        "textures/gui/coal_generator.png",
                                    ),
                                ),
                            ),
                        playerSkin =
                            PlayerSkinSource.Pixels(
                                loadMinecraftUiImage(ResourceId("minecraft", "textures/entity/player/slim/efe.png")),
                            ),
                    )
                },
            )
        val imageDirectory = output.resolve("components")
        Files.createDirectories(imageDirectory)
        val frames = LinkedHashMap<ComponentShowcase, ComponentShowcaseFrame>()
        for (showcase in ComponentShowcase.entries) {
            closeFabricScreen(context)
            val physicalSize = showcase.physicalSize
            context.input.resizeWindow(physicalSize.width, physicalSize.height)
            context.runOnClient(
                FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                    minecraft.options.guiScale().set(showcase.scale)
                    minecraft.resizeGui()
                    // Minimal component viewports are smaller than the options menu's 320 by 240 scale-selection floor.
                    minecraft.window.setGuiScale(showcase.scale)
                    check(minecraft.window.guiScaledWidth == showcase.viewport.width && minecraft.window.guiScaledHeight == showcase.viewport.height)
                },
            )
            context.input.setCursorPos(showcase.pointer.x.toDouble() * showcase.scale, showcase.pointer.y.toDouble() * showcase.scale)
            val headless =
                context.computeOnClient(
                    FailableFunction<Minecraft, HeadlessImage, RuntimeException> {
                        renderHeadless(
                            profile,
                            createComponentShowcaseScreenDefinition(showcase, assets),
                            showcase.viewport,
                            showcase.pointer,
                            frameTime = FrameTime(0L),
                            scale = showcase.scale,
                        )
                    },
                )
            if (showcase === ComponentShowcase.Canvas) requireCanvasKnownPixels(headless)
            val png = headless.encodePng()
            val imagePath = imageDirectory.resolve("${showcase.slug}.png")
            Files.write(imagePath, png)

            context.setScreen {
                createMinecraftScreen(
                    createComponentShowcaseScreenDefinition(showcase, assets),
                    profile,
                    parent = null,
                )
            }
            context.waitForScreen(FabricMinecraftScreen::class.java)
            context.input.setCursorPos(showcase.pointer.x.toDouble() * showcase.scale, showcase.pointer.y.toDouble() * showcase.scale)
            context.waitTicks(2)
            context.runOnClient(
                FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                    check(minecraft.window.guiScale == showcase.scale) { "The component capture lost its actual GUI density." }
                    check(minecraft.window.guiScaledWidth == showcase.viewport.width && minecraft.window.guiScaledHeight == showcase.viewport.height)
                },
            )
            val fabricPath =
                context.takeScreenshot(
                    TestScreenshotOptions
                        .of("strata-component-${showcase.slug}-fabric")
                        .disableCounterPrefix()
                        .withSize(physicalSize.width, physicalSize.height)
                        .withDestinationDir(output),
                )
            NativeImage.read(fabricPath.inputStream()).use { fabric ->
                requireImageSize(fabric, physicalSize)
                if (showcase.animationFrameTimes.isEmpty()) {
                    requireExactPixels(fabric, headless)
                } else {
                    val matched =
                        showcase.animationFrameTimes.any { frameTime ->
                            val candidate =
                                context.computeOnClient(
                                    FailableFunction<Minecraft, HeadlessImage, RuntimeException> {
                                        renderHeadless(
                                            profile,
                                            createComponentShowcaseScreenDefinition(showcase, assets),
                                            showcase.viewport,
                                            showcase.pointer,
                                            frameTime,
                                            showcase.scale,
                                        )
                                    },
                                )
                            hasExactPixels(fabric, candidate)
                        }
                    if (matched.not()) {
                        throw AssertionError(
                            "Fabric ${showcase.slug} pixels did not match any complete headless animation frame.",
                        )
                    }
                }
            }
            frames[showcase] = ComponentShowcaseFrame(headless, png)
        }
        closeFabricScreen(context)
        return frames
    }

    private fun requireCanvasKnownPixels(image: HeadlessImage) {
        val expected =
            intArrayOf(
                0xFF4CC9F0.toInt(),
                0xFF4361EE.toInt(),
                0xFF7209B7.toInt(),
                0xFFF72585.toInt(),
                0xFF90BE6D.toInt(),
                0xFFF9C74F.toInt(),
                0xFFF8961E.toInt(),
                0xFF7D2122.toInt(),
            )
        require(image.argbAt(0, 0) == 0xFF000000.toInt()) { "The Canvas showcase background differs from opaque black." }
        expected.indices.forEach { index ->
            val x = 24 + (index % 4) * 16
            val y = 24 + (index / 4) * 16
            if (image.argbAt(x, y) != expected[index]) {
                throw AssertionError("Canvas source texel $index differs from its independent expected straight-alpha color.")
            }
        }
    }

    private fun createComponentShowcaseScreenDefinition(
        showcase: ComponentShowcase,
        assets: ComponentShowcaseAssets,
    ): ScreenDefinition =
        when (showcase) {
            ComponentShowcase.Row -> createRowShowcaseScreenDefinition()
            ComponentShowcase.FlowRow -> createFlowRowShowcaseScreenDefinition()
            ComponentShowcase.Column -> createColumnShowcaseScreenDefinition()
            ComponentShowcase.Stack -> createStackShowcaseScreenDefinition()
            ComponentShowcase.Grid -> createGridShowcaseScreenDefinition()
            ComponentShowcase.Spacer -> createSpacerShowcaseScreenDefinition()
            ComponentShowcase.Text -> createTextShowcaseScreenDefinition()
            ComponentShowcase.TextField -> createTextFieldShowcaseScreenDefinition()
            ComponentShowcase.TextArea -> createTextAreaShowcaseScreenDefinition()
            ComponentShowcase.Button -> createButtonShowcaseScreenDefinition()
            ComponentShowcase.Checkbox -> createCheckboxShowcaseScreenDefinition()
            ComponentShowcase.CycleButton -> createCycleButtonShowcaseScreenDefinition()
            ComponentShowcase.Slider -> createSliderShowcaseScreenDefinition()
            ComponentShowcase.Tab -> createTabShowcaseScreenDefinition()
            ComponentShowcase.ScrollArea -> createScrollAreaShowcaseScreenDefinition()
            ComponentShowcase.Scrollbar -> createScrollbarShowcaseScreenDefinition()
            ComponentShowcase.VirtualList -> createVirtualListShowcaseScreenDefinition()
            ComponentShowcase.SelectionList -> createSelectionListShowcaseScreenDefinition()
            ComponentShowcase.Image -> createImageShowcaseScreenDefinition(assets.image)
            ComponentShowcase.Canvas -> createCanvasShowcaseScreenDefinition()
            ComponentShowcase.Slot -> createSlotShowcaseScreenDefinition()
            ComponentShowcase.PlayerHead -> createPlayerHeadShowcaseScreenDefinition(assets.playerSkin)
            ComponentShowcase.LoadingIndicator -> createLoadingIndicatorShowcaseScreenDefinition()
            ComponentShowcase.ProgressBar -> createProgressBarShowcaseScreenDefinition()
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
                    val componentShowcases = runComponentShowcaseParity(context, profile, output)
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
            }
            closeFabricScreen(context)
        } finally {
            world.close()
        }
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
                        MinecraftClientScreenAccess.currentScreen(minecraft) === screen && probe.isReady() && 0L < readRenderWork(minecraft).framePreparations
                    },
                )
                context.computeOnClient(
                    FailableFunction<Minecraft, String, RuntimeException> { minecraft ->
                        probe.verify(
                            frameCount = { readRenderWork(minecraft).hostFrames },
                            scroll = { scrollMinecraftScreen(screen, probe.position) },
                            move = { screen.mouseMoved(probe.position.x.toDouble(), probe.position.y.toDouble()) },
                            click = {
                                val event = MouseButtonEvent(probe.position.x.toDouble(), probe.position.y.toDouble(), MouseButtonInfo(0, 0))
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
        private val industrialAssetSize = IntSize(1229, 1280)
        private val playerHeadViewport = IntSize(64, 64)
    }

    private data class ComponentShowcaseAssets(
        val image: ImageSource,
        val playerSkin: PlayerSkinSource,
    )

    private class ComponentShowcaseFrame(
        val image: HeadlessImage,
        val png: ByteArray,
    )

    private enum class ComponentShowcase(
        val slug: String,
        val viewport: IntSize,
        val pointer: IntOffset = IntOffset.Zero,
        val animationFrameTimes: List<FrameTime> = emptyList(),
        val scale: Int = 1,
    ) {
        Row("row", IntSize(136, 64)),
        FlowRow("flow-row", IntSize(168, 60)),
        Column("column", IntSize(120, 64)),
        Stack("stack", IntSize(64, 64)),
        Grid("grid", IntSize(64, 64)),
        Spacer("spacer", IntSize(160, 64)),
        Text("text", IntSize(192, 88), scale = 2),
        TextField("text-field", IntSize(216, 64), scale = 2),
        TextArea("text-area", IntSize(226, 80), scale = 2),
        Button("button", IntSize(166, 64)),
        Checkbox("checkbox", IntSize(166, 36)),
        CycleButton("cycle-button", IntSize(166, 36)),
        Slider("slider", IntSize(166, 36)),
        Tab("tab", IntSize(160, 64)),
        ScrollArea("scroll-area", IntSize(120, 48)),
        Scrollbar("scrollbar", IntSize(94, 48)),
        VirtualList("virtual-list", IntSize(120, 48)),
        SelectionList("selection-list", IntSize(120, 48)),
        Image("image", IntSize(64, 64)),
        Canvas("canvas", IntSize(96, 64)),
        Slot("slot", IntSize(64, 64), IntOffset(32, 32)),
        PlayerHead("player-head", IntSize(64, 64)),
        LoadingIndicator(
            "loading-indicator",
            IntSize(32, 24),
            animationFrameTimes =
                listOf(
                    FrameTime(0L),
                    FrameTime(300_000_000L),
                    FrameTime(600_000_000L),
                ),
        ),
        ProgressBar("progress-bar", IntSize(116, 28)),
        ;

        val physicalSize: IntSize = IntSize(Math.multiplyExact(viewport.width, scale), Math.multiplyExact(viewport.height, scale))
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
