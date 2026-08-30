package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage

/**
 * Borrows one ordinary OpenGL 2D RGBA8 straight-alpha color texture until capture completion.
 *
 * Implementations must keep the texture and its contents stable until close, which occurs on the render thread after a capture fence.
 * The texture is externally owned; Strata never deletes or changes its pixels or sampling parameters.
 * Multisample, depth, layered, HDR, and integer images are unsupported and rejected before sampling.
 */
public interface MinecraftCanvasTextureLease : AutoCloseable {
    /**
     * The externally owned OpenGL 2D texture name in the current render context, borrowed on the render thread.
     *
     * Its identity, extent, and pixels must remain stable until this lease closes after capture completion.
     * The adapter validates that the name identifies a supported texture before issuing source sampling work.
     */
    public val textureId: Int

    /**
     * The immutable positive extent of source mip level zero, read on the render thread.
     *
     * It must match the leased texture and any [snapshot]; an extent mismatch rejects capture before presentation.
     * It need not match the destination extent because the adapter stretches the complete source image.
     * Each axis must be at most 32,768 pixels for exact native integer sampling, and the active device may impose a lower limit.
     */
    public val size: IntSize

    /**
     * The logical image edge represented by native texel row zero, fixed throughout this lease.
     *
     * The render-thread adapter applies this orientation to both GPU sampling and optional snapshot normalization.
     * Declaring an origin does not transfer texture ownership or change its pixels.
     */
    public val origin: MinecraftCanvasTextureOrigin

    /**
     * Optional immutable pixels from exactly this lease, with [size] and source rows ordered according to [origin].
     *
     * The adapter reads this receipt on the render thread and normalizes it to the same extent and orientation as the owned target.
     * Null permits normal native presentation but prevents portable capture of that generation; no implicit readback is performed.
     * The immutable image may outlive the lease and must never represent a different source update.
     */
    public val snapshot: DrawImage?

    /**
     * Releases the source reservation on the render thread after GPU capture completes, independently of later GUI target use.
     *
     * The caller owns the texture before and after release; Strata invokes this callback without deleting the texture itself.
     * Cleanup may request native deletion but must not issue new GPU work or depend on a future GUI frame.
     *
     * @throws Throwable when source cleanup fails; the device preserves the primary failure while attempting independent cleanup.
     */
    override fun close()
}
