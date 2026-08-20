package dev.s7a.strata.integration.docs

/**
 * Renders exhaustive typed showcase details without allowing new detail types to disappear from generated guidance.
 */
internal object ShowcaseDetailMarkdown {
    /**
     * Renders the generic modifiers contained in the ordered detail list.
     *
     * @param details typed details to classify.
     * @return comma-separated source-like guidance or `none` when no generic modifier is present.
     */
    internal fun genericGuidance(details: List<ShowcaseTreeDetail>): String = guidance(details, Category.GenericModifier)

    /**
     * Renders the component parameters contained in the ordered detail list.
     *
     * @param details typed details to classify.
     * @return comma-separated source-like guidance or `none` when no component parameter is present.
     */
    internal fun componentParameterGuidance(details: List<ShowcaseTreeDetail>): String = guidance(details, Category.ComponentParameter)

    /**
     * Renders the parent data contained in the ordered detail list.
     *
     * @param details typed details to classify.
     * @return comma-separated source-like guidance or `none` when no parent data is present.
     */
    internal fun parentDataGuidance(details: List<ShowcaseTreeDetail>): String = guidance(details.distinct(), Category.ParentData)

    /**
     * Renders one typed detail in deterministic source-like form.
     *
     * @param detail detail to render.
     * @return stable documentation text.
     */
    internal fun text(detail: ShowcaseTreeDetail): String =
        when (detail) {
            ShowcaseTreeDetail.FillMaxSize -> "FillMaxSize"
            is ShowcaseTreeDetail.Size -> "Size(width=${detail.width}, height=${detail.height})"
            is ShowcaseTreeDetail.Padding -> "Padding(all=${detail.all})"
            is ShowcaseTreeDetail.Background -> "Background(color=0x${detail.color.value.toUInt().toString(16).padStart(8, '0').uppercase()})"
            is ShowcaseTreeDetail.Weight -> "Weight(weight=${detail.weight}, fill=${detail.fill})"
            is ShowcaseTreeDetail.RowAlign -> "RowAlign(alignment=${detail.alignment})"
            is ShowcaseTreeDetail.ColumnAlign -> "ColumnAlign(alignment=${detail.alignment})"
            is ShowcaseTreeDetail.BoxAlign -> "BoxAlign(alignment=${detail.alignment})"
            is ShowcaseTreeDetail.Spacing -> "Spacing(value=${detail.value})"
            is ShowcaseTreeDetail.Arrangement -> "Arrangement(value=${detail.arrangement})"
            is ShowcaseTreeDetail.RowDefaultAlignment -> "RowDefaultAlignment(alignment=${detail.alignment})"
            is ShowcaseTreeDetail.ColumnDefaultAlignment -> "ColumnDefaultAlignment(alignment=${detail.alignment})"
            is ShowcaseTreeDetail.BoxContentAlignment -> "BoxContentAlignment(alignment=${detail.alignment})"
        }

    private fun guidance(
        details: List<ShowcaseTreeDetail>,
        expectedCategory: Category,
    ): String {
        val selected = details.filter { detail -> category(detail) == expectedCategory }
        return if (selected.isEmpty()) "none" else selected.joinToString(", ") { detail -> text(detail) }
    }

    private fun category(detail: ShowcaseTreeDetail): Category =
        when (detail) {
            ShowcaseTreeDetail.FillMaxSize,
            is ShowcaseTreeDetail.Size,
            is ShowcaseTreeDetail.Padding,
            is ShowcaseTreeDetail.Background,
            -> Category.GenericModifier

            is ShowcaseTreeDetail.Weight,
            is ShowcaseTreeDetail.RowAlign,
            is ShowcaseTreeDetail.ColumnAlign,
            is ShowcaseTreeDetail.BoxAlign,
            -> Category.ParentData

            is ShowcaseTreeDetail.Spacing,
            is ShowcaseTreeDetail.Arrangement,
            is ShowcaseTreeDetail.RowDefaultAlignment,
            is ShowcaseTreeDetail.ColumnDefaultAlignment,
            is ShowcaseTreeDetail.BoxContentAlignment,
            -> Category.ComponentParameter
        }

    private enum class Category {
        GenericModifier,
        ComponentParameter,
        ParentData,
    }
}
