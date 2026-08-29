package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.render.DrawImage

/**
 * Owns custom native drawing resources for one attachment resource generation.
 *
 * A factory lazily creates a separate instance during each attachment's first capture, even when a source is shared, and a replacement after resource reload.
 * Initialization uploads share the first capture's completion fence; an attachment closed before native preparation creates no renderer.
 * Calls are confined to the Minecraft render thread and target only the borrowed offscreen target.
 * Drawing directly to the current GUI or window framebuffer is outside this contract.
 * Pixel row zero of the finished target must represent the top image row, with ordinary RGBA8 straight alpha.
 * Instances must not retain the context or target beyond [render].
 */
public interface MinecraftCanvasRenderer : AutoCloseable {
    /**
     * Draws one complete presentation after the target has been cleared to transparent.
     *
     * @param context borrowed native target, final sizes, and presentation timestamp.
     * @return an optional immutable snapshot of exactly these pixels in physical size and top-left orientation.
     * @throws Throwable when drawing fails; target and renderer resources remain protected until submitted work completes.
     */
    public fun render(context: MinecraftCanvasContext): DrawImage?

    /**
     * Releases renderer-owned resources after its final GPU work has completed.
     *
     * This render-thread call occurs once per renderer instance, including failed attachments and retired reload generations.
     * The external factory/source is never closed.
     * Cleanup may enqueue native destruction but must not issue new GPU work or wait for an unconsumed GUI frame.
     * Cleanup failures propagate after the remaining device cleanup has been attempted.
     *
     * @throws Throwable when renderer resource release fails; independent device cleanup still runs and preserves the primary failure.
     */
    override fun close()
}
