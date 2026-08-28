package dev.s7a.strata.runtime.minecraft.font

import dev.s7a.strata.resource.ResourceId

/**
 * Detached immutable bytes for one selected resource.
 *
 * @property id structural resource identifier.
 * @property source diagnostic pack label.
 * @param bytes bytes copied before storage.
 */
internal class FontResource(
    val id: ResourceId,
    val source: String,
    bytes: ByteArray,
) {
    private val bytes: ByteArray = bytes.copyOf()

    /**
     * Encoded payload size available without copying or retaining additional source state.
     */
    val size: Int
        get() = bytes.size

    /**
     * Returns an independent byte copy for synchronous decoding.
     */
    fun copyBytes(): ByteArray = bytes.copyOf()

    /**
     * Checks recognizable PNG content before retaining a bitmap provider, without copying encoded or expanded bytes.
     * Non-PNG custom decoder payloads remain subject to that decoder's bounded allocation contract.
     *
     * @param limits immutable snapshot input ceilings.
     * @param consume loader-owned aggregate accounting callback, which may reject observed work.
     * @return whether PNG content was recognized and checked.
     * @throws IllegalArgumentException when PNG content is malformed or exceeds a ceiling.
     */
    fun checkBitmap(
        limits: MinecraftFontLoadLimits,
        consume: (Long) -> Unit,
    ): Boolean = FontPngBounds.check(bytes, limits.copy(maxImageBytes = minOf(limits.maxImageBytes, limits.maxBitmapSheetBytes)), consume)
}
