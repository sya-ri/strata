@file:JvmSynthetic

package dev.s7a.strata.runtime.minecraft.fabric

import net.minecraft.ResourceLocationException

/**
 * Names the native invalid-resource exception used by Minecraft 1.21.3.
 *
 * The alias is compile-time only and lets shared parsing code preserve the native failure as its cause.
 */
internal typealias MinecraftResourceLocationException = ResourceLocationException
