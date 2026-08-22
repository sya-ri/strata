package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Slot
import dev.s7a.strata.component.Slots
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.menuBackground
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.minecraft.MinecraftUiHost
import dev.s7a.strata.runtime.minecraft.MinecraftUiPlatform
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.extractMinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.fabric.loadMinecraftUiImage
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.apache.commons.lang3.function.FailableConsumer
import org.apache.commons.lang3.function.FailableFunction
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Predicate
import kotlin.io.path.inputStream

/**
 * Proves the public Strata screen, asset, input, and inventory contracts in a loaded Minecraft 1.21.11 client.
 *
 * The test owns every screen and integrated-world resource that it creates, performs client work through [ClientGameTestContext], and rejects retained presentation data after each screen is detached.
 */
@OptIn(InternalStrataRuntimeApi::class)
@Suppress("TooManyFunctions")
public class StrataMinecraft12111ClientGameTest : FabricClientGameTest {
    /**
     * Opens and renders portable and inventory-bound public API scenes against the actual 1.21.11 client.
     *
     * @param context Fabric client GameTest context owning the client thread, window, screenshots, and integrated world.
     * @throws AssertionError when the loaded version, asset profile, rendering, input, inventory synchronization, frame reuse, or cleanup contract fails.
     * @throws Throwable when Minecraft or Fabric cannot create, render, or close a required test resource.
     */
    override fun runTest(context: ClientGameTestContext) {
        context.restoreDefaultGameOptions()
        configureViewport(context)
        require(minecraftVersion() == loadedMinecraftVersion()) {
            "The loaded Minecraft release does not match the configured integration target."
        }

        val profile =
            context.computeOnClient(
                FailableFunction<Minecraft, MinecraftUiProfile, RuntimeException> { extractMinecraftUiProfile() },
            )
        verifyVersionAsset(context)
        val output = outputDirectory()
        Files.createDirectories(output)
        verifyPortableScene(context, profile, output)
        verifyPlayerInventoryBinding(context, profile, output)
        context.restoreDefaultGameOptions()
    }

