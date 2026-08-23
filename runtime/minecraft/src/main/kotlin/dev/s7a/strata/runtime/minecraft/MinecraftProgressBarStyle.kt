package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.NineSliceCenterMode
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PaintScope

/**
 * Immutable version-profile strategy for painting one determinate progress bar.
 *
 * Implementations retain only platform-neutral image snapshots and may therefore be shared safely by retained elements on the profile owner thread.
 */
internal sealed interface MinecraftProgressBarStyle {
    /**
     * Paints the background and completed portion into the complete measured [PaintScope.size].
     *
     * @param scope active node-local paint scope.
     * @param progress finite normalized completed fraction.
     */
    fun paint(
        scope: PaintScope,
        progress: Double,
    )

    /**
     * Bundle-era tiled nine-slice progress treatment.
     *
     * @property border immutable 12 by 12 border sprite.
     * @property fill immutable 6 by 6 incomplete-fill sprite.
     * @property full immutable 6 by 6 completed-fill sprite.
     */
    class Bundle(
        private val border: DrawImage,
        private val fill: DrawImage,
        private val full: DrawImage,
    ) : MinecraftProgressBarStyle {
        init {
            require(border.size == IntSize(12, 12)) { "ProgressBar border sprite must be 12 by 12 pixels." }
            require(fill.size == IntSize(6, 6)) { "ProgressBar fill sprite must be 6 by 6 pixels." }
            require(full.size == IntSize(6, 6)) { "Completed ProgressBar fill sprite must be 6 by 6 pixels." }
        }

        override fun paint(
            scope: PaintScope,
            progress: Double,
        ) {
            val innerWidth = scope.size.width - BORDER * 2
            val completedWidth = (innerWidth.toDouble() * progress).toInt()
            if (0 < completedWidth) {
                val destination = IntRect(BORDER, BORDER, BORDER + completedWidth, scope.size.height - BORDER)
                paintMinecraftNineSlice(
                    MinecraftRectPaintScope(scope, destination),
                    if (progress == 1.0) full else fill,
                    Insets.all(FILL_BORDER),
                    NineSliceCenterMode.Tiled,
                )
            }
            paintMinecraftNineSlice(scope, border, Insets.all(BORDER), NineSliceCenterMode.Tiled)
        }

        private companion object {
            private const val BORDER = 2
            private const val FILL_BORDER = 2
        }
    }

    /**
     * Legacy fixed-source horizontal progress treatment.
     *
     * @property background immutable complete background sprite.
     * @property fill immutable equal-sized fill sprite cropped by progress.
     */
    class Horizontal(
        private val background: DrawImage,
        private val fill: DrawImage,
    ) : MinecraftProgressBarStyle {
        init {
            require(background.size == fill.size) { "Horizontal ProgressBar sprites must have equal sizes." }
        }

        override fun paint(
            scope: PaintScope,
            progress: Double,
        ) {
            val source = IntRect(0, 0, background.size.width, background.size.height)
            val destination = IntRect(0, 0, scope.size.width, scope.size.height)
            scope.blitImage(background, source, destination)

            val completedWidth = (scope.size.width.toDouble() * progress).toInt()
            if (0 < completedWidth) {
                val completedSourceWidth =
                    (background.size.width.toLong() * completedWidth.toLong() / scope.size.width.toLong())
                        .toInt()
                        .coerceAtLeast(1)
                scope.blitImage(
                    fill,
                    IntRect(0, 0, completedSourceWidth, fill.size.height),
                    IntRect(0, 0, completedWidth, scope.size.height),
                )
            }
        }
    }
}
