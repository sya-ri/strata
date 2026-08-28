@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.ScrollStateObserver
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.TextWrap

/**
 * Current owner-thread viewport presentation for one retained multiline editor.
 *
 * Only numeric pan, caret-follow intent, current maximum line width, and the last rendered vertical cell are retained.
 * Caller state, layout, renderer, and observers are borrowed for each operation and never retained.
 * External scroll changes cancel caret following; frame measurement synchronizes the rendered cell after origin-suppressed changes.
 */
internal class MinecraftTextAreaViewportState {
    /**
     * Whether the next measure follows an insertion position changed by editing or navigation.
     */
    @get:JvmSynthetic
    @set:JvmSynthetic
    internal var followCaret: Boolean = false

    /**
     * Non-negative logical horizontal displacement, always zero for wrapped layouts.
     */
    @get:JvmSynthetic
    internal var horizontalOffset: Int = 0
        private set

    private var maximumLineWidth = 0
    private var verticalCell = 0

    /**
     * Drops derived layout width and pending following when the owning editor detaches.
     * Numeric pan is preserved until a state transfer or current-layout clamp explicitly replaces it.
     */
    @JvmSynthetic
    internal fun clear() {
        maximumLineWidth = 0
        followCaret = false
    }

    /**
     * Discards the previous state's horizontal pan, or enforces a newly wrapped presentation.
     */
    @JvmSynthetic
    internal fun resetHorizontal() {
        horizontalOffset = 0
    }

    /**
     * Records the actual rendered vertical cell after attachment or origin-suppressed geometry changes.
     *
     * @param offset current non-negative caller scroll offset.
     */
    @JvmSynthetic
    internal fun synchronize(offset: Double) {
        verticalCell = offset.toInt()
    }

    /**
     * Records a scroll event and reports whether its integer drawing cell changed.
     *
     * @param offset current non-negative caller scroll offset.
     * @return true only when painting uses a different integer displacement.
     */
    @JvmSynthetic
    internal fun scrolled(offset: Double): Boolean {
        val next = offset.toInt()
        if (verticalCell == next) return false
        verticalCell = next
        return true
    }

    /**
     * Replaces the one current width summary and clamps pan after text, font, or viewport changes.
     *
     * @param layout newly created current layout, borrowed without retention.
     * @param settings current immutable editor settings.
     * @param caret current composed insertion position.
     */
    @JvmSynthetic
    internal fun layoutChanged(
        layout: MinecraftTextLayout,
        settings: MinecraftTextAreaConfiguration,
        caret: IntOffset,
    ) {
        maximumLineWidth = layout.lines.maxOfOrNull { it.run.size.width } ?: 0
        clampHorizontal(settings, caret.x)
    }

    /**
     * Applies a pending caret-follow request once, after the editor publishes its current vertical geometry.
     *
     * @param settings current settings whose caller-owned scroll state receives derived geometry.
     * @param origin this editor's live observer token, excluded from its own synchronous notifications.
     * @param caret current composed insertion position, including soft-wrap affinity.
     */
    @JvmSynthetic
    internal fun follow(
        settings: MinecraftTextAreaConfiguration,
        origin: ScrollStateObserver,
        caret: IntOffset,
    ) {
        val scroll = settings.state.scrollState
        val offset = scroll.metrics.offset
        val next =
            when {
                caret.y.toDouble() < offset -> caret.y.toDouble()
                offset + settings.innerSize.height < caret.y.toDouble() + 9.0 -> caret.y.toDouble() + 9.0 - settings.innerSize.height
                else -> offset
            }
        scroll.scrollTo(maxOf(0.0, next), origin)
        followHorizontal(settings, caret.x)
        followCaret = false
    }

    private fun followHorizontal(
        settings: MinecraftTextAreaConfiguration,
        caret: Int,
    ) {
        if (settings.policy.wrap != TextWrap.None || caret < 0) return
        horizontalOffset =
            when {
                caret < horizontalOffset -> caret
                horizontalOffset.toLong() + settings.innerSize.width <= caret.toLong() -> (caret.toLong() - settings.innerSize.width + 1L).toInt()
                else -> horizontalOffset
            }
    }

    /**
     * Clamps current pan after geometry or insertion-position changes without rebuilding the layout width summary.
     *
     * @param settings current immutable viewport and wrapping policy.
     * @param caret current composed horizontal insertion position.
     */
    @JvmSynthetic
    internal fun clampHorizontal(
        settings: MinecraftTextAreaConfiguration,
        caret: Int,
    ) {
        if (settings.policy.wrap != TextWrap.None) {
            horizontalOffset = 0
            return
        }
        val width = maxOf(caret, maximumLineWidth)
        val maximum = maxOf(0L, width.toLong() - settings.innerSize.width + 1L).toInt()
        horizontalOffset = horizontalOffset.coerceIn(0, maximum)
    }
}
