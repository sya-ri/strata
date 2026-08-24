package dev.s7a.strata.runtime.minecraft.fabric

/**
 * Detached nine-slice metadata decoded by one exact Minecraft adapter.
 *
 * The value contains only portable integers and a center-mode flag, retains no resource or native metadata object, and is safe to consume after the source resource is released.
 */
internal class FabricMinecraftGuiScaling internal constructor(
    @get:JvmSynthetic
    internal val width: Int,
    @get:JvmSynthetic
    internal val height: Int,
    @get:JvmSynthetic
    internal val borderLeft: Int,
    @get:JvmSynthetic
    internal val borderTop: Int,
    @get:JvmSynthetic
    internal val borderRight: Int,
    @get:JvmSynthetic
    internal val borderBottom: Int,
    @get:JvmSynthetic
    internal val stretchesInner: Boolean,
)
