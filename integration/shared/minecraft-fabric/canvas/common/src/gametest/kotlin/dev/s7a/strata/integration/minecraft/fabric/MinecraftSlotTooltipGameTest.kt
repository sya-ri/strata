@file:Suppress("DEPRECATION") // The loaded acceptance scene preserves the compiled native screen entry point.

package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.Slot
import dev.s7a.strata.component.Slots
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.Minecraft
import net.minecraft.world.item.ItemStack
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Compares complete native Slot tooltip frames with the game's independent container-screen implementation.
 *
 * Inventory replacement stays on the caller's server thread. Screens, native snapshots and settings stay on the client thread.
 * A black portable overlay hides the Slot item and highlight while leaving its real hit region active, isolating the final native tooltip stratum.
 * The reference owns a private native menu and invokes vanilla's protected tooltip method; it never calls the production tooltip helper.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftSlotTooltipGameTest {
    /**
     * Verifies names, enchantments, native item data, advanced settings, stationary updates, edge positioning and carried-item eligibility.
     *
     * The caller restores the viewport. This scene restores settings, authoritative fixture contents and HUD visibility on every exit.
     */
    fun run(
        context: MinecraftCanvasTestContext,
        profile: MinecraftUiProfile,
        inventoryIndex: Int,
        replaceFixture: (MinecraftSlotTooltipFixture) -> Unit,
    ) {
        val screens = mutableListOf<FabricMinecraftScreen>()
        val previous =
            context.onClient {
                val client = Minecraft.getInstance()
                check(checkNotNull(client.player).containerMenu.carried.isEmpty) { "The tooltip scene requires an initially empty carried stack." }
                Pair(client.options.advancedItemTooltips, context.exchangeHudHidden(true))
            }
        val result =
            runCatching {
                for (scale in listOf(1, 2)) {
                    context.configureViewport(viewport, scale)
                    val logical = context.onClient { IntSize(Minecraft.getInstance().window.guiScaledWidth, Minecraft.getInstance().window.guiScaledHeight) }
                    for (position in Position.entries) {
                        verifyPosition(context, profile, inventoryIndex, replaceFixture, screens, logical, position, scale)
                    }
                    verifyEligibility(context, profile, inventoryIndex, replaceFixture, screens, logical, scale)
                }
            }
        runCanvasTestCleanup(
            result.exceptionOrNull(),
            { context.onClient { context.setScreen(null) } },
            { context.onClient { screens.asReversed().forEach(FabricMinecraftScreen::close) } },
            { replaceFixture(MinecraftSlotTooltipFixture.OriginalSword) },
            { context.onClient { Minecraft.getInstance().options.advancedItemTooltips = previous.first } },
            { context.onClient { context.exchangeHudHidden(previous.second) } },
        )
        result.getOrThrow()
    }

    @Suppress("LongParameterList")
    private fun verifyPosition(
        context: MinecraftCanvasTestContext,
        profile: MinecraftUiProfile,
        inventoryIndex: Int,
        replaceFixture: (MinecraftSlotTooltipFixture) -> Unit,
        screens: MutableList<FabricMinecraftScreen>,
        logical: IntSize,
        position: Position,
        scale: Int,
    ) {
        replaceFixture(MinecraftSlotTooltipFixture.OriginalSword)
        val origin = position.origin(logical)
        val pointer = IntOffset(origin.x + slotHalfSize, origin.y + slotHalfSize)
        val screen = context.onClient { createMinecraftScreen(definition(logical, origin, inventoryIndex), profile, parent = null) }.also(screens::add)
        context.onClient {
            context.setScreen(screen)
            Minecraft.getInstance().options.advancedItemTooltips = false
        }
        context.movePointer(pointer)
        val normal = capture(context, screen, inventoryIndex, "scale-$scale-${position.name}-normal")
        context.onClient { Minecraft.getInstance().options.advancedItemTooltips = true }
        val advanced = capture(context, screen, inventoryIndex, "scale-$scale-${position.name}-advanced")
        val advancedChanged = pixels(normal.path).contentEquals(pixels(advanced.path)).not()
        if (advancedChanged.not()) {
            val nativeState =
                context.onClient {
                    val client = Minecraft.getInstance()
                    val window = client.window
                    val hovered =
                        runCatching {
                            val inventoryField = screen.javaClass.getDeclaredField("inventory")
                            check(inventoryField.trySetAccessible())
                            val inventory = checkNotNull(inventoryField.get(screen))
                            val hoveredField = inventory.javaClass.getDeclaredField("hovered")
                            check(hoveredField.trySetAccessible())
                            (hoveredField.get(inventory) != null).toString()
                        }.getOrElse { failure -> "unreadable:${failure.javaClass.name}:${failure.message}" }
                    "logicalPointer=$pointer\nrawPointer=${client.mouseHandler.xpos()},${client.mouseHandler.ypos()}\n" +
                        "logicalViewport=${window.guiScaledWidth}x${window.guiScaledHeight}\n" +
                        "nativeWindow=${window.screenWidth}x${window.screenHeight}\nframebuffer=${window.width}x${window.height}\n" +
                        "guiScale=${window.guiScale}\nforeground=${context.currentScreen() === screen}\noverlay=${context.hasOverlay()}\n" +
                        "hoveredBinding=$hovered\nnormalNativeFrames=${normal.nativeFrameBaseline}..${normal.nativeFramesObserved}\n" +
                        "advancedNativeFrames=${advanced.nativeFrameBaseline}..${advanced.nativeFramesObserved}\n"
                }
            Files.writeString(context.outputDirectory.resolve("strata-tooltip-native-input-$scale-${position.name}.txt"), nativeState)
        }
        check(advancedChanged) { "Changing advanced tooltips must change the same hovered sword without moving its pointer." }
        val observations = mutableListOf(normal, advanced)
        if (position == Position.Center) {
            replaceFixture(MinecraftSlotTooltipFixture.UpdatedSword)
            val updated = capture(context, screen, inventoryIndex, "scale-$scale-stationary-updated")
            check(pixels(advanced.path).contentEquals(pixels(updated.path)).not()) { "Authoritative name and damage changes must update a stationary hovered tooltip." }
            observations += updated
        }
        observations.forEach { observation -> compareVanilla(context, observation, pointer) }
    }

    @Suppress("LongParameterList")
    private fun verifyEligibility(
        context: MinecraftCanvasTestContext,
        profile: MinecraftUiProfile,
        inventoryIndex: Int,
        replaceFixture: (MinecraftSlotTooltipFixture) -> Unit,
        screens: MutableList<FabricMinecraftScreen>,
        logical: IntSize,
        scale: Int,
    ) {
        val origin = Position.Center.origin(logical)
        val pointer = IntOffset(origin.x + slotHalfSize, origin.y + slotHalfSize)
        val screen = context.onClient { createMinecraftScreen(definition(logical, origin, inventoryIndex), profile, parent = null) }.also(screens::add)
        for (fixture in listOf(MinecraftSlotTooltipFixture.Empty, MinecraftSlotTooltipFixture.Bundle, MinecraftSlotTooltipFixture.CarriedSword, MinecraftSlotTooltipFixture.CarriedBundle)) {
            replaceFixture(fixture)
            context.onClient { context.setScreen(screen) }
            context.movePointer(pointer)
            val observation = capture(context, screen, inventoryIndex, "scale-$scale-${fixture.name}")
            if (fixture == MinecraftSlotTooltipFixture.Empty) {
                check(pixels(observation.path).all { it == black }) { "An empty bound Slot must not retain the previous tooltip." }
            }
            if (fixture == MinecraftSlotTooltipFixture.Bundle || fixture == MinecraftSlotTooltipFixture.CarriedBundle) {
                check(context.onClient { observation.stack?.tooltipImage?.isPresent == true }) { "The filled bundle must exercise actual native item-provided tooltip content." }
            }
            compareVanilla(context, observation, pointer)
        }
        replaceFixture(MinecraftSlotTooltipFixture.OriginalSword)
        val unbound = context.onClient { createMinecraftScreen(definition(logical, origin, null), profile, parent = null) }.also(screens::add)
        context.onClient { context.setScreen(unbound) }
        context.movePointer(pointer)
        val observation = capture(context, unbound, null, "scale-$scale-unbound")
        check(pixels(observation.path).all { it == black }) { "An unbound Slot must not inherit another screen's tooltip binding." }
        compareVanilla(context, observation, pointer)
    }

    private fun definition(
        logical: IntSize,
        origin: IntOffset,
        inventoryIndex: Int?,
    ): ScreenDefinition =
        ScreenDefinition("Native Slot tooltip acceptance") {
            Stack(Modifier.size(logical.width, logical.height).background(ArgbColor(black))) {
                Slot(bind = inventoryIndex?.let(Slots::playerInventory), highlightable = false, modifier = Modifier.padding(left = origin.x, top = origin.y))
                // This noninteractive paint plane isolates the native tooltip without replacing the actual Slot hit test.
                Spacer(Modifier.size(logical.width, logical.height).background(ArgbColor(black)))
            }
        }

    private fun capture(
        context: MinecraftCanvasTestContext,
        screen: FabricMinecraftScreen,
        inventoryIndex: Int?,
        name: String,
    ): Capture {
        val baseline = MinecraftCanvasFrameFence.completedFrames(context, screen)
        MinecraftCanvasFrameFence.awaitCompletedFrame(context, screen, baseline)
        val committed = MinecraftCanvasFrameFence.completedFrames(context, screen)
        val path = context.takeScreenshot("strata-slot-tooltip-$name", viewport)
        return context.onClient {
            val client = Minecraft.getInstance()
            val player = checkNotNull(client.player)
            val guiScale: Number = client.window.guiScale
            Capture(
                path,
                inventoryIndex?.let { player.inventory.getItem(it).copy() },
                player.containerMenu.carried.copy(),
                client.options.advancedItemTooltips,
                guiScale.toDouble(),
                IntSize(client.window.screenWidth, client.window.screenHeight),
                baseline,
                committed,
            )
        }
    }

    private fun compareVanilla(
        context: MinecraftCanvasTestContext,
        observation: Capture,
        pointer: IntOffset,
    ) {
        val reference =
            context.onClient {
                Minecraft.getInstance().options.advancedItemTooltips = observation.advanced
                MinecraftSlotTooltipReferenceScreen(observation.stack, observation.carried).also(context::setScreen)
            }
        context.movePointer(pointer)
        val baseline = context.onClient { reference.frames }
        context.waitFor { baseline < reference.frames }
        val native =
            context.takeScreenshot(
                observation.path.fileName
                    .toString()
                    .removeSuffix(".png") + "-vanilla",
                viewport,
            )
        val actualPixels = pixels(observation.path)
        val expectedPixels = pixels(native)
        val mismatch = actualPixels.indices.count { index -> actualPixels[index] != expectedPixels[index] }
        val receipt =
            "oracle=vanilla-AbstractContainerScreen\nfullRGBA=true\ntolerance=0\nphysical=640x480\n" +
                "guiScale=${observation.guiScale}\nnativeWindow=${observation.nativeWindow}\n" +
                "nativeFrameBaseline=${observation.nativeFrameBaseline}\nnativeFramesObserved=${observation.nativeFramesObserved}\n" +
                "advanced=${observation.advanced}\ncarriedEmpty=${context.onClient { observation.carried.isEmpty }}\n" +
                "itemTooltipImage=${context.onClient { observation.stack?.tooltipImage?.isPresent ?: false }}\n" +
                "referenceFrameBaseline=$baseline\nreferenceFrames=${context.onClient { reference.frames }}\nmismatchedPixels=$mismatch\n"
        Files.writeString(
            observation.path.resolveSibling(
                observation.path.fileName
                    .toString()
                    .removeSuffix(".png") + ".txt",
            ),
            receipt,
        )
        if (mismatch != 0) {
            val diff = BufferedImage(viewport.width, viewport.height, BufferedImage.TYPE_INT_ARGB)
            val colors = IntArray(actualPixels.size) { index -> if (actualPixels[index] == expectedPixels[index]) black else red }
            diff.setRGB(0, 0, viewport.width, viewport.height, colors, 0, viewport.width)
            ImageIO.write(
                diff,
                "PNG",
                observation.path
                    .resolveSibling(
                        observation.path.fileName
                            .toString()
                            .removeSuffix(".png") + "-diff.png",
                    ).toFile(),
            )
        }
        check(mismatch == 0) { "The complete Slot tooltip frame differs from vanilla at $mismatch pixels: ${observation.path.fileName}." }
        if (context.onClient { observation.stack?.isEmpty == false && observation.carried.isEmpty }) {
            check(actualPixels.any { it != black }) { "A populated hovered Slot must display an actual native tooltip." }
        }
    }

    private fun pixels(path: Path): IntArray {
        val image = checkNotNull(ImageIO.read(path.toFile())) { "The native tooltip screenshot must be readable: $path" }
        check(image.width == viewport.width && image.height == viewport.height) { "Tooltip captures must retain the complete physical viewport." }
        return image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
    }

    private data class Capture(
        val path: Path,
        val stack: ItemStack?,
        val carried: ItemStack,
        val advanced: Boolean,
        val guiScale: Double,
        val nativeWindow: IntSize,
        val nativeFrameBaseline: Long,
        val nativeFramesObserved: Long,
    )

    private enum class Position {
        Center,
        UpperLeft,
        LowerRight,
        ;

        fun origin(logical: IntSize): IntOffset =
            when (this) {
                Center -> IntOffset(logical.width / 2 - slotHalfSize, logical.height / 2 - slotHalfSize)
                UpperLeft -> IntOffset(1, 1)
                LowerRight -> IntOffset(logical.width - slotSize - 1, logical.height - slotSize - 1)
            }
    }

    private val viewport = IntSize(640, 480)
    private val black = 0xFF000000.toInt()
    private val red = 0xFFFF0000.toInt()

    @Suppress("MayBeConstant")
    private val slotHalfSize = 9

    @Suppress("MayBeConstant")
    private val slotSize = 18
}
