package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.resources.metadata.gui.GuiSpriteScaling

/**
 * Copies Minecraft's native image into detached straight-ARGB pixels.
 */
@JvmSynthetic
internal fun copyFabricMinecraftArgbPixels(image: NativeImage): IntArray = image.getPixels()

/**
 * Indicates that the supported tooltip frame metadata stretches its center.
 *
 * This remains a property so the internal adapter bridge does not expose a Java constant field.
 */
@get:JvmSynthetic
@Suppress("MayBeConstant")
internal val minecraftTooltipFrameStretchesInner: Boolean = true

/**
 * Reads the explicit center mode from Minecraft's nine-slice metadata.
 */
@JvmSynthetic
internal fun minecraftNineSliceStretchesInner(scaling: GuiSpriteScaling.NineSlice): Boolean = scaling.stretchInner()
