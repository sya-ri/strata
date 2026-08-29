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
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDevices
import dev.s7a.strata.runtime.minecraft.canvas.NativeGuiResource
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.extractMinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.fabric.loadMinecraftUiImage
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.texture.AbstractTexture
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO

/**
 * Proves the public Strata screen, asset, input, and inventory contracts in a loaded legacy Minecraft client.
 *
 * The test owns every screen and integrated-world resource that it creates, performs runner-independent client work through [MinecraftLoadedTestContext], and rejects retained presentation data after each screen is detached.
 * Native texture owners are observed separately until actual GUI-use fences and physical destruction complete, without blocking the client thread.
 */
@OptIn(InternalStrataRuntimeApi::class)
@Suppress("TooManyFunctions")
internal class StrataMinecraftLegacyLoadedSuite {
    /**
     * Opens and renders portable and inventory-bound public API scenes against the configured legacy client.
     *
     * @param context loaded-client coordinator owning client-thread, tick, and integrated-world handoffs.
     * @throws AssertionError when the loaded version, asset profile, rendering, input, inventory synchronization, frame reuse, or cleanup contract fails.
     * @throws Throwable when Minecraft or Fabric cannot create, render, or close a required test resource.
     */
    fun run(context: MinecraftLoadedTestContext) {
        configureViewport(context)
        require(minecraftVersion() == loadedMinecraftVersion()) {
            "The loaded Minecraft release does not match the configured integration target."
        }

        val profile = context.computeOnClient { extractMinecraftUiProfile() }
        verifyVersionAsset(context)
        val output = outputDirectory()
        Files.createDirectories(output)
        verifyProfileCache(context, output)
        verifyContinuousInput(context, profile, output)
        runMinecraftCanvasTest(context, profile, output)
        verifyPortableScene(context, output)
        verifyPlayerInventoryBinding(context, profile, output)
    }

    private fun configureViewport(context: MinecraftLoadedTestContext) {
        context.computeOnClient { minecraft ->
            minecraft.window.setWindowed(viewport.width, viewport.height)
            minecraft.options.guiScale().set(1)
            minecraft.options.forceUnicodeFont().set(false)
            minecraft.resizeDisplay()
        }
    }

