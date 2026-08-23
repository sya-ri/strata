package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.NineSliceCenterMode
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.RootOverlayPaintScope

/**
 * Immutable version-profile strategy for painting a tooltip background and frame.
 */
internal sealed interface MinecraftTooltipStyle {
    /**
     * Paints one complete tooltip decoration at [bounds].
     *
     * @param scope active root-overlay paint scope.
     * @param bounds complete six-pixel-padded tooltip bounds.
     */
    fun paint(
        scope: RootOverlayPaintScope,
        bounds: IntRect,
    )

    /**
     * Resource-backed tooltip treatment used by Minecraft releases with GUI sprites.
     *
     * @property background immutable 100 by 100 tiled background nine-slice.
     * @property frame immutable 100 by 100 stretched frame nine-slice.
     */
    class Sprites(
        private val background: DrawImage,
        private val frame: DrawImage,
    ) : MinecraftTooltipStyle {
        init {
            require(background.size == IntSize(100, 100)) { "Tooltip background sprite must be 100 by 100 pixels." }
            require(frame.size == IntSize(100, 100)) { "Tooltip frame sprite must be 100 by 100 pixels." }
        }

        override fun paint(
            scope: RootOverlayPaintScope,
            bounds: IntRect,
        ) {
            paintMinecraftNineSlice(
                MinecraftRectPaintScope(scope, bounds),
                background,
                Insets.all(BACKGROUND_BORDER),
                NineSliceCenterMode.Tiled,
            )
            paintMinecraftNineSlice(
                MinecraftRectPaintScope(scope, bounds),
                frame,
                Insets.all(FRAME_BORDER),
                NineSliceCenterMode.Stretched,
            )
        }

        private companion object {
            private const val BACKGROUND_BORDER = 9
            private const val FRAME_BORDER = 10
        }
    }

    /**
     * Code-defined tooltip treatment used by Minecraft releases before tooltip sprites existed.
     *
     * @property backgroundColor native fill and one-pixel outside-edge color.
     * @property borderTop native top border color.
     * @property borderBottom native bottom border color.
     */
    class Legacy(
        private val backgroundColor: ArgbColor,
        private val borderTop: ArgbColor,
        private val borderBottom: ArgbColor,
    ) : MinecraftTooltipStyle {
        override fun paint(
            scope: RootOverlayPaintScope,
            bounds: IntRect,
        ) {
            scope.fillRectangle(IntRect(bounds.left, bounds.top - 1, bounds.right, bounds.top), backgroundColor)
            scope.fillRectangle(IntRect(bounds.left, bounds.bottom, bounds.right, bounds.bottom + 1), backgroundColor)
            scope.fillRectangle(bounds, backgroundColor)
            scope.fillRectangle(IntRect(bounds.left - 1, bounds.top, bounds.left, bounds.bottom), backgroundColor)
            scope.fillRectangle(IntRect(bounds.right, bounds.top, bounds.right + 1, bounds.bottom), backgroundColor)

            scope.fillRectangle(IntRect(bounds.left, bounds.top, bounds.right, bounds.top + 1), borderTop)
            scope.fillRectangle(IntRect(bounds.left, bounds.bottom - 1, bounds.right, bounds.bottom), borderBottom)
            val sideHeight = bounds.height - 2
            for (offset in 0 until sideHeight) {
                val color = interpolate(borderTop, borderBottom, offset, sideHeight)
                val y = bounds.top + 1 + offset
                scope.fillRectangle(IntRect(bounds.left, y, bounds.left + 1, y + 1), color)
                scope.fillRectangle(IntRect(bounds.right - 1, y, bounds.right, y + 1), color)
            }
        }

        private fun interpolate(
            start: ArgbColor,
            end: ArgbColor,
            index: Int,
            count: Int,
        ): ArgbColor {
            if (count <= 1) return start
            val denominator = count - 1

            fun channel(shift: Int): Int {
                val startChannel = start.value ushr shift and 0xFF
                val endChannel = end.value ushr shift and 0xFF
                return (startChannel * (denominator - index) + endChannel * index) / denominator
            }
            return ArgbColor(
                channel(24) shl 24 or
                    (channel(16) shl 16) or
                    (channel(8) shl 8) or
                    channel(0),
            )
        }
    }
}
