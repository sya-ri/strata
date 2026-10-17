package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize

/**
 * Bounds one prepared frame's exact GPU outputs to 256 images, 1024 passes and 64 MiB including all metadata payload copies.
 * This traversal-local scalar ledger owns no image, native allocation, or cache and never survives frame preparation.
 */
internal class FabricMinecraftSamplingBudget {
    private var images = 0
    private var bytes = 0L
    private var passes = 0

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
        return reserve(required, 1)
    }

    /**
     * Reserves both ping-pong outputs, three metadata payload copies and every ordered pass before allocation.
     */
    @JvmSynthetic
    internal fun admitComposition(
        size: IntSize,
        metadataPixels: Long,
        passCount: Int,
    ): Boolean {
        require(0 < metadataPixels && passCount in 1..1024)
        return reserve(compositionBytes(size, metadataPixels, passCount), passCount)
    }

    private fun reserve(
        required: Long,
        passCount: Int,
    ): Boolean {
        if (256 <= images || 1024 - passes < passCount || 64L * 1_024 * 1_024 - bytes < required) return false
        images += 1
        passes += passCount
        bytes = Math.addExact(bytes, required)
        return true
    }

    /**
     * Checked byte accounting shared with the native-generation extent reservation.
     * Fixed and per-pass allowances cover metadata owners, headers, ordered source references and temporary plan records.
     */
    internal companion object {
        /**
         * Counts both RGBA8 targets, all CPU/staging/GPU metadata payloads and conservative bounded scalar/reference overhead.
         */
        @JvmSynthetic
        internal fun compositionBytes(
            size: IntSize,
            metadataPixels: Long,
            passCount: Int,
        ): Long {
            val output = Math.multiplyExact(size.width.toLong(), size.height.toLong())
            val payload = Math.multiplyExact(Math.addExact(Math.multiplyExact(output, 2L), Math.multiplyExact(metadataPixels, 3L)), 4L)
            return Math.addExact(payload, Math.addExact(4096L, Math.multiplyExact(passCount.toLong(), 128L)))
        }
    }
}