    private fun verifyProfileCache(
        context: MinecraftLoadedTestContext,
        output: Path,
    ) {
        val probe = MinecraftProfileCacheProbe({ it.screen }, { it.setScreen(null) })
        val outcome =
            runCatching {
                val reload = context.computeOnClient(probe::begin)
                context.waitFor { reload.isDone }
                reload.join()
                // The reload future completes before LoadingOverlay finishes its native fade-out.
                context.waitFor { it.overlay == null }
                context.computeOnClient(probe::afterReload)
                context.waitFor { probe.collected() }
                probe.writeReceipt(output)
            }
        val cleanup = runCatching { context.computeOnClient { probe.close() } }
        outcome.exceptionOrNull()?.let { failure ->
            cleanup.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        cleanup.getOrThrow()
    }

    private fun verifyVersionAsset(context: MinecraftLoadedTestContext) {
        val ascii =
            context.computeOnClient {
                loadMinecraftUiImage(ResourceId("minecraft", "textures/font/ascii.png"))
            }
        require(ascii.size == asciiTextureSize) {
            "Minecraft ${minecraftVersion()}'s regular ASCII font must be the verified 128 by 128 pixel asset."
        }
    }

    private fun verifyContinuousInput(
        context: MinecraftLoadedTestContext,
        profile: MinecraftUiProfile,
        output: Path,
    ) {
        val probe = context.computeOnClient { MinecraftContinuousInputProbe() }
        val screen = context.computeOnClient { createMinecraftScreen(probe.definition(), profile, parent = null) }
        // Keep inherited Minecraft callback references on their mapped vanilla owner.
        val vanillaScreen: Screen = screen
        val outcome =
            runCatching {
                context.computeOnClient { minecraft -> minecraft.setScreen(screen) }
                context.waitFor { minecraft ->
                    minecraft.screen === screen && probe.isReady() && 0L < readRenderWork(minecraft).framePreparations
                }
                context.computeOnClient { minecraft ->
                    probe.verify(
                        frameCount = { readRenderWork(minecraft).hostFrames },
                        scroll = { scrollMinecraftScreen(screen, probe.position) },
                        move = { vanillaScreen.mouseMoved(probe.position.x.toDouble(), probe.position.y.toDouble()) },
                        click = { clickMinecraftScreen(screen, probe.position) },
                    )
                }
            }
        val cleanup = runCatching { context.computeOnClient { vanillaScreen.onClose() } }
        val receipt =
            outcome.getOrElse { failure ->
                cleanup.exceptionOrNull()?.let { if (it !== failure) failure.addSuppressed(it) }
                throw failure
            }
        cleanup.getOrThrow()
        Files.writeString(output.resolve("continuous-input.txt"), "minecraftVersion=${minecraftVersion()}\n$receipt")
    }

    private fun verifyPortableScene(
        context: MinecraftLoadedTestContext,
        output: Path,
    ) {
        val pressed = AtomicBoolean()
        movePointer(context, portableButtonCenter)
        context.computeOnClient {
            portableDefinition(pressed).open()
        }
        context.waitFor { minecraft -> minecraft.screen is FabricMinecraftScreen }
        movePointer(context, slotCenter)
        context.waitTicks(2)

        val screenshot = takeScreenshot(context, "strata-public-api-${minecraftVersion()}", output)
        assertRenderedPixels(screenshot)
        assertCleanFrameReuse(context)
        clickCurrentScreen(context, portableButtonCenter)
        context.waitFor { pressed.get() }
        closeAndAssertReleased(context)
    }

    private fun verifyPlayerInventoryBinding(
        context: MinecraftLoadedTestContext,
        profile: MinecraftUiProfile,
        output: Path,
    ) {
        val world = context.createSingleplayerWorld()
        try {
            world.awaitReady()
            val server = world.server
            val playerId = context.computeOnClient { minecraft -> checkNotNull(minecraft.player).uuid }
            world.computeOnServer { server ->
                val player = server.playerList.players.single()
                player.inventory.setItem(playerInventoryIndex, ItemStack(Items.DIRT, itemCount))
                player.inventoryMenu.broadcastChanges()
            }
            context.waitFor { minecraft ->
                val stack = minecraft.player?.inventory?.getItem(playerInventoryIndex)
                stack != null && stack.`is`(Items.DIRT) && stack.count == itemCount
            }

            runMinecraftCanvasSlotTest(context, profile, output, playerInventoryIndex)
            openPlayerInventoryScreen(context, profile, output)
            verifyPlayerInventoryRoundTrip(context, server, playerId)
            closeAndAssertReleased(context)
        } finally {
            world.close()
        }
    }

    private fun openPlayerInventoryScreen(
        context: MinecraftLoadedTestContext,
        profile: MinecraftUiProfile,
        output: Path,
    ) {
        context.computeOnClient { minecraft ->
            minecraft.setScreen(createMinecraftScreen(inventoryDefinition(), profile, parent = null))
        }
        context.waitFor { minecraft ->
            val screen = minecraft.screen as? FabricMinecraftScreen ?: return@waitFor false
            nativePresentation(screen).textures.isNotEmpty()
        }
        movePointer(context, slotCenter)
        val textureObservation = AtomicReference<PortableTextureObservation?>()
        context.waitFor { minecraft ->
            val screen = minecraft.screen as? FabricMinecraftScreen ?: return@waitFor false
            val observed = portableTextureObservation(screen)
            if (observed.sizes == listOf(viewport, slotHighlightTextureSize)) {
                textureObservation.set(observed)
                true
            } else {
                false
            }
        }
        assertPortableTextureBounds(checkNotNull(textureObservation.get()))
        // This PNG is diagnostic; the strict texture-bounds gate uses the immutable observation captured above.
        takeScreenshot(context, "strata-player-inventory-binding-${minecraftVersion()}", output)
    }

    private fun verifyPlayerInventoryRoundTrip(
        context: MinecraftLoadedTestContext,
        server: MinecraftServer,
        playerId: UUID,
    ) {
        clickCurrentScreen(context, slotCenter)
        context.waitFor { minecraft ->
            val player = minecraft.player ?: return@waitFor false
            player.inventory.getItem(playerInventoryIndex).isEmpty &&
                player.containerMenu.carried.`is`(Items.DIRT) &&
                player.containerMenu.carried.count == itemCount
        }
        waitForServer(context, server, playerId) { player ->
            player.inventory.getItem(playerInventoryIndex).isEmpty &&
                player.containerMenu.carried.`is`(Items.DIRT) &&
                player.containerMenu.carried.count == itemCount
        }
        context.waitTicks(6)
        clickCurrentScreen(context, slotCenter)
        context.waitFor { minecraft ->
            val player = minecraft.player ?: return@waitFor false
            player.inventory.getItem(playerInventoryIndex).`is`(Items.DIRT) &&
                player.inventory.getItem(playerInventoryIndex).count == itemCount &&
                player.containerMenu.carried.isEmpty
        }
        waitForServer(context, server, playerId) { player ->
            player.inventory.getItem(playerInventoryIndex).`is`(Items.DIRT) &&
                player.inventory.getItem(playerInventoryIndex).count == itemCount &&
                player.containerMenu.carried.isEmpty
        }
    }

    private fun clickCurrentScreen(
        context: MinecraftLoadedTestContext,
        position: IntOffset,
    ) {
        context.computeOnClient { minecraft ->
            val screen: Screen = activeFabricScreen(minecraft)
            clickMinecraftScreen(screen, position)
        }
    }

    private fun movePointer(
        context: MinecraftLoadedTestContext,
        position: IntOffset,
    ) {
        context.movePointer(position)
    }

    private fun takeScreenshot(
        context: MinecraftLoadedTestContext,
        name: String,
        output: Path,
    ): Path = context.takeScreenshot(name, output, viewport)

    private fun waitForServer(
        context: MinecraftLoadedTestContext,
        server: MinecraftServer,
        playerId: UUID,
        condition: (ServerPlayer) -> Boolean,
    ) {
        val matched = AtomicBoolean()
        val pending = AtomicBoolean()
        val failure = AtomicReference<Throwable?>()
        context.waitFor {
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
        }
        failure.get()?.let { throwable -> throw throwable }
    }

    private fun assertCleanFrameReuse(context: MinecraftLoadedTestContext) {
        context.waitFor { minecraft ->
            val initialized = readRenderWork(minecraft)
            0 < initialized.renderExtractions && 0 < initialized.framePreparations
        }
        val before = renderWork(context)
        context.waitFor { minecraft -> before.renderExtractions < readRenderWork(minecraft).renderExtractions }
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

    private fun assertPortableTextureBounds(observation: PortableTextureObservation) {
        require(observation.sizes == listOf(viewport, slotHighlightTextureSize)) {
            "Portable runs must retain only their visible bounds instead of one full-viewport texture each: sizes=${observation.sizes}, pointer=${observation.pointer}"
        }
    }

    private fun portableTextureObservation(screen: FabricMinecraftScreen): PortableTextureObservation {
        val presentation = fabricPresentation(screen)
        return PortableTextureObservation(
            sizes = nativeTextureSizes(screen),
            pointer = retainedPresentation(presentation, "pointerPosition") as? IntOffset,
        )
    }

    private fun nativeTextureSizes(screen: FabricMinecraftScreen): List<IntSize> =
        nativePresentation(screen).textures.map { texture ->
            val pixels = retainedPresentation(texture, "pixels") as? NativeImage ?: error("A displayed Fabric texture has no owned native pixels.")
            IntSize(pixels.width, pixels.height)
        }

    private fun closeAndAssertReleased(context: MinecraftLoadedTestContext) {
        val presentation =
            context.computeOnClient { minecraft ->
                val screen = activeFabricScreen(minecraft)
                val vanillaScreen: Screen = screen
                val retained = nativePresentation(screen)
                require(retained.textures.isNotEmpty()) { "The rendered Fabric screen must own a native texture before detach." }
                minecraft.setScreen(null)
                assertPresentationReleased(screen)
                vanillaScreen.onClose()
                assertTerminalReleased(screen)
                retained
            }
        context.waitFor { minecraft ->
            (minecraft.screen is FabricMinecraftScreen).not() && NativeCanvasDevices.retainedGuiResourceSetCount() == 0
        }
        context.computeOnClient { minecraft -> assertNativePresentationReleased(minecraft, presentation) }
    }

    private fun assertPresentationReleased(screen: FabricMinecraftScreen) {
        val presentation = fabricPresentation(screen)

        fun retained(name: String): Any? = retainedPresentation(presentation, name)

        val portableFrames = checkNotNull(retained("portableFrames")) { "The Fabric presenter has no portable cache owner." }
        require(retainedPresentation(portableFrames, "current") == null) { "A detached Fabric screen retained its portable texture generation." }
        require(retained("preparedCommands") == null) { "A detached Fabric screen retained its display list." }
        require(retained("preparedViewport") == null) { "A detached Fabric screen retained its prepared viewport." }
        require((retained("preparedLayers") as List<*>).isEmpty()) { "A detached Fabric screen retained prepared layers." }
        require(retained("pointerPosition") == null) { "A detached Fabric screen retained its native pointer position." }
        require(retained("pointerFrameCommands") == null) { "A detached Fabric screen retained pointer display-list ownership." }
    }

    private fun nativePresentation(screen: FabricMinecraftScreen): NativePresentation {
        val presentation = fabricPresentation(screen)
        val portableFrames = checkNotNull(retainedPresentation(presentation, "portableFrames")) { "The Fabric presenter has no portable cache owner." }
        // Readiness predicates may run before the newly installed screen's first native presentation.
        val prepared = retainedPresentation(portableFrames, "current") ?: return NativePresentation(emptyList(), emptyList())
        val textures =
            (retainedPresentation(prepared, "textures") as? List<*>)
                ?.map { texture -> texture as? NativeGuiResource ?: error("Fabric presentation retained an invalid native texture owner.") }
                ?: error("Fabric presentation textures are not a list.")
        val locations =
            textures.map { texture ->
                retainedPresentation(texture, "location") as? MinecraftTestResourceLocation ?: error("Fabric presentation retained an invalid texture identifier.")
            }
        require(textures.size == locations.distinct().size) { "Fabric portable textures must retain distinct native registrations." }
        return NativePresentation(textures.toList(), locations.toList())
    }

    private fun assertNativePresentationReleased(
        minecraft: Minecraft,
        presentation: NativePresentation,
    ) {
        presentation.textures.forEach { texture ->
            require(texture.isDestroyed()) { "A detached Fabric screen retained native texture storage after physical retirement." }
            require(retainedPresentation(texture, "pixels") == null) { "A physically retired Fabric texture retained native upload pixels." }
        }
        val textureManager = minecraft.textureManager
        val registry =
            textureManager.javaClass.declaredFields
                .filter { field -> Map::class.java.isAssignableFrom(field.type) }
                .map { field ->
                    check(field.trySetAccessible()) { "A Minecraft texture-manager map is inaccessible." }
                    field.get(textureManager) as? Map<*, *> ?: error("A Minecraft texture-manager map has an invalid value.")
                }.single { candidate ->
                    candidate.isNotEmpty() &&
                        candidate.all { (key, value) -> key is MinecraftTestResourceLocation && value is AbstractTexture }
                }
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
        val image = requireNotNull(ImageIO.read(path.toFile())) { "The loaded public API screenshot is not a decodable image." }
        require(image.width == viewport.width && image.height == viewport.height) {
            "The loaded public API scene rendered at an unexpected size."
        }
        val colors = HashSet<Int>()
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                colors += image.getRGB(x, y)
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

    private fun lightPixelCount(
        image: BufferedImage,
        region: IntRect,
    ): Int {
        require(0 <= region.left && region.right <= image.width && 0 <= region.top && region.bottom <= image.height) {
            "The asserted Minecraft UI pixel region is outside the screenshot."
        }
        var count = 0
        for (y in region.top until region.bottom) {
            for (x in region.left until region.right) {
                val pixel = image.getRGB(x, y)
                val red = pixel ushr redShift and channelMask
                val green = pixel ushr greenShift and channelMask
                val blue = pixel and channelMask
                if (minimumLightChannel <= red && minimumLightChannel <= green && minimumLightChannel <= blue) count += 1
            }
        }
        return count
    }

    private fun renderWork(context: MinecraftLoadedTestContext): RenderWork = context.computeOnClient { minecraft -> readRenderWork(minecraft) }

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

    private fun outputDirectory(): Path {
        val configured = System.getProperty("strata.minecraftLegacyOutput")
        require(configured.isNullOrBlank().not()) { "The legacy Minecraft verification output directory is not configured." }
        return Path.of(configured)
    }

    private fun minecraftVersion(): String {
        val configured = System.getProperty("strata.minecraftVersion")
        require(configured.isNullOrBlank().not()) { "The Minecraft version is not configured." }
        return configured
    }

    private fun portableDefinition(pressed: AtomicBoolean): ScreenDefinition =
        ScreenDefinition("Minecraft ${minecraftVersion()} public API") {
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
                    Text("Minecraft ${minecraftVersion()}")
                    Button("Loaded public API", modifier = Modifier.Empty.onPress { pressed.set(true) })
                }
            }
        }

    private fun inventoryDefinition(): ScreenDefinition =
        ScreenDefinition("Minecraft ${minecraftVersion()} inventory binding") {
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
        val textures: List<NativeGuiResource>,
        val locations: List<MinecraftTestResourceLocation>,
    )

    private data class PortableTextureObservation(
        val sizes: List<IntSize>,
        val pointer: IntOffset?,
    )

    private companion object {
        private val viewport = IntSize(320, 180)
        private val asciiTextureSize = IntSize(128, 128)
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
