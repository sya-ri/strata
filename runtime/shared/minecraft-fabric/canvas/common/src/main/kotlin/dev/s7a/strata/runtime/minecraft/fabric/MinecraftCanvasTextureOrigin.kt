package dev.s7a.strata.runtime.minecraft.fabric

/**
 * Identifies which logical image edge contains native texel row zero.
 *
 * This immutable value is safe to share between threads and does not describe window or framebuffer coordinates.
 * Canvas adapters normalize leased images to [TopLeft] before presentation.
 */
public enum class MinecraftCanvasTextureOrigin {
    /**
     * Native texel row zero contains the top logical image row.
     *
     * The adapter preserves this row order when sampling the leased texture and normalizing its optional snapshot.
     */
    TopLeft,

    /**
     * Native texel row zero contains the bottom logical image row.
     *
     * The adapter reverses this row order when sampling the leased texture and normalizing its optional snapshot.
     */
    BottomLeft,
}
