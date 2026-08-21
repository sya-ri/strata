package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PaintScope

/**
 * Paints one validated immutable image with Minecraft's dimension-specialized nine-slice command order.
 *
 * The caller owns validation and invokes this only on its paint thread.
 * Source pixels are retained only by the emitted draw commands, and no source or destination is mutated.
 *
 * @param scope active retained paint scope whose size is the complete destination.
 * @param image immutable source pixels with nonempty centers after [border].
 * @param border non-negative source borders.
 * @param centerMode typed inner-segment mapping.
 * @throws ArithmeticException when checked segment or tiled destination arithmetic overflows.
 */
@JvmSynthetic
internal fun paintMinecraftNineSlice(
    scope: PaintScope,
    image: DrawImage,
    border: Insets,
    centerMode: MinecraftNineSliceCenterMode,
) {
    if (scope.size.width == 0 || scope.size.height == 0) return
    val left = minOf(border.left, scope.size.width / 2)
    val right = minOf(border.right, scope.size.width / 2)
    val top = minOf(border.top, scope.size.height / 2)
    val bottom = minOf(border.bottom, scope.size.height / 2)
    when {
        scope.size == image.size -> blit(scope, image, IntRect(0, 0, image.size.width, image.size.height), IntRect(0, 0, scope.size.width, scope.size.height))
        scope.size.height == image.size.height -> paintHorizontal(scope, image, centerMode, left, right)
        scope.size.width == image.size.width -> paintVertical(scope, image, centerMode, top, bottom)
        else -> paintGrid(scope, image, centerMode, left, top, right, bottom)
    }
}

private fun paintHorizontal(
    scope: PaintScope,
    image: DrawImage,
    centerMode: MinecraftNineSliceCenterMode,
    left: Int,
    right: Int,
) {
    val destinationRight = Math.subtractExact(scope.size.width, right)
    val sourceRight = Math.subtractExact(image.size.width, right)
    blit(scope, image, IntRect(0, 0, left, image.size.height), IntRect(0, 0, left, scope.size.height))
    inner(scope, image, centerMode, IntRect(left, 0, sourceRight, image.size.height), IntRect(left, 0, destinationRight, scope.size.height))
    blit(
        scope,
        image,
        IntRect(sourceRight, 0, image.size.width, image.size.height),
        IntRect(destinationRight, 0, scope.size.width, scope.size.height),
    )
}

private fun paintVertical(
    scope: PaintScope,
    image: DrawImage,
    centerMode: MinecraftNineSliceCenterMode,
    top: Int,
    bottom: Int,
) {
    val destinationBottom = Math.subtractExact(scope.size.height, bottom)
    val sourceBottom = Math.subtractExact(image.size.height, bottom)
    blit(scope, image, IntRect(0, 0, image.size.width, top), IntRect(0, 0, scope.size.width, top))
    inner(scope, image, centerMode, IntRect(0, top, image.size.width, sourceBottom), IntRect(0, top, scope.size.width, destinationBottom))
    blit(
        scope,
        image,
        IntRect(0, sourceBottom, image.size.width, image.size.height),
        IntRect(0, destinationBottom, scope.size.width, scope.size.height),
    )
}

@Suppress("LongParameterList")
private fun paintGrid(
    scope: PaintScope,
    image: DrawImage,
    centerMode: MinecraftNineSliceCenterMode,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
) {
    val sourceRight = Math.subtractExact(image.size.width, right)
    val sourceBottom = Math.subtractExact(image.size.height, bottom)
    val destinationRight = Math.subtractExact(scope.size.width, right)
    val destinationBottom = Math.subtractExact(scope.size.height, bottom)
    blit(scope, image, IntRect(0, 0, left, top), IntRect(0, 0, left, top))
    inner(scope, image, centerMode, IntRect(left, 0, sourceRight, top), IntRect(left, 0, destinationRight, top))
    blit(scope, image, IntRect(sourceRight, 0, image.size.width, top), IntRect(destinationRight, 0, scope.size.width, top))
    inner(scope, image, centerMode, IntRect(0, top, left, sourceBottom), IntRect(0, top, left, destinationBottom))
    inner(scope, image, centerMode, IntRect(left, top, sourceRight, sourceBottom), IntRect(left, top, destinationRight, destinationBottom))
    inner(
        scope,
        image,
        centerMode,
        IntRect(sourceRight, top, image.size.width, sourceBottom),
        IntRect(destinationRight, top, scope.size.width, destinationBottom),
    )
    blit(scope, image, IntRect(0, sourceBottom, left, image.size.height), IntRect(0, destinationBottom, left, scope.size.height))
    inner(
        scope,
        image,
        centerMode,
        IntRect(left, sourceBottom, sourceRight, image.size.height),
        IntRect(left, destinationBottom, destinationRight, scope.size.height),
    )
    blit(
        scope,
        image,
        IntRect(sourceRight, sourceBottom, image.size.width, image.size.height),
        IntRect(destinationRight, destinationBottom, scope.size.width, scope.size.height),
    )
}

private fun inner(
    scope: PaintScope,
    image: DrawImage,
    centerMode: MinecraftNineSliceCenterMode,
    source: IntRect,
    destination: IntRect,
) {
    if (source.width == 0 || source.height == 0) return
    if (destination.width == 0 || destination.height == 0) return
    when (centerMode) {
        MinecraftNineSliceCenterMode.Stretched -> blit(scope, image, source, destination)
        MinecraftNineSliceCenterMode.Tiled -> tile(scope, image, source, destination)
    }
}

private fun tile(
    scope: PaintScope,
    image: DrawImage,
    source: IntRect,
    destination: IntRect,
) {
    var destinationTop = destination.top
    while (destinationTop < destination.bottom) {
        val height = minOf(source.height, Math.subtractExact(destination.bottom, destinationTop))
        var destinationLeft = destination.left
        while (destinationLeft < destination.right) {
            val width = minOf(source.width, Math.subtractExact(destination.right, destinationLeft))
            blit(
                scope,
                image,
                IntRect(source.left, source.top, Math.addExact(source.left, width), Math.addExact(source.top, height)),
                IntRect(destinationLeft, destinationTop, Math.addExact(destinationLeft, width), Math.addExact(destinationTop, height)),
            )
            destinationLeft = Math.addExact(destinationLeft, source.width)
        }
        destinationTop = Math.addExact(destinationTop, source.height)
    }
}

private fun blit(
    scope: PaintScope,
    image: DrawImage,
    source: IntRect,
    destination: IntRect,
) {
    if (source.width == 0 || source.height == 0) return
    if (destination.width == 0 || destination.height == 0) return
    scope.blitImage(image, source, destination)
}
