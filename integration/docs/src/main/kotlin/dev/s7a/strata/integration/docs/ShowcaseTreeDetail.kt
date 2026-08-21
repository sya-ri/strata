package dev.s7a.strata.integration.docs

import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.layout.Arrangement as LayoutArrangement

/**
 * Exhaustive typed documentation detail attached to a logical component tree node.
 */
internal sealed interface ShowcaseTreeDetail {
    /**
     * A modifier that fills the available parent size.
     */
    data object FillMaxSize : ShowcaseTreeDetail

    /**
     * An explicit logical size.
     */
    data class Size(
        internal val width: Int,
        internal val height: Int,
    ) : ShowcaseTreeDetail

    /**
     * An explicit logical height.
     */
    data class Height(
        internal val value: Int,
    ) : ShowcaseTreeDetail

    /**
     * Uniform logical padding.
     */
    data class Padding(
        internal val all: Int,
    ) : ShowcaseTreeDetail

    /**
     * A background color.
     */
    data class Background(
        internal val color: ArgbColor,
    ) : ShowcaseTreeDetail

    /**
     * A weighted direct-child parent-data modifier.
     */
    data class Weight(
        internal val weight: Float,
        internal val fill: Boolean,
    ) : ShowcaseTreeDetail

    /**
     * A Row direct-child vertical alignment.
     */
    data class RowAlign(
        internal val alignment: VerticalAlignment,
    ) : ShowcaseTreeDetail

    /**
     * A Column direct-child horizontal alignment.
     */
    data class ColumnAlign(
        internal val alignment: HorizontalAlignment,
    ) : ShowcaseTreeDetail

    /**
     * A Box direct-child alignment.
     */
    data class BoxAlign(
        internal val alignment: Alignment,
    ) : ShowcaseTreeDetail

    /**
     * Fixed spacing between linear children.
     */
    data class Spacing(
        internal val value: Int,
    ) : ShowcaseTreeDetail

    /**
     * A Minecraft Scroll logical wheel-displacement multiplier.
     */
    data class ScrollRate(
        internal val value: Int,
    ) : ShowcaseTreeDetail

    /**
     * Whether a Minecraft Slot participates in native hover highlighting.
     */
    data class SlotHighlightable(
        internal val value: Boolean,
    ) : ShowcaseTreeDetail

    /**
     * A linear child arrangement.
     */
    data class Arrangement(
        internal val arrangement: LayoutArrangement,
    ) : ShowcaseTreeDetail

    /**
     * The default Row cross-axis alignment.
     */
    data class RowDefaultAlignment(
        internal val alignment: VerticalAlignment,
    ) : ShowcaseTreeDetail

    /**
     * The default Column cross-axis alignment.
     */
    data class ColumnDefaultAlignment(
        internal val alignment: HorizontalAlignment,
    ) : ShowcaseTreeDetail

    /**
     * The default Box child alignment.
     */
    data class BoxContentAlignment(
        internal val alignment: Alignment,
    ) : ShowcaseTreeDetail
}
