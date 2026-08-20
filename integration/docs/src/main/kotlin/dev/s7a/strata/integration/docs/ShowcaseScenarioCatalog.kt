package dev.s7a.strata.integration.docs

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.render.ArgbColor

/**
 * Owns the deterministic typed catalog used by both generator launchers.
 */
internal object ShowcaseScenarioCatalog {
    private val canvas = ArgbColor(0xFF111827.toInt())
    private val panel = ArgbColor(0xFF1F2937.toInt())
    private val cyan = ArgbColor(0xFF22D3EE.toInt())
    private val violet = ArgbColor(0xFFA78BFA.toInt())
    private val amber = ArgbColor(0xFFFBBF24.toInt())
    private val rose = ArgbColor(0xFFFB7185.toInt())

    /**
     * Component scenarios in the required Row, Column, Box, Spacer order.
     */
    internal val components: List<ComponentScenario> =
        listOf(
            ComponentScenario(
                component = DocumentedComponent.Row,
                source = SourceReference("dev/s7a/strata/integration/docs/RowExample.kt", "row"),
                viewportMetadata = ShowcaseViewport(IntSize(72, 28), 3),
                tree =
                    tree(
                        DocumentedComponent.Row,
                        listOf(
                            ShowcaseTreeDetail.FillMaxSize,
                            ShowcaseTreeDetail.Background(canvas),
                            ShowcaseTreeDetail.Padding(4),
                            ShowcaseTreeDetail.Spacing(2),
                            ShowcaseTreeDetail.Arrangement(Arrangement.SpaceEvenly),
                            ShowcaseTreeDetail.RowDefaultAlignment(VerticalAlignment.Center),
                        ),
                        tree(DocumentedComponent.Spacer, listOf(ShowcaseTreeDetail.Size(12, 12), ShowcaseTreeDetail.Background(cyan))),
                        tree(DocumentedComponent.Spacer, listOf(ShowcaseTreeDetail.Size(14, 16), ShowcaseTreeDetail.Background(violet), ShowcaseTreeDetail.Weight(1f, false))),
                        tree(DocumentedComponent.Spacer, listOf(ShowcaseTreeDetail.Size(12, 8), ShowcaseTreeDetail.Background(amber), ShowcaseTreeDetail.RowAlign(VerticalAlignment.Bottom))),
                    ),
                description = ::rowDescription,
                render = ::row,
            ),
            ComponentScenario(
                component = DocumentedComponent.Column,
                source = SourceReference("dev/s7a/strata/integration/docs/ColumnExample.kt", "column"),
                viewportMetadata = ShowcaseViewport(IntSize(36, 68), 3),
                tree =
                    tree(
                        DocumentedComponent.Column,
                        listOf(
                            ShowcaseTreeDetail.FillMaxSize,
                            ShowcaseTreeDetail.Background(canvas),
                            ShowcaseTreeDetail.Padding(4),
                            ShowcaseTreeDetail.Spacing(2),
                            ShowcaseTreeDetail.Arrangement(Arrangement.SpaceAround),
                            ShowcaseTreeDetail.ColumnDefaultAlignment(HorizontalAlignment.Center),
                        ),
                        tree(DocumentedComponent.Spacer, listOf(ShowcaseTreeDetail.Size(12, 12), ShowcaseTreeDetail.Background(cyan))),
                        tree(DocumentedComponent.Spacer, listOf(ShowcaseTreeDetail.Size(14, 16), ShowcaseTreeDetail.Background(violet), ShowcaseTreeDetail.Weight(1f, false))),
                        tree(DocumentedComponent.Spacer, listOf(ShowcaseTreeDetail.Size(12, 8), ShowcaseTreeDetail.Background(amber), ShowcaseTreeDetail.ColumnAlign(HorizontalAlignment.End))),
                    ),
                description = ::columnDescription,
                render = ::column,
            ),
            ComponentScenario(
                component = DocumentedComponent.Box,
                source = SourceReference("dev/s7a/strata/integration/docs/BoxExample.kt", "box"),
                viewportMetadata = ShowcaseViewport(IntSize(64, 36), 3),
                tree =
                    tree(
                        DocumentedComponent.Box,
                        listOf(
                            ShowcaseTreeDetail.FillMaxSize,
                            ShowcaseTreeDetail.Background(canvas),
                            ShowcaseTreeDetail.Padding(4),
                            ShowcaseTreeDetail.BoxContentAlignment(Alignment.Center),
                        ),
                        tree(DocumentedComponent.Spacer, listOf(ShowcaseTreeDetail.Size(28, 16), ShowcaseTreeDetail.Background(cyan), ShowcaseTreeDetail.BoxAlign(Alignment.TopStart))),
                        tree(DocumentedComponent.Spacer, listOf(ShowcaseTreeDetail.Size(36, 20), ShowcaseTreeDetail.Background(violet))),
                        tree(DocumentedComponent.Spacer, listOf(ShowcaseTreeDetail.Size(20, 12), ShowcaseTreeDetail.Background(amber), ShowcaseTreeDetail.BoxAlign(Alignment.BottomEnd))),
                    ),
                description = ::boxDescription,
                render = ::box,
            ),
            ComponentScenario(
                component = DocumentedComponent.Spacer,
                source = SourceReference("dev/s7a/strata/integration/docs/SpacerExample.kt", "spacer"),
                viewportMetadata = ShowcaseViewport(IntSize(64, 28), 3),
                tree =
                    tree(
                        DocumentedComponent.Box,
                        listOf(ShowcaseTreeDetail.FillMaxSize, ShowcaseTreeDetail.Background(canvas), ShowcaseTreeDetail.Padding(4), ShowcaseTreeDetail.BoxContentAlignment(Alignment.Center)),
                        tree(DocumentedComponent.Spacer, listOf(ShowcaseTreeDetail.Size(36, 12), ShowcaseTreeDetail.Background(rose), ShowcaseTreeDetail.BoxAlign(Alignment.Center))),
                    ),
                description = ::spacerDescription,
                render = ::spacer,
            ),
        )

