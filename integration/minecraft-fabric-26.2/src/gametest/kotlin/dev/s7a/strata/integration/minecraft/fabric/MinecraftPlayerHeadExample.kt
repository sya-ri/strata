package dev.s7a.strata.integration.minecraft.fabric

// showcase-source-begin:player-head
import dev.s7a.strata.dsl.Box
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.minecraft.MinecraftScreenDefinition
import dev.s7a.strata.runtime.minecraft.PlayerHead
import dev.s7a.strata.runtime.minecraft.createMinecraftScreenDefinition

/**
 * Builds a reusable player-head screen from a detached skin selected by the version adapter.
 *
 * @param skin immutable 64 by 64 player skin from Minecraft's resource or downloaded-texture path.
 * @return one-shot definition reproducing the Social Interactions 24 by 24 face and hat layers.
 */
internal fun createPlayerHeadScreenDefinition(skin: DrawImage): MinecraftScreenDefinition =
    createMinecraftScreenDefinition("Player head") {
        Box(
            modifier = Modifier.Empty.size(64, 64).background(ArgbColor(0xFF000000.toInt())),
            contentAlignment = Alignment.Center,
        ) {
            PlayerHead(skin)
        }
    }
// showcase-source-end:player-head
