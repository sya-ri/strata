@file:JvmSynthetic

package dev.s7a.strata.runtime.minecraft.fabric

import net.minecraft.client.resources.metadata.gui.GuiSpriteScaling

/**
 * Indicates that native tooltip-frame metadata tiles its center.
 */
@get:JvmSynthetic
@Suppress("MayBeConstant")
internal val minecraftTooltipFrameStretchesInner: Boolean = false

/**
 * Reads the center mode of native fixed tiled nine-slice metadata.
 */
@JvmSynthetic
@Suppress("FunctionOnlyReturningConstant", "UnusedParameter")
internal fun minecraftNineSliceStretchesInner(scaling: GuiSpriteScaling.NineSlice): Boolean = false
