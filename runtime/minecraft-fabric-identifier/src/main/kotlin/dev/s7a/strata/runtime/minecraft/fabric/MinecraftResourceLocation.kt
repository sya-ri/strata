@file:JvmSynthetic

package dev.s7a.strata.runtime.minecraft.fabric

import net.minecraft.resources.Identifier

/**
 * Names the native Minecraft resource identifier used by releases after the ResourceLocation rename.
 *
 * The alias is compile-time only, carries no ownership, and is consumed exclusively by compatible Fabric adapter source roots.
 */
internal typealias MinecraftResourceLocation = Identifier
