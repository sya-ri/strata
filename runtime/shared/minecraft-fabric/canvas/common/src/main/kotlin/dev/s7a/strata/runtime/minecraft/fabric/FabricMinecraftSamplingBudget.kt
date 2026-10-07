package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect

/**
 * Bounds one prepared frame's exact GPU outputs to 256 images and 64 MiB including their axis textures.
 * This traversal-local scalar ledger owns no image, native allocation, or cache and never survives frame preparation.
 */
internal class FabricMinecraftSamplingBudget {
    private var images = 0
    private var bytes = 0L

    /**
     * Reserves visible output and lookup bytes before constructing CPU metadata or acquiring native storage.
     * Invisible commands consume no budget; exhaustion leaves the ledger unchanged and selects exact CPU fallback.
     */
    @JvmSynthetic
    internal fun admit(
        destination: FloatRect,
        clip: IntRect,
        scale: Int,
    ): Boolean {
        val bounds = destination.enclosingFabricViewportBounds(clip) ?: return true
        val width = Math.multiplyExact(bounds.width, scale)
        val height = Math.multiplyExact(bounds.height, scale)
        val output = Math.multiplyExact(width.toLong(), height.toLong())
        val lookup = Math.multiplyExact(maxOf(3, width, height).toLong(), 3L)
        val required = Math.multiplyExact(Math.addExact(output, lookup), 4L)
        if (256 <= images || 64L * 1_024 * 1_024 - bytes < required) return false
        images += 1
        bytes = Math.addExact(bytes, required)
        return true
    }
}
