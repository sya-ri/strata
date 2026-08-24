package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.render.DrawImage

/**
 * Detached widget image set extracted from either individual GUI sprites or a legacy atlas.
 *
 * Every image is immutable and owned by the returned snapshot; the value retains no Minecraft resource, stream, or native image.
 */
internal class FabricMinecraftWidgetImages internal constructor(
    @get:JvmSynthetic
    internal val checkbox: DrawImage,
    @get:JvmSynthetic
    internal val checkboxHighlighted: DrawImage,
    @get:JvmSynthetic
    internal val checkboxSelected: DrawImage,
    @get:JvmSynthetic
    internal val checkboxSelectedHighlighted: DrawImage,
    @get:JvmSynthetic
    internal val slider: DrawImage,
    @get:JvmSynthetic
    internal val sliderBorder: Int,
    @get:JvmSynthetic
    internal val sliderHighlighted: DrawImage,
    @get:JvmSynthetic
    internal val sliderHighlightedBorder: Int,
    @get:JvmSynthetic
    internal val sliderHandle: DrawImage,
    @get:JvmSynthetic
    internal val sliderHandleHighlighted: DrawImage,
    @get:JvmSynthetic
    internal val textFieldNormal: DrawImage,
    @get:JvmSynthetic
    internal val textFieldHighlighted: DrawImage,
    @get:JvmSynthetic
    internal val buttonNormal: DrawImage,
    @get:JvmSynthetic
    internal val buttonNormalBorder: Int,
    @get:JvmSynthetic
    internal val buttonHighlighted: DrawImage,
    @get:JvmSynthetic
    internal val buttonHighlightedBorder: Int,
    @get:JvmSynthetic
    internal val buttonDisabled: DrawImage,
    @get:JvmSynthetic
    internal val buttonDisabledBorder: Int,
)
