@file:JvmSynthetic

package dev.s7a.strata.runtime.minecraft.fabric

import net.minecraft.IdentifierException

/**
 * Names the native invalid-resource exception used by releases after the ResourceLocation rename.
 */
internal typealias MinecraftResourceLocationException = IdentifierException
