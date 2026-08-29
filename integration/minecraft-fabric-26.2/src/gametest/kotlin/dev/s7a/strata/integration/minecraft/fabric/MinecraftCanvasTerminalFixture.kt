package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.systems.RenderSystem
import dev.s7a.strata.component.Canvas
import dev.s7a.strata.component.CanvasSource
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDevices
import dev.s7a.strata.runtime.minecraft.fabric.MinecraftCanvasContext
import dev.s7a.strata.runtime.minecraft.fabric.MinecraftCanvasRenderer
import dev.s7a.strata.runtime.minecraft.fabric.canvasSource
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.extractMinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.fabric.mixin.canvas.FabricMinecraftCanvasGameRendererAccess
import dev.s7a.strata.runtime.minecraft.fabric.mixin.canvas.FabricMinecraftCanvasRenderStateAccess
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.state.gui.BlitRenderState
import net.minecraft.client.renderer.state.gui.GuiRenderState
import org.joml.Vector4f

/**
 * Records one real offscreen capture between portable background and foreground uploads, leaving all GUI blits queued for terminal cleanup.
 *
 * Preparation runs on the client render thread before Minecraft closes its renderer or resources.
 * The renderer owns an independently uploaded texture and a real last-use fence probe, and returns no CPU snapshot.
 * This fixture retains only scalar observations and Minecraft's borrowed GUI queue; it never owns a target or native renderer.
 * Verification after original close invokes no GPU API and releases the borrowed queue reference even if an assertion fails.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftCanvasTerminalFixture {
    private var queue: GuiRenderState? = null
    private var backend: MinecraftCanvasTestBackend? = null
    private var backendDescription = ""
    private var elementsAdded = 0
    private var blitsAdded = 0
    private var queuedElements = 0
    private var retainedTargets = 0
    private var retainedPortableSets = 0
    private var renderersOpened = 0
    private var renderCalls = 0
    private var renderersClosed = 0

    /**
     * Installs a mixed portable/native Canvas scene directly into the actual renderer-owned GUI queue.
     *
     * The source and screen are transferred to ordinary production lifecycle ownership before capture begins.
     * No GUI consumer or artificial completion callback is invoked; production close must discard this queue and finish recorded capture work.
     * The caller always invokes original Minecraft close after this operation, including on failure.
     *
     * @param client live native owner at the outer close boundary.
     * @param expectedBackend optional requested backend; a fallback fails before any receipt can be produced.
     * @throws Throwable when native preparation or independent queue, resource, and callback assertions fail.
     */
    internal fun enqueue(
        client: Minecraft,
        expectedBackend: MinecraftCanvasTestBackend?,
    ) {
        RenderSystem.assertOnRenderThread()
        check(queue == null) { "The terminal Canvas fixture cannot enqueue twice." }
        check(NativeCanvasDevices.retainedTargetCount() == 0) { "The completed suite still retains native Canvas targets." }
        val deviceInfo = RenderSystem.getDevice().deviceInfo
        val loadedBackend = MinecraftCanvasTestBackend.parse(deviceInfo.backendName())
        check(expectedBackend == null || expectedBackend == loadedBackend) { "The terminal proof loaded an unexpected GPU backend." }
        backend = loadedBackend
        backendDescription = deviceInfo.toString()
        val guiRenderer = (client.gameRenderer as FabricMinecraftCanvasGameRendererAccess).strataCanvasGuiRenderer()
        val state = (guiRenderer as FabricMinecraftCanvasRenderStateAccess).strataCanvasRenderState()
        queue = state
        val previousElements = countElements(state)
        val previousBlits = countBlits(state)
        val source = canvasSource(depth = true) { createRenderer() }
        val screen = createMinecraftScreen(definition(source), extractMinecraftUiProfile(), parent = null)
        MinecraftClientScreenAccess.setScreen(client, screen)
        screen.extractRenderState(GuiGraphicsExtractor(client, state, 0, 0), 0, 0, 0f)
        queuedElements = countElements(state)
        elementsAdded = queuedElements - previousElements
        blitsAdded = countBlits(state) - previousBlits
        retainedTargets = NativeCanvasDevices.retainedTargetCount()
        retainedPortableSets = NativeCanvasDevices.retainedGuiResourceSetCount()
        check(3 <= elementsAdded && 3 <= blitsAdded) { "The Canvas and both portable layers did not add real unconsumed GUI blits." }
        check(retainedTargets == 1) { "The queued terminal Canvas must retain exactly one target set." }
        check(0 < retainedPortableSets) { "The queued terminal background and foreground must retain their portable resource generation." }
        check(renderersOpened == 1 && renderCalls == 1 && renderersClosed == 0) { "The queued Canvas renderer was duplicated or closed before terminal cleanup." }
    }

    /**
     * Verifies successful actual shutdown using only CPU-side observations and returns immutable receipt fields.
     *
     * The real renderer probe has already checked its last-use native fence during production cleanup.
     * Native target accounting includes physical destruction acknowledgements, so zero proves the terminal drain returned every permit.
     * The borrowed GUI state is detached before assertions, and no resource, context, device, or fence is queried after vanilla close.
     *
     * @return immutable evidence for the single capture whose original close has returned successfully.
     * @throws IllegalStateException if queued references, renderer ownership, or lifetime permits survived shutdown.
     */
    internal fun verifyReleased(): Map<String, String> {
        val state = checkNotNull(queue) { "The terminal Canvas was never queued." }
        queue = null
        val remainingElements = countElements(state)
        val remainingTargets = NativeCanvasDevices.retainedTargetCount()
        val remainingPortableSets = NativeCanvasDevices.retainedGuiResourceSetCount()
        check(remainingElements == 0) { "Production shutdown left native GUI references queued." }
        check(remainingTargets == 0) { "Production shutdown did not acknowledge physical Canvas target destruction." }
        check(remainingPortableSets == 0) { "Production shutdown did not acknowledge physical portable GUI resource destruction." }
        check(renderersOpened == 1 && renderCalls == 1 && renderersClosed == 1) { "Production shutdown did not release the renderer exactly once after its last GPU work." }
        return mapOf(
            "backend" to checkNotNull(backend).name,
            "backendDescription" to backendDescription,
            "queuedElementsBefore" to queuedElements.toString(),
            "queuedElementsAdded" to elementsAdded.toString(),
            "queuedBlitsAdded" to blitsAdded.toString(),
            "queuedElementsAfter" to remainingElements.toString(),
            "targetsBefore" to retainedTargets.toString(),
            "targetsAfter" to remainingTargets.toString(),
            "portableSetsBefore" to retainedPortableSets.toString(),
            "portableSetsAfter" to remainingPortableSets.toString(),
            "renderersOpened" to renderersOpened.toString(),
            "renderCalls" to renderCalls.toString(),
            "renderersClosed" to renderersClosed.toString(),
        )
    }

    private fun definition(source: CanvasSource): ScreenDefinition =
        ScreenDefinition("Mixed Canvas terminal queue acceptance") {
            Stack(Modifier.Empty.size(64, 48).background(ArgbColor(0xFF0000FF.toInt()))) {
                Canvas(source, IntSize(32, 32))
                Spacer(Modifier.Empty.size(8, 8).background(ArgbColor(0xFFFF0000.toInt())))
            }
        }

    private fun createRenderer(): MinecraftCanvasRenderer {
        val probe = MinecraftCanvasRendererProbe.create()
        renderersOpened++
        return object : MinecraftCanvasRenderer {
            override fun render(context: MinecraftCanvasContext): DrawImage? {
                check(context.target.useDepth) { "The terminal Canvas must have its requested depth attachment." }
                context.encoder.clearColorTexture(checkNotNull(context.target.colorTexture), Vector4f(0f, 1f, 0f, 128f / 255f))
                probe.recordUse()
                renderCalls++
                return null
            }

            override fun close() {
                probe.close()
                renderersClosed++
            }
        }
    }

    private fun countElements(state: GuiRenderState): Int {
        var count = 0
        state.forEachElement({ count++ }, GuiRenderState.TraverseRange.ALL)
        return count
    }

    private fun countBlits(state: GuiRenderState): Int {
        var count = 0
        state.forEachElement({ element -> if (element is BlitRenderState) count++ }, GuiRenderState.TraverseRange.ALL)
        return count
    }
}
