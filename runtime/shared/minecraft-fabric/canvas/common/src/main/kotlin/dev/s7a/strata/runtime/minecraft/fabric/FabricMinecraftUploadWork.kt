package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage

/**
 * Render-thread cumulative payload counters for successful RGBA8 uploads and resolved sampled fallbacks.
 * This screen-owned diagnostic state retains no pixels, commands, native objects, or history.
 * GPU-generated output is excluded from CPU upload bytes; lookup metadata is counted separately.
 */
internal class FabricMinecraftUploadWork {
    private var sourceUploadByteCount = 0L
    private var rasterUploadByteCount = 0L
    private var samplingUploadByteCount = 0L
    private var tintFallbackCount = 0L
    private var alphaCutoffFallbackCount = 0L
    private var otherIneligibleFallbackCount = 0L

    /**
     * Records a newly uploaded immutable source, excluding device-wide sharing and hits.
     */
    @JvmSynthetic
    internal fun source(image: DrawImage) {
        sourceUploadByteCount = Math.addExact(sourceUploadByteCount, bytes(image.size))
    }

    /**
     * Records the payload actually transferred for a changed portable generation.
     */
    @JvmSynthetic
    internal fun portable(image: FabricMinecraftPortableImage) {
        val sampling = image.sampling
        if (sampling == null) {
            rasterUploadByteCount = Math.addExact(rasterUploadByteCount, bytes(image.physicalSize))
        } else {
            samplingUploadByteCount = Math.addExact(samplingUploadByteCount, bytes(sampling.indices.size))
        }
    }

    /**
     * Records borrow-specific fallback reasons; tint takes precedence over cutoff when both apply.
     */
    @JvmSynthetic
    internal fun fallback(inputs: FabricMinecraftFrameInputs) {
        tintFallbackCount = Math.addExact(tintFallbackCount, inputs.tintFallbackImages)
        alphaCutoffFallbackCount = Math.addExact(alphaCutoffFallbackCount, inputs.alphaCutoffFallbackImages)
        otherIneligibleFallbackCount = Math.addExact(otherIneligibleFallbackCount, inputs.ineligibleSampledImages - inputs.tintFallbackImages - inputs.alphaCutoffFallbackImages)
    }

    private fun bytes(size: IntSize): Long = Math.multiplyExact(Math.multiplyExact(size.width.toLong(), size.height.toLong()), 4L)
}
