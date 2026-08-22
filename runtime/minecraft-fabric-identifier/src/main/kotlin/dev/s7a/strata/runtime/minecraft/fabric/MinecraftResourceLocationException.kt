@file:JvmSynthetic

package dev.s7a.strata.runtime.minecraft.fabric

import net.minecraft.IdentifierException

/**
 * Names the native invalid-resource exception used by releases after the ResourceLocation rename.
 *
 * The alias is compile-time only and lets shared parsing code preserve the native failure as its cause.
 */
internal typealias MinecraftResourceLocationException = IdentifierException
