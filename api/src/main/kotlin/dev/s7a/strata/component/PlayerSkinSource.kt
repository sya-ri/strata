package dev.s7a.strata.component

import dev.s7a.strata.render.DrawImage
import java.util.UUID

/**
 * Platform-neutral source for a player skin used by [PlayerHead].
 *
 * Lookup sources contain no game object and are resolved asynchronously by the active version adapter.
 */
public sealed interface PlayerSkinSource {
    /**
     * Uses one immutable normalized 64 by 64 skin snapshot.
     *
     * @property skin immutable source pixels retained without copying.
     */
    public data class Pixels(
        public val skin: DrawImage,
    ) : PlayerSkinSource

    /**
     * Resolves the active local player's skin.
     */
    public data object CurrentPlayer : PlayerSkinSource

    /**
     * Resolves a player profile and skin from a Minecraft account name.
     *
     * @property value nonblank account name.
     * @throws IllegalArgumentException when [value] is blank.
     */
    public data class Name(
        public val value: String,
    ) : PlayerSkinSource {
        init {
            require(value.isNotBlank()) { "Player skin name must not be blank." }
        }
    }

    /**
     * Resolves a player skin from a profile UUID.
     *
     * @property value immutable profile UUID.
     */
    public data class Uuid(
        public val value: UUID,
    ) : PlayerSkinSource
}