    /**
     * Overview scenario rendered before component pages.
     */
    internal val overview: OverviewScenario =
        OverviewScenario(
            source = SourceReference("dev/s7a/strata/integration/docs/OverviewExample.kt", "overview"),
            viewport = IntSize(72, 44),
            scale = 3,
            tree =
                ShowcaseTree(
                    DocumentedComponent.Column,
                    listOf(
                        ShowcaseTreeDetail.FillMaxSize,
                        ShowcaseTreeDetail.Background(canvas),
                        ShowcaseTreeDetail.Padding(4),
                        ShowcaseTreeDetail.Spacing(4),
                        ShowcaseTreeDetail.Arrangement(Arrangement.Center),
                        ShowcaseTreeDetail.ColumnDefaultAlignment(HorizontalAlignment.Center),
                    ),
                    listOf(
                        ShowcaseTree(
                            DocumentedComponent.Row,
                            listOf(ShowcaseTreeDetail.Size(60, 12), ShowcaseTreeDetail.Arrangement(Arrangement.SpaceEvenly), ShowcaseTreeDetail.RowDefaultAlignment(VerticalAlignment.Center)),
                            listOf(
                                ShowcaseTree(DocumentedComponent.Spacer, listOf(ShowcaseTreeDetail.Size(10, 8), ShowcaseTreeDetail.Background(cyan))),
                                ShowcaseTree(DocumentedComponent.Spacer, listOf(ShowcaseTreeDetail.Size(10, 10), ShowcaseTreeDetail.Background(violet))),
                                ShowcaseTree(DocumentedComponent.Spacer, listOf(ShowcaseTreeDetail.Size(10, 6), ShowcaseTreeDetail.Background(amber))),
                            ),
                        ),
                        ShowcaseTree(
                            DocumentedComponent.Box,
                            listOf(ShowcaseTreeDetail.Size(44, 16), ShowcaseTreeDetail.Background(panel), ShowcaseTreeDetail.BoxContentAlignment(Alignment.Center)),
                            listOf(ShowcaseTree(DocumentedComponent.Spacer, listOf(ShowcaseTreeDetail.Size(24, 8), ShowcaseTreeDetail.Background(rose), ShowcaseTreeDetail.BoxAlign(Alignment.Center)))),
                        ),
                    ),
                ),
            description = ::overviewDescription,
            render = ::overview,
        )

