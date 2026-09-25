package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.runtime.minecraft.fabric.MinecraftCanvasContext

/**
 * Checks the borrowed native target's actual depth capability during its render callback.
 */
internal fun hasMinecraftCanvasTestDepth(context: MinecraftCanvasContext): Boolean = context.target.hasDepth()
