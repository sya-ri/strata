package dev.s7a.strata.runtime.minecraft.fabric

import net.minecraft.resources.ResourceLocation

/**
 * Mapped Minecraft resource identifier type used by this adapter boundary.
 */
internal typealias MinecraftResourceLocation = ResourceLocation

/**
 * Creates a mapped Minecraft resource identifier or throws for an invalid namespace or path.
 */
internal fun minecraftResourceLocation(
    namespace: String,
    path: String,
): MinecraftResourceLocation = ResourceLocation(namespace, path)

/**
 * Parses a mapped Minecraft resource identifier or throws for invalid input.
 */
internal fun parseMinecraftResourceLocation(value: String): MinecraftResourceLocation = ResourceLocation(value)
