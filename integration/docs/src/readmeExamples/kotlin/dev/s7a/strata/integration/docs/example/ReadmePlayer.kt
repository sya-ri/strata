package dev.s7a.strata.integration.docs.example

import dev.s7a.strata.component.PlayerSkinSource

/**
 * Immutable presentation data supplied by the application to every README example.
 * The caller owns the detached skin pixels; examples only read these values on the screen owner thread.
 *
 * @property name visible player name.
 * @property role short secondary label.
 * @property skin immutable offline player skin.
 */
internal data class ReadmePlayer(
    val name: String,
    val role: String,
    val skin: PlayerSkinSource.Pixels,
)