    /**
     * Checks catalog order, uniqueness, and exact component coverage.
     *
     * @throws IllegalArgumentException when the typed catalog is incomplete, duplicated, or invalid.
     */
    internal fun validate() {
        val expected = DocumentedComponent.entries
        val actual = components.map { scenario -> scenario.component }
        require(actual == expected) { "Showcase catalog does not cover components in typed order." }
        require(actual.toSet().size == actual.size) { "Showcase catalog contains duplicate components." }
        val sources = listOf(overview.source) + components.map { scenario -> scenario.source }
        require(sources.map { source -> source.slug }.toSet().size == sources.size) { "Showcase source slugs must be unique." }
        require(sources.map { source -> source.relativePath }.toSet().size == sources.size) { "Showcase source paths must be unique." }
        val outputPaths =
            listOf("README.md", "images/overview.png") +
                components.flatMap { scenario -> listOf("${scenario.component.slug}.md", "images/${scenario.component.slug}.png") }
        require(outputPaths.toSet().size == outputPaths.size) { "Showcase output paths must be unique." }
        require(0 < overview.viewport.width && 0 < overview.viewport.height && 0 < overview.scale) {
            "Overview viewport metadata must be positive."
        }
        components.forEach { scenario ->
            require(0 < scenario.viewport.width && 0 < scenario.viewport.height && 0 < scenario.scale) {
                "Component viewport metadata must be positive for ${scenario.component.apiMethodName}."
            }
        }
        validateDetails(overview.tree)
        components.forEach { scenario -> validateDetails(scenario.tree) }
    }

    private fun tree(
        component: DocumentedComponent,
        details: List<ShowcaseTreeDetail>,
        vararg children: ShowcaseTree,
    ): ShowcaseTree = ShowcaseTree(component, details, children.toList())

    private fun validateDetails(tree: ShowcaseTree) {
        tree.details.forEach(::validateDetail)
        tree.children.forEach { child -> validateDetails(child) }
    }

    private fun validateDetail(detail: ShowcaseTreeDetail) {
        when (detail) {
            ShowcaseTreeDetail.FillMaxSize,
            is ShowcaseTreeDetail.Background,
            is ShowcaseTreeDetail.RowAlign,
            is ShowcaseTreeDetail.ColumnAlign,
            is ShowcaseTreeDetail.BoxAlign,
            is ShowcaseTreeDetail.Arrangement,
            is ShowcaseTreeDetail.RowDefaultAlignment,
            is ShowcaseTreeDetail.ColumnDefaultAlignment,
            is ShowcaseTreeDetail.BoxContentAlignment,
            -> Unit

            is ShowcaseTreeDetail.Size -> require(0 < detail.width && 0 < detail.height) { "Showcase sizes must be positive." }

            is ShowcaseTreeDetail.Padding -> require(0 <= detail.all) { "Showcase padding must be nonnegative." }

            is ShowcaseTreeDetail.Spacing -> require(0 <= detail.value) { "Showcase spacing must be nonnegative." }

            is ShowcaseTreeDetail.Weight -> require(detail.weight.isFinite() && 0 < detail.weight) { "Showcase weights must be positive finite values." }
        }
    }
}
