package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.Canvas
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Slot
import dev.s7a.strata.component.SlotBinding
import dev.s7a.strata.component.Slots
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.UiScope
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.Minecraft
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * Verifies Canvas, populated native Slot, portable overlay, and clipping order in the same loaded presentation.
 *
 * The caller supplies an already seeded player inventory index and retains ownership of that inventory and world.
 * The fixture owns only its external native texture, and releases it on the client thread after all capture leases finish.
 * Populated and empty native Slot references distinguish item texels from the Slot sprite; literal native-source colors independently verify Canvas coverage.
 */
@OptIn(InternalStrataRuntimeApi::class)
// Why: native assertions must preserve arbitrary primary failures while independent cleanup is still attempted.
@Suppress("TooGenericExceptionCaught")
internal object MinecraftCanvasSlotGameTest {
    /**
     * Runs the overlap scene at GUI scales one and two without mutating or substituting the populated inventory stack.
     *
     * @param context runner-owned native screenshot and client-thread bridge; the caller restores the viewport afterward.
     * @param profile immutable profile from the currently loaded resource manager.
     * @param inventoryIndex populated player inventory slot seeded by the existing server synchronization scenario.
     * @throws Throwable when native rendering, screenshot assertions, scheduling, or independent cleanup fails.
     */
    internal fun run(
        context: MinecraftCanvasTestContext,
        profile: MinecraftUiProfile,
        inventoryIndex: Int,
    ) {
        context.onClient {
            val player = checkNotNull(Minecraft.getInstance().player) { "The Canvas/Slot scene requires a loaded player." }
            check(
                player.inventory
                    .getItem(inventoryIndex)
                    .isEmpty
                    .not(),
            ) { "The Canvas/Slot scene requires the actual populated inventory slot." }
            check(player.containerMenu.carried.isEmpty) { "A carried stack must not cover the Canvas/Slot pixel oracle." }
        }
        val fixture = context.onClient { MinecraftCanvasTestFixture(createMinecraftCanvasTestResources()) }
        var screen: FabricMinecraftScreen? = null
        var failure: Throwable? = null
        try {
            context.configureViewport(viewport, 1)
            val owned = context.onClient { createMinecraftScreen(definition(fixture, Slots.playerInventory(inventoryIndex)), profile, parent = null) }
            screen = owned
            context.onClient { context.setScreen(owned) }
            verify(context, fixture, 1)
            context.configureViewport(viewport, 2)
            verify(context, fixture, 2)
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            cleanup(
                failure,
                { context.onClient { context.setScreen(null) } },
                { context.onClient { screen?.close() ?: Unit } },
                { context.waitFor { fixture.leasesOpened == fixture.leasesClosed } },
                { context.onClient { fixture.close() } },
            )
        }
    }

    private fun definition(
        fixture: MinecraftCanvasTestFixture,
        binding: SlotBinding,
    ): ScreenDefinition =
        ScreenDefinition("Native Canvas and inventory Slot order") {
            Stack(Modifier.Empty.background(ArgbColor(black))) {
                Row(spacing = panelSpacing) {
                    referenceSlot(binding, ArgbColor(black))
                    referenceSlot(binding, ArgbColor(white))
                    nativeOverlap(fixture, binding, CanvasOrder.BeforeSlot)
                    nativeOverlap(fixture, binding, CanvasOrder.AfterSlot)
                    canvasTestClip(IntSize(panelSize, clipHeight)) {
                        nativeOverlap(fixture, binding, CanvasOrder.BeforeSlot)
                    }
                    referenceSlot(null, ArgbColor(black))
                }
            }
        }

    private fun UiScope.referenceSlot(
        binding: SlotBinding?,
        color: ArgbColor,
    ) {
        Stack(Modifier.Empty.size(panelSize, panelSize).background(color), contentAlignment = Alignment.Center) {
            Slot(bind = binding, highlightable = false)
        }
    }

    private fun UiScope.nativeOverlap(
        fixture: MinecraftCanvasTestFixture,
        binding: SlotBinding,
        order: CanvasOrder,
    ) {
        Stack(Modifier.Empty.size(panelSize, panelSize), contentAlignment = Alignment.Center) {
            if (order == CanvasOrder.AfterSlot) Slot(bind = binding, highlightable = false)
            Canvas(fixture.textureSource, IntSize(panelSize, panelSize))
            if (order == CanvasOrder.BeforeSlot) Slot(bind = binding, highlightable = false)
            Spacer(Modifier.Empty.size(overlaySize, overlaySize).background(ArgbColor(white)))
        }
    }

