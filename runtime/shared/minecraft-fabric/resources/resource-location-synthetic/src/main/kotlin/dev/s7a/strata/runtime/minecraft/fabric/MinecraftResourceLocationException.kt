@file:JvmSynthetic

package dev.s7a.strata.runtime.minecraft.fabric

import net.minecraft.ResourceLocationException

/**
 * Mapped invalid-resource exception hidden from the Java-facing adapter surface.
 */
internal typealias MinecraftResourceLocationException = ResourceLocationException
