package dev.s7a.strata.integration.docs

/**
 * Renders exhaustive typed showcase details without allowing new detail types to disappear from generated guidance.
 */
internal object ShowcaseDetailMarkdown {
    /**
     * Renders one typed detail in deterministic source-like form.
     *
     * @param detail detail to render.
     * @return stable documentation text.
     *
     * The single exhaustive branch makes newly added metadata impossible to omit silently.
     */
    @Suppress("CyclomaticComplexMethod")
    internal fun text(detail: ShowcaseTreeDetail): String =
        when (detail) {
            ShowcaseTreeDetail.FillMaxSize -> "FillMaxSize"
            is ShowcaseTreeDetail.Size -> "Size(width=${detail.width}, height=${detail.height})"
            is ShowcaseTreeDetail.Height -> "Height(value=${detail.value})"
            is ShowcaseTreeDetail.MultilineText -> "TextLayout.Multiline(wrap=${detail.policy.wrap}, maxLines=${detail.policy.maxLines}, overflow=${detail.policy.overflow}, lineSpacing=${detail.policy.lineSpacing})"
            is ShowcaseTreeDetail.Padding -> "Padding(all=${detail.all})"
            is ShowcaseTreeDetail.Background -> "Background(color=0x${detail.color.value.toUInt().toString(16).padStart(8, '0').uppercase()})"
            is ShowcaseTreeDetail.Weight -> "Weight(weight=${detail.weight}, fill=${detail.fill})"
            is ShowcaseTreeDetail.RowAlign -> "RowAlign(alignment=${detail.alignment})"
            is ShowcaseTreeDetail.FlowRowAlign -> "FlowRowAlign(alignment=${detail.alignment})"
            is ShowcaseTreeDetail.ColumnAlign -> "ColumnAlign(alignment=${detail.alignment})"
            is ShowcaseTreeDetail.StackAlign -> "StackAlign(alignment=${detail.alignment})"
            is ShowcaseTreeDetail.GridAlign -> "GridAlign(alignment=${detail.alignment})"
            is ShowcaseTreeDetail.GridColumns -> "GridColumns(value=${detail.value})"
            is ShowcaseTreeDetail.GridSpacing -> "GridSpacing(horizontal=${detail.horizontal}, vertical=${detail.vertical})"
            is ShowcaseTreeDetail.FlowRowSpacing -> "FlowRowSpacing(horizontal=${detail.horizontal}, vertical=${detail.vertical})"
            is ShowcaseTreeDetail.Spacing -> "Spacing(value=${detail.value})"
            is ShowcaseTreeDetail.ScrollRate -> "ScrollRate(value=${detail.value})"
            is ShowcaseTreeDetail.SlotHighlightable -> "SlotHighlightable(value=${detail.value})"
            is ShowcaseTreeDetail.Arrangement -> "Arrangement(value=${detail.arrangement})"
            is ShowcaseTreeDetail.RowDefaultAlignment -> "RowDefaultAlignment(alignment=${detail.alignment})"
            is ShowcaseTreeDetail.FlowRowDefaultAlignment -> "FlowRowDefaultAlignment(alignment=${detail.alignment})"
            is ShowcaseTreeDetail.ColumnDefaultAlignment -> "ColumnDefaultAlignment(alignment=${detail.alignment})"
            is ShowcaseTreeDetail.StackContentAlignment -> "StackContentAlignment(alignment=${detail.alignment})"
            is ShowcaseTreeDetail.GridContentAlignment -> "GridContentAlignment(alignment=${detail.alignment})"
        }
}
