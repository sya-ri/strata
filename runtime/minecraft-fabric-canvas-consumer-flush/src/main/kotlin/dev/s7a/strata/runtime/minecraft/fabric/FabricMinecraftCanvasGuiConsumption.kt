@file:JvmName("FabricMinecraftCanvasGuiConsumption")
@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.systems.RenderSystem
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics

/**
 * Flushes an entire completed legacy screen submission before marking queued Canvas targets consumed.
 *
 * Call only at the end of screen rendering, including its failure path, never during producer capture or display-list extraction.
 * The render-thread graphics context is borrowed for this call and is not retained.
 *
 * @param graphics native GUI context whose pending draws may reference Canvas targets.
 * @param failure the original screen failure, or null after a successful screen submission.
 * @throws Throwable when flushing or lifetime cleanup fails without an earlier screen failure; otherwise cleanup failures are suppressed.
 */
@Suppress("TooGenericExceptionCaught")
@JvmSynthetic
internal fun finishCanvasGui(
    graphics: GuiGraphics,
    failure: Throwable? = null,
) {
    var primary = failure
    try {
        graphics.flush()
    } catch (flushFailure: Throwable) {
        val earlier = primary
        if (earlier == null) {
            primary = flushFailure
            throw flushFailure
        }
        FabricMinecraftFailures.addSuppressed(earlier, flushFailure)
    } finally {
        FabricMinecraftCanvasHooks.afterGui(primary)
    }
}

/**
 * Empties the legacy immediate GUI buffer before terminal Canvas device completion.
 *
 * Unlike deferred extraction families, this family drains pending vertices while native shaders and textures are still alive.
 * The following device completion waits for those submitted draws before releasing Canvas targets.
 * This call borrows the client on the render thread, runs no producer or input callback, and retains no state.
 *
 * @param client native owner of the immediate GUI buffer; a partially constructed client without a renderer has no queue.
 * @throws Throwable if flushing fails, in which case the caller must not release Canvas targets as though the queue were empty.
 */
internal fun discardCanvasGui(client: Minecraft) {
    RenderSystem.assertOnRenderThread()
    if (client.gameRenderer == null) return
    client.renderBuffers().bufferSource().endBatch()
}
