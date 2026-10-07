package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.systems.RenderSystem

/**
 * Reads the active device's RGBA source-texture bound after the caller verifies render-thread access.
 */
@JvmSynthetic
internal fun fabricMinecraftMaximumTextureSize(): Int =
    RenderSystem
        .getDevice()
        .deviceInfo.limits
        .maxTextureSizeForFormat(GpuFormat.RGBA8_UNORM)
