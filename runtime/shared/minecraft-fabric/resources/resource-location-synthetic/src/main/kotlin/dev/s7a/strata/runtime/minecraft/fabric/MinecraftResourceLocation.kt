@file:JvmSynthetic

package dev.s7a.strata.runtime.minecraft.fabric

import net.minecraft.resources.ResourceLocation

/**
 * Mapped resource identifiers hidden from the Java-facing adapter surface.
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
