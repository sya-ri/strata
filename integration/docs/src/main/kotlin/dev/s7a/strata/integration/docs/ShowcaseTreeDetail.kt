package dev.s7a.strata.integration.docs

import dev.s7a.strata.geometry.DoubleOffset
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.text.TextLayout
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
     * Explicit display-text line breaking, line limit, spacing, and overflow behavior.
     */
    data class MultilineText(
        internal val policy: TextLayout.Multiline,
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
     * A FlowRow direct-child vertical alignment within its measured row.
     */
    data class FlowRowAlign(
        internal val alignment: VerticalAlignment,
    ) : ShowcaseTreeDetail

    /**
     * A Column direct-child horizontal alignment.
     */
    data class ColumnAlign(
        internal val alignment: HorizontalAlignment,
    ) : ShowcaseTreeDetail

    /**
     * A Stack direct-child alignment.
     */
    data class StackAlign(
        internal val alignment: Alignment,
    ) : ShowcaseTreeDetail

    /**
     * A TiledImage direct-child content-coordinate anchor.
     */
    data class TiledImageContentPosition(
        internal val position: DoubleOffset,
        internal val alignment: Alignment,
    ) : ShowcaseTreeDetail

    /**
     * A Grid direct-child alignment inside its measured cell.
     */
    data class GridAlign(
        internal val alignment: Alignment,
    ) : ShowcaseTreeDetail

    /**
     * The fixed number of columns in a Grid.
     */
    data class GridColumns(
        internal val value: Int,
    ) : ShowcaseTreeDetail

    /**
     * Independent fixed spacing between Grid columns and rows.
     */
    data class GridSpacing(
        internal val horizontal: Int,
        internal val vertical: Int,
    ) : ShowcaseTreeDetail

    /**
     * Independent fixed spacing between FlowRow children and rows.
     */
    data class FlowRowSpacing(
        internal val horizontal: Int,
        internal val vertical: Int,
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
     * A linear child arrangement or FlowRow per-row horizontal arrangement.
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
     * The default FlowRow vertical alignment within each measured row.
     */
    data class FlowRowDefaultAlignment(
        internal val alignment: VerticalAlignment,
    ) : ShowcaseTreeDetail

    /**
     * The default Column cross-axis alignment.
     */
    data class ColumnDefaultAlignment(
        internal val alignment: HorizontalAlignment,
    ) : ShowcaseTreeDetail

    /**
     * The default Stack child alignment.
     */
    data class StackContentAlignment(
        internal val alignment: Alignment,
    ) : ShowcaseTreeDetail

    /**
     * The default alignment of a Grid child inside its measured cell.
     */
    data class GridContentAlignment(
        internal val alignment: Alignment,
    ) : ShowcaseTreeDetail
}