    private fun configureViewport(context: ClientGameTestContext) {
        context.input.resizeWindow(viewport.width, viewport.height)
        context.runOnClient(
            FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                minecraft.options.guiScale().set(1)
                minecraft.options.forceUnicodeFont().set(false)
                minecraft.resizeDisplay()
            },
        )
    }

    private fun verifyVersionAsset(context: ClientGameTestContext) {
        val button =
            context.computeOnClient(
                FailableFunction<Minecraft, DrawImage, RuntimeException> {
                    loadMinecraftUiImage(ResourceId("minecraft", "textures/gui/sprites/widget/button.png"))
                },
            )
        require(button.size == buttonTextureSize) {
            "Minecraft 1.21.11's normal Button sprite must be the verified 200 by 20 pixel asset."
        }
    }

    private fun verifyPortableScene(
        context: ClientGameTestContext,
        profile: MinecraftUiProfile,
        output: Path,
    ) {
        val pressed = AtomicBoolean()
        context.input.setCursorPos(portableButtonCenter.x.toDouble(), portableButtonCenter.y.toDouble())
        context.setScreen { createMinecraftScreen(portableDefinition(pressed), profile, parent = null) }
        context.waitForScreen(FabricMinecraftScreen::class.java)
        context.waitTicks(2)

        val screenshot =
            context.takeScreenshot(
                TestScreenshotOptions
                    .of("strata-public-api-1.21.11")
                    .disableCounterPrefix()
                    .withSize(viewport.width, viewport.height)
                    .withDestinationDir(output),
            )
        assertRenderedPixels(screenshot)
        assertCleanFrameReuse(context)
        clickCurrentScreen(context, portableButtonCenter)
        context.waitFor(Predicate<Minecraft> { pressed.get() })
        closeAndAssertReleased(context)
    }

    private fun verifyPlayerInventoryBinding(
        context: ClientGameTestContext,
        profile: MinecraftUiProfile,
        output: Path,
    ) {
        val world = context.worldBuilder().setUseConsistentSettings(true).create()
        try {
            world.clientWorld.waitForChunksRender()
            val server =
                context.computeOnClient(
                    FailableFunction<Minecraft, MinecraftServer, RuntimeException> { minecraft ->
                        checkNotNull(minecraft.singleplayerServer)
                    },
                )
            val playerId =
                context.computeOnClient(
                    FailableFunction<Minecraft, UUID, RuntimeException> { minecraft ->
                        checkNotNull(minecraft.player).uuid
                    },
                )
            world.server.runOnServer<RuntimeException> { server ->
                val player = server.playerList.players.single()
                player.inventory.setItem(playerInventoryIndex, ItemStack(Items.DIRT, itemCount))
                player.inventoryMenu.broadcastChanges()
            }
            context.waitFor(
                Predicate<Minecraft> { minecraft ->
                    val stack = minecraft.player?.inventory?.getItem(playerInventoryIndex)
                    stack != null && stack.`is`(Items.DIRT) && stack.count == itemCount
                },
            )

            openPlayerInventoryScreen(context, profile, output)
            verifyPlayerInventoryRoundTrip(context, server, playerId)
            closeAndAssertReleased(context)
        } finally {
            world.close()
        }
    }

    private fun openPlayerInventoryScreen(
        context: ClientGameTestContext,
        profile: MinecraftUiProfile,
        output: Path,
    ) {
        context.input.setCursorPos(slotCenter.x.toDouble(), slotCenter.y.toDouble())
        context.setScreen { createMinecraftScreen(inventoryDefinition(), profile, parent = null) }
        context.waitForScreen(FabricMinecraftScreen::class.java)
        context.waitTicks(2)
        context.takeScreenshot(
            TestScreenshotOptions
                .of("strata-player-inventory-binding-1.21.11")
                .disableCounterPrefix()
                .withSize(viewport.width, viewport.height)
                .withDestinationDir(output),
        )
        assertPortableTextureBounds(context)
    }

    private fun verifyPlayerInventoryRoundTrip(
        context: ClientGameTestContext,
        server: MinecraftServer,
        playerId: UUID,
    ) {
        clickCurrentScreen(context, slotCenter)
        context.waitFor(
            Predicate<Minecraft> { minecraft ->
                val player = minecraft.player ?: return@Predicate false
                player.inventory.getItem(playerInventoryIndex).isEmpty &&
                    player.containerMenu.carried.`is`(Items.DIRT) &&
                    player.containerMenu.carried.count == itemCount
            },
        )
        waitForServer(context, server, playerId) { player ->
            player.inventory.getItem(playerInventoryIndex).isEmpty &&
                player.containerMenu.carried.`is`(Items.DIRT) &&
                player.containerMenu.carried.count == itemCount
        }
        clickCurrentScreen(context, slotCenter)
        context.waitFor(
            Predicate<Minecraft> { minecraft ->
                val player = minecraft.player ?: return@Predicate false
                player.inventory.getItem(playerInventoryIndex).`is`(Items.DIRT) &&
                    player.inventory.getItem(playerInventoryIndex).count == itemCount &&
                    player.containerMenu.carried.isEmpty
            },
        )
        waitForServer(context, server, playerId) { player ->
            player.inventory.getItem(playerInventoryIndex).`is`(Items.DIRT) &&
                player.inventory.getItem(playerInventoryIndex).count == itemCount &&
                player.containerMenu.carried.isEmpty
        }
    }

    private fun clickCurrentScreen(
        context: ClientGameTestContext,
        position: IntOffset,
    ) {
        context.runOnClient(
            FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                val screen: Screen = activeFabricScreen(minecraft)
                val event =
                    MouseButtonEvent(
                        position.x.toDouble(),
                        position.y.toDouble(),
                        MouseButtonInfo(primaryMouseButton, noModifiers),
                    )
                check(screen.mouseClicked(event, false)) { "The Strata element must consume its primary press." }
                screen.mouseReleased(event)
            },
        )
    }

    private fun waitForServer(
        context: ClientGameTestContext,
        server: MinecraftServer,
        playerId: UUID,
        condition: (ServerPlayer) -> Boolean,
    ) {
        val matched = AtomicBoolean()
        val pending = AtomicBoolean()
        val failure = AtomicReference<Throwable?>()
        context.waitFor(
            Predicate<Minecraft> {
                failure.get()?.let { throwable -> throw throwable }
                if (matched.get()) {
                    true
                } else {
                    if (pending.compareAndSet(false, true)) {
                        server.execute(
                            Runnable {
                                runCatching { matched.set(condition(checkNotNull(server.playerList.getPlayer(playerId)))) }
                                    .exceptionOrNull()
                                    ?.let(failure::set)
                                pending.set(false)
                            },
                        )
                    }
                    false
                }
            },
        )
        failure.get()?.let { throwable -> throw throwable }
    }

    private fun assertCleanFrameReuse(context: ClientGameTestContext) {
        val before = renderWork(context)
        context.waitFor(Predicate<Minecraft> { minecraft -> before.renderExtractions < readRenderWork(minecraft).renderExtractions })
        val after = renderWork(context)
        require(after.hostFrames - before.hostFrames == after.renderExtractions - before.renderExtractions) {
            "Each clean native render must request exactly one common host frame: before=$before, after=$after"
        }
        require(after.framePreparations == before.framePreparations) {
            "An unchanged display list must not be partitioned again: before=$before, after=$after"
        }
        require(after.rasterizations == before.rasterizations) {
            "An unchanged portable layer must not be rasterized again: before=$before, after=$after"
        }
        require(after.textureUploads == before.textureUploads) {
            "An unchanged portable layer must not be uploaded again: before=$before, after=$after"
        }
    }

    private fun assertPortableTextureBounds(context: ClientGameTestContext) {
        context.runOnClient(
            FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                val presentation = nativePresentation(activeFabricScreen(minecraft))
                val sizes =
                    presentation.textures.map { texture ->
                        val pixels = checkNotNull(texture.pixels) { "A displayed Fabric texture was already released." }
                        IntSize(pixels.width, pixels.height)
                    }
                require(sizes == listOf(viewport, slotHighlightTextureSize)) {
                    "Portable runs must retain only their visible bounds instead of one full-viewport texture each: $sizes"
                }
            },
        )
    }

    private fun closeAndAssertReleased(context: ClientGameTestContext) {
        context.runOnClient(
            FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                val screen = activeFabricScreen(minecraft)
                val vanillaScreen: Screen = screen
                val presentation = nativePresentation(screen)
                require(presentation.textures.isNotEmpty()) { "The rendered Fabric screen must own a native texture before detach." }
                minecraft.setScreen(null)
                assertPresentationReleased(screen)
                assertNativePresentationReleased(minecraft, presentation)
                vanillaScreen.onClose()
                assertTerminalReleased(screen)
            },
        )
        context.waitFor(Predicate<Minecraft> { minecraft -> (minecraft.screen is FabricMinecraftScreen).not() })
    }

    private fun assertPresentationReleased(screen: FabricMinecraftScreen) {
        val presentation = fabricPresentation(screen)

        fun retained(name: String): Any? = retainedPresentation(presentation, name)

        require((retained("textures") as List<*>).isEmpty()) { "A detached Fabric screen retained dynamic textures." }
        require((retained("textureLocations") as List<*>).isEmpty()) { "A detached Fabric screen retained registered texture identifiers." }
        require(retained("preparedCommands") == null) { "A detached Fabric screen retained its display list." }
        require(retained("preparedViewport") == null) { "A detached Fabric screen retained its prepared viewport." }
        require((retained("preparedLayers") as List<*>).isEmpty()) { "A detached Fabric screen retained prepared layers." }
        require(retained("pointerPosition") == null) { "A detached Fabric screen retained its native pointer position." }
        require(retained("pointerFrameCommands") == null) { "A detached Fabric screen retained pointer display-list ownership." }
    }

    private fun nativePresentation(screen: FabricMinecraftScreen): NativePresentation {
        val presentation = fabricPresentation(screen)

        fun retained(name: String): Any? = retainedPresentation(presentation, name)

        val textures =
            (retained("textures") as? List<*>)
                ?.map { texture -> texture as? DynamicTexture ?: error("Fabric presentation retained a non-dynamic texture.") }
                ?: error("Fabric presentation textures are not a list.")
        val locations =
            (retained("textureLocations") as? List<*>)
                ?.map { location -> location as? Identifier ?: error("Fabric presentation retained an invalid texture identifier.") }
                ?: error("Fabric presentation texture identifiers are not a list.")
        require(textures.size == locations.size) { "Fabric presentation texture ownership is inconsistent." }
        return NativePresentation(textures.toList(), locations.toList())
    }

    private fun assertNativePresentationReleased(
        minecraft: Minecraft,
        presentation: NativePresentation,
    ) {
        presentation.textures.forEach { texture ->
            require(texture.pixels == null) { "A detached Fabric screen left a native texture open." }
        }
        val textureManager = minecraft.textureManager
        val registryField =
            textureManager.javaClass.declaredFields.single { field ->
                Map::class.java.isAssignableFrom(field.type)
            }
        check(registryField.trySetAccessible()) { "Minecraft's texture registry is inaccessible." }
        val registry = registryField.get(textureManager) as? Map<*, *> ?: error("Minecraft's texture registry is not a map.")
        require(presentation.locations.none { location -> registry.containsKey(location) }) {
            "A detached Fabric screen left a texture registered in Minecraft's TextureManager."
        }
    }

    private fun assertTerminalReleased(screen: FabricMinecraftScreen) {
        val fields = FabricMinecraftScreen::class.java.declaredFields.associateBy { field -> field.name }

        fun retained(name: String): Any? {
            val field = fields[name] ?: error("Fabric terminal field is missing: $name")
            check(field.trySetAccessible()) { "Fabric terminal field is inaccessible: $name" }
            return field.get(screen)
        }

        require(retained("closed") == true) { "A terminally closed Fabric screen did not record closure." }
        require(retained("attached") == false) { "A terminally closed Fabric screen remained attached." }
        val host = retained("host") as? MinecraftUiHost ?: error("Fabric screen host has an unexpected type.")
        require(runCatching { host.title }.exceptionOrNull() is IllegalStateException) {
            "A terminally closed Fabric screen left its common host readable."
        }
        val inventory = retained("inventory") as? MinecraftUiPlatform ?: error("Fabric inventory bridge has an unexpected type.")
        require(runCatching { inventory.refresh() }.exceptionOrNull() is IllegalStateException) {
            "A terminally closed Fabric screen left its Minecraft platform usable."
        }
        val inventoryFields = inventory::class.java.declaredFields.associateBy { field -> field.name }

        fun inventoryRetained(name: String): Any? {
            val field = inventoryFields[name] ?: error("Fabric inventory terminal field is missing: $name")
            check(field.trySetAccessible()) { "Fabric inventory terminal field is inaccessible: $name" }
            return field.get(inventory)
        }

        require((inventoryRetained("bindings") as? Set<*>)?.isEmpty() == true) {
            "A terminally closed Fabric screen retained inventory Slot bindings."
        }
        require((inventoryRetained("skinBindings") as? Set<*>)?.isEmpty() == true) {
            "A terminally closed Fabric screen retained player-skin bindings."
        }
        require((inventoryRetained("quickCraftSlots") as? Set<*>)?.isEmpty() == true) {
            "A terminally closed Fabric screen retained quick-craft bindings."
        }
        require(inventoryRetained("minecraft") == null) { "A terminally closed Fabric screen retained the Minecraft client." }
    }

    private fun assertRenderedPixels(path: Path) {
        NativeImage.read(path.inputStream()).use { image ->
            require(image.getWidth() == viewport.width && image.getHeight() == viewport.height) {
                "The loaded public API scene rendered at an unexpected size."
            }
            val colors = HashSet<Int>()
            for (y in 0 until image.getHeight()) {
                for (x in 0 until image.getWidth()) {
                    colors += image.getPixel(x, y)
                }
            }
            require(minimumRenderedColorCount <= colors.size) {
                "The loaded public API scene did not render enough distinct Minecraft UI colors."
            }
            require(minimumTextLightPixelCount <= lightPixelCount(image, textRegion)) {
                "The loaded public API scene did not render its Text glyphs."
            }
            require(minimumButtonLabelLightPixelCount <= lightPixelCount(image, buttonLabelRegion)) {
                "The loaded public API scene did not render its Button label."
            }
        }
    }

    private fun lightPixelCount(
        image: NativeImage,
        region: IntRect,
    ): Int {
        require(0 <= region.left && region.right <= image.getWidth() && 0 <= region.top && region.bottom <= image.getHeight()) {
            "The asserted Minecraft UI pixel region is outside the screenshot."
        }
        var count = 0
        for (y in region.top until region.bottom) {
            for (x in region.left until region.right) {
                val pixel = image.getPixel(x, y)
                val red = pixel ushr redShift and channelMask
                val green = pixel ushr greenShift and channelMask
                val blue = pixel and channelMask
                if (minimumLightChannel <= red && minimumLightChannel <= green && minimumLightChannel <= blue) count += 1
            }
        }
        return count
    }

    private fun renderWork(context: ClientGameTestContext): RenderWork =
        context.computeOnClient(
            FailableFunction<Minecraft, RenderWork, RuntimeException> { minecraft -> readRenderWork(minecraft) },
        )

    private fun readRenderWork(minecraft: Minecraft): RenderWork {
        val screen = activeFabricScreen(minecraft)
        val presentation = fabricPresentation(screen)

        fun counter(name: String): Long {
            val fields = presentation.javaClass.declaredFields.associateBy { field -> field.name }
            val field = fields[name] ?: error("Fabric render counter field is missing: $name")
            check(field.trySetAccessible()) { "Fabric render counter field is inaccessible: $name" }
            return field.getLong(presentation)
        }

        return RenderWork(
            renderExtractions = counter("renderExtractionCount"),
            hostFrames = counter("hostFrameCount"),
            framePreparations = counter("framePreparationCount"),
            rasterizations = counter("portableRasterizationCount"),
            textureUploads = counter("textureUploadCount"),
        )
    }

    private fun fabricPresentation(screen: FabricMinecraftScreen): Any {
        val fields = FabricMinecraftScreen::class.java.declaredFields.associateBy { field -> field.name }
        val field = fields["presentation"] ?: error("Fabric screen presentation collaborator is missing.")
        check(field.trySetAccessible()) { "Fabric screen presentation collaborator is inaccessible." }
        return checkNotNull(field.get(screen)) { "Fabric screen presentation collaborator is null." }
    }

    private fun retainedPresentation(
        presentation: Any,
        name: String,
    ): Any? {
        val fields = presentation.javaClass.declaredFields.associateBy { field -> field.name }
        val field = fields[name] ?: error("Fabric presentation field is missing: $name")
        check(field.trySetAccessible()) { "Fabric presentation field is inaccessible: $name" }
        return field.get(presentation)
    }

    private fun activeFabricScreen(minecraft: Minecraft): FabricMinecraftScreen = minecraft.screen as? FabricMinecraftScreen ?: error("The Fabric integration screen is not active.")

    private fun loadedMinecraftVersion(): String = SharedConstants.getCurrentVersion().name()

    private fun outputDirectory(): Path {
        val configured = System.getProperty("strata.minecraft12111Output")
        require(configured.isNullOrBlank().not()) { "The Minecraft 1.21.11 verification output directory is not configured." }
        return Path.of(configured)
    }

    private fun minecraftVersion(): String {
        val configured = System.getProperty("strata.minecraftVersion")
        require(configured.isNullOrBlank().not()) { "The Minecraft version is not configured." }
        return configured
    }

    private fun portableDefinition(pressed: AtomicBoolean): ScreenDefinition =
        ScreenDefinition("Minecraft 1.21.11 public API") {
            Stack(
                modifier =
                    Modifier.Empty
                        .size(viewport.width, viewport.height)
                        .background(ArgbColor(opaqueBlack))
                        .menuBackground(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    spacing = 8,
                    horizontalAlignment = HorizontalAlignment.Center,
                ) {
                    Text("Minecraft 1.21.11")
                    Button("Loaded public API", modifier = Modifier.Empty.onPress { pressed.set(true) })
                }
            }
        }

    private fun inventoryDefinition(): ScreenDefinition =
        ScreenDefinition("Minecraft 1.21.11 inventory binding") {
            Stack(
                modifier =
                    Modifier.Empty
                        .size(viewport.width, viewport.height)
                        .background(ArgbColor(opaqueBlack))
                        .menuBackground(),
                contentAlignment = Alignment.Center,
            ) {
                Slot(bind = Slots.playerInventory(playerInventoryIndex))
            }
        }

    private data class RenderWork(
        val renderExtractions: Long,
        val hostFrames: Long,
        val framePreparations: Long,
        val rasterizations: Long,
        val textureUploads: Long,
    )

    private data class NativePresentation(
        val textures: List<DynamicTexture>,
        val locations: List<Identifier>,
    )

    private companion object {
        private val viewport = IntSize(320, 180)
        private val buttonTextureSize = IntSize(200, 20)
        private val slotHighlightTextureSize = IntSize(24, 24)
        private val portableButtonCenter = IntOffset(160, 98)
        private val slotCenter = IntOffset(160, 90)
        private val textRegion = IntRect(110, 68, 210, 82)
        private val buttonLabelRegion = IntRect(100, 91, 220, 104)
        private val opaqueBlack = 0xFF000000.toInt()

        @Suppress("MayBeConstant")
        private val playerInventoryIndex = 0

        @Suppress("MayBeConstant")
        private val itemCount = 7

        @Suppress("MayBeConstant")
        private val primaryMouseButton = 0

        @Suppress("MayBeConstant")
        private val noModifiers = 0

        @Suppress("MayBeConstant")
        private val minimumRenderedColorCount = 4

        @Suppress("MayBeConstant")
        private val minimumTextLightPixelCount = 20

        @Suppress("MayBeConstant")
        private val minimumButtonLabelLightPixelCount = 20

        @Suppress("MayBeConstant")
        private val minimumLightChannel = 240

        @Suppress("MayBeConstant")
        private val redShift = 16

        @Suppress("MayBeConstant")
        private val greenShift = 8

        @Suppress("MayBeConstant")
        private val channelMask = 0xFF
    }
}