    private fun verify(
        context: MinecraftCanvasTestContext,
        fixture: MinecraftCanvasTestFixture,
        scale: Int,
    ) {
        val earlierLeases = context.onClient { fixture.leasesOpened }
        context.waitFor { earlierLeases < fixture.leasesOpened }
        context.waitTicks(2)
        val name = "strata-canvas-slot-order-scale-$scale"
        val path = context.takeScreenshot(name, viewport)
        val image = checkNotNull(ImageIO.read(path.toFile())) { "The Canvas/Slot native screenshot must be readable." }
        check(image.width == viewport.width && image.height == viewport.height) { "Canvas/Slot screenshots must preserve their full physical extent." }
        val opaqueItemTexels = verifyPanels(image, scale)
        Files.writeString(
            path.resolveSibling("$name.txt"),
            "case=$name\nphysical=640x480\nguiScale=$scale\nsourceSnapshots=absent\nopaqueItemTexels=$opaqueItemTexels\n" +
                "itemOracle=matching-populated-slot-over-black-and-white-distinct-from-empty-slot\nbackend=${fixture.backendDescription}\n",
        )
    }

    private fun verifyPanels(
        image: BufferedImage,
        scale: Int,
    ): Int {
        var opaqueItemTexels = 0
        for (y in 0 until panelSize * scale) {
            for (x in 0 until panelSize * scale) {
                val overlay = overlayStart * scale <= x && x < overlayEnd * scale && overlayStart * scale <= y && y < overlayEnd * scale
                val expectedAbove = if (overlay) white else nativeTexels[(y / (panelSize * scale / 2)) * 2 + x / (panelSize * scale / 2)]
                check(pixel(image, Panel.CanvasAbove, x, y, scale) == expectedAbove) { "Canvas must cover the native Slot, then receive its portable overlay at ($x, $y), scale $scale." }
                val below = pixel(image, Panel.CanvasBelow, x, y, scale)
                if (overlay) check(below == white) { "The portable overlay must cover both the Canvas and native Slot." }
                val blackReference = pixel(image, Panel.ReferenceBlack, x, y, scale)
                val whiteReference = pixel(image, Panel.ReferenceWhite, x, y, scale)
                val emptyReference = pixel(image, Panel.EmptySlot, x, y, scale)
                if (blackReference == whiteReference && blackReference != emptyReference && overlay.not()) {
                    opaqueItemTexels++
                    check(below == blackReference) { "A later native Slot must cover its Canvas at opaque item texel ($x, $y), scale $scale." }
                }
                val expectedClipped = if (y < clipHeight * scale) below else black
                check(pixel(image, Panel.ClippedBelow, x, y, scale) == expectedClipped) { "The ancestor clip must apply equally to Canvas, Slot, and portable overlay at ($x, $y), scale $scale." }
            }
        }
        check(minimumOpaqueItemTexels * scale * scale <= opaqueItemTexels) { "The populated native Slot must contribute an independently visible opaque item region." }
        check(pixel(image, Panel.CanvasBelow, scale, scale, scale) == nativeTexels[0]) { "The Canvas beneath the Slot must remain visible outside the item." }
        return opaqueItemTexels
    }

    private fun pixel(
        image: BufferedImage,
        panel: Panel,
        x: Int,
        y: Int,
        scale: Int,
    ): Int = image.getRGB(panel.x * scale + x, y)

    private fun cleanup(
        primary: Throwable?,
        vararg operations: () -> Unit,
    ) {
        var failure = primary
        for (operation in operations) {
            try {
                operation()
            } catch (caught: Throwable) {
                val previous = failure
                if (previous == null) {
                    failure = caught
                } else if (previous !== caught) {
                    previous.addSuppressed(caught)
                }
            }
        }
        if (primary == null) failure?.let { throw it }
    }

    private enum class Panel(
        val x: Int,
    ) {
        ReferenceBlack(0),
        ReferenceWhite(48),
        CanvasBelow(96),
        CanvasAbove(144),
        ClippedBelow(192),
        EmptySlot(240),
    }

    private enum class CanvasOrder {
        BeforeSlot,
        AfterSlot,
    }

    private val viewport = IntSize(640, 480)

    @Suppress("MayBeConstant")
    private val panelSize = 32

    @Suppress("MayBeConstant")
    private val panelSpacing = 16

    @Suppress("MayBeConstant")
    private val clipHeight = 16

    @Suppress("MayBeConstant")
    private val overlaySize = 4

    @Suppress("MayBeConstant")
    private val overlayStart = 14

    @Suppress("MayBeConstant")
    private val overlayEnd = 18

    @Suppress("MayBeConstant")
    private val minimumOpaqueItemTexels = 32
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val nativeTexels = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFF00.toInt())
}
