@file:JvmSynthetic

package dev.s7a.strata.runtime.minecraft.fabric

import net.minecraft.resources.ResourceLocation

/**
 * Names the native Minecraft resource location used by Minecraft 1.21.4.
 *
 * The alias is compile-time only, carries no ownership, and is consumed exclusively by the compatible shared Fabric adapter sources.
 */
internal typealias MinecraftResourceLocation = ResourceLocation

/**
 * Creates a mapped Minecraft resource identifier or throws for an invalid namespace or path.
 */
internal fun minecraftResourceLocation(
    namespace: String,
    path: String,
): MinecraftResourceLocation = ResourceLocation.fromNamespaceAndPath(namespace, path)

/**
 * Parses a mapped Minecraft resource identifier or throws for invalid input.
 */
internal fun parseMinecraftResourceLocation(value: String): MinecraftResourceLocation = ResourceLocation.parse(value)
