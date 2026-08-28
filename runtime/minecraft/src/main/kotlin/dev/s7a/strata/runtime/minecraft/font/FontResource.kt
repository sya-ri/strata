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
     * Returns an independent byte copy for synchronous decoding.
     */
    fun copyBytes(): ByteArray = bytes.copyOf()
}
