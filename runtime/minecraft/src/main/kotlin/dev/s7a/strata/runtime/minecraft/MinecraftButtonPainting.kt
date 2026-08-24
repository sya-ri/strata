package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.NineSliceCenterMode
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.render.PaintScope

/**
 * Paints one profile button sprite at an arbitrary validated width.
 */
internal fun paintMinecraftButtonSprite(
    scope: PaintScope,
    sprite: MinecraftButtonSpriteSnapshot,
    width: Int,
) {
    val border = minOf(sprite.border, width / 2)
    val sourceHeight = 20
    if (border == 0) {
        val bounds = IntRect(0, 0, width, sourceHeight)
        scope.blitImage(sprite.image, bounds, bounds)
        return
    }
    scope.blitImage(sprite.image, IntRect(0, 0, border, sourceHeight), IntRect(0, 0, border, sourceHeight))
    if (border * 2 < width) {
        val centerSource =
            when (sprite.centerMode) {
                NineSliceCenterMode.Tiled -> IntRect(border, 0, width - border, sourceHeight)
                NineSliceCenterMode.Stretched -> IntRect(border, 0, 200 - border, sourceHeight)
            }
        scope.blitImage(sprite.image, centerSource, IntRect(border, 0, width - border, sourceHeight))
    }
    scope.blitImage(
        sprite.image,
        IntRect(200 - border, 0, 200, sourceHeight),
        IntRect(width - border, 0, width, sourceHeight),
    )
}
