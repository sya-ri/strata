package dev.s7a.strata.integration.minecraft.fabric

import net.minecraft.resources.ResourceLocation

/** Mapped Minecraft resource identifier used by the legacy loaded-client acceptance suite. */
internal typealias MinecraftTestResourceLocation = ResourceLocation

/** Creates a mapped Minecraft resource identifier for integration-only registry inspection. */
internal fun minecraftTestResourceLocation(
    namespace: String,
    path: String,
): MinecraftTestResourceLocation = ResourceLocation(namespace, path)
