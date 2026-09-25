package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage

/**
 * Normalizes an explicitly supplied same-lease snapshot without reading native pixels.
 *
 * The input is immutable and externally owned; the result is immutable and detached, with nearest sampling at pixel centers.
 * Calls are render-thread confined by the native capture owner and retain no historical images.
 *
 * @param snapshot optional pixels guaranteed by the lease to describe its exact native contents.
 * @param sourceSize complete native source extent.
 * @param targetSize positive physical destination extent.
 * @param origin identifies the image row represented by source row zero.
 * @return normalized target pixels, or null when the producer supplied no snapshot.
 * @throws IllegalArgumentException when a supplied snapshot disagrees with its lease extent.
 * @throws ArithmeticException when allocating the checked destination area would overflow.
 */
@JvmSynthetic
internal fun normalizeCanvasSnapshot(
    snapshot: DrawImage?,
    sourceSize: IntSize,
    targetSize: IntSize,
    origin: MinecraftCanvasTextureOrigin,
): DrawImage? {
    if (snapshot == null) return null
    require(snapshot.size == sourceSize) { "A Canvas snapshot must match the leased native image extent." }
    require(0 < sourceSize.width && 0 < sourceSize.height) { "A Canvas source must have a positive extent." }
    if (sourceSize == targetSize && origin == MinecraftCanvasTextureOrigin.TopLeft) return snapshot
    val pixels = IntArray(Math.multiplyExact(targetSize.width, targetSize.height))
    for (y in 0 until targetSize.height) {
        val sampledY = ((y.toLong() * 2L + 1L) * sourceSize.height / (targetSize.height.toLong() * 2L)).toInt()
        val sourceY = if (origin == MinecraftCanvasTextureOrigin.TopLeft) sampledY else sourceSize.height - sampledY - 1
        for (x in 0 until targetSize.width) {
            val sourceX = ((x.toLong() * 2L + 1L) * sourceSize.width / (targetSize.width.toLong() * 2L)).toInt()
            pixels[y * targetSize.width + x] = snapshot.argbAt(sourceX, sourceY)
        }
    }
    return createDrawImage(targetSize, pixels)
}
