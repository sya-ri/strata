@file:JvmSynthetic

package dev.s7a.strata.runtime.minecraft.fabric

import net.minecraft.resources.Identifier

/**
 * Names the native Minecraft resource identifier used by releases after the ResourceLocation rename.
 *
 * The alias is compile-time only, carries no ownership, and is consumed exclusively by compatible Fabric adapter source roots.
 */
internal typealias MinecraftResourceLocation = Identifier

/**
 * Creates a mapped Minecraft resource identifier or throws for an invalid namespace or path.
 */
internal fun minecraftResourceLocation(
    namespace: String,
    path: String,
): MinecraftResourceLocation = Identifier.fromNamespaceAndPath(namespace, path)

/**
 * Parses a mapped Minecraft resource identifier or throws for invalid input.
 */
internal fun parseMinecraftResourceLocation(value: String): MinecraftResourceLocation = Identifier.parse(value)
