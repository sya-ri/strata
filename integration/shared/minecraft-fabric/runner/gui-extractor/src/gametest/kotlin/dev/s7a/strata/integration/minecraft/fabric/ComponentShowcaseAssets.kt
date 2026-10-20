package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.PlayerSkinSource

/**
 * Detached active-resource pixels used to construct independent headless and Fabric component definitions.
 */
internal data class ComponentShowcaseAssets(
    val image: ImageSource,
    val playerSkin: PlayerSkinSource,
)
