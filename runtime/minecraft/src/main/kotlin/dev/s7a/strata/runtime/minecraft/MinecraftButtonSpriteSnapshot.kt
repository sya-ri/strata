package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Immutable internal Button sprite policy retained by one complete profile.
 *
 * @property image exact immutable 200 by 20 sprite pixels.
 * @property border positive horizontal nine-slice border that leaves a nonempty source center; each component validates its destination width.
 * @property centerMode typed center sampling policy.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftButtonSpriteSnapshot private constructor(
    @get:JvmSynthetic
    internal val image: DrawImage,
    @get:JvmSynthetic
    internal val border: Int,
    @get:JvmSynthetic
    internal val centerMode: MinecraftNineSliceCenterMode,
) {
    /**
     * Owns the synthetic constructor bridge for immutable sprite snapshots.
     */
    companion object {
        /**
         * Creates one validated sprite policy without exposing its constructor to Java.
         *
         * @param image immutable sprite pixels.
         * @param border validated horizontal border width.
         * @param centerMode typed center sampling policy.
         * @return an immutable sprite snapshot.
         */
        @JvmSynthetic
        internal fun create(
            image: DrawImage,
            border: Int,
            centerMode: MinecraftNineSliceCenterMode,
        ): MinecraftButtonSpriteSnapshot = MinecraftButtonSpriteSnapshot(image, border, centerMode)
    }
}
