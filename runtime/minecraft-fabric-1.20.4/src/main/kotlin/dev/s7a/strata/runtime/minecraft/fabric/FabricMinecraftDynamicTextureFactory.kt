package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.client.resources.metadata.gui.GuiSpriteScaling

/**
 * Constructs Minecraft's unnamed dynamic texture for the caller-owned [image].
 */
internal fun createFabricMinecraftDynamicTexture(image: NativeImage): DynamicTexture = DynamicTexture(image)

/**
 * Indicates that Minecraft 1.20.4 nine-slice metadata tiles its center.
 */
@get:JvmSynthetic
@Suppress("MayBeConstant")
internal val minecraftTooltipFrameStretchesInner: Boolean = false

/**
 * Reads the center mode represented by Minecraft 1.20.4's fixed tiled nine-slice record.
 */
@JvmSynthetic
@Suppress("FunctionOnlyReturningConstant", "UnusedParameter")
internal fun minecraftNineSliceStretchesInner(scaling: GuiSpriteScaling.NineSlice): Boolean = false
