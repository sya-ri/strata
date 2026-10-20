package dev.s7a.strata.runtime.web

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage

/**
 * Host-resolved asynchronous player skin; no network access occurs during UI evaluation.
 */
public sealed interface WebPlayerSkin {
    /**
     * Profile lookup or image decoding is pending.
     */
    public data object Loading : WebPlayerSkin

    /**
     * Lookup failed; the component presents its declared failure slot.
     */
    public data object Failed : WebPlayerSkin

    /**
     * Immutable normalized skin; hosts must convert legacy layouts before publishing.
     */
    public data class Ready(
        public val image: DrawImage,
    ) : WebPlayerSkin {
        init {
            require(image.size == IntSize(64, 64)) { "Web skins must be normalized 64 by 64 images." }
        }
    }
}
