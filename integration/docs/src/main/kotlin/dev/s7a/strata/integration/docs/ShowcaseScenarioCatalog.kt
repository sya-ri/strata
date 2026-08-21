package dev.s7a.strata.integration.docs

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.HorizontalAlignment

/**
 * Owns the deterministic typed catalog used by both generator launchers.
 */
internal object ShowcaseScenarioCatalog {
    /**
     * Component scenarios in the required Row, Column, Box, Spacer order.
     */
    internal val components: List<ComponentScenario> =
        listOf(
            ComponentScenario(
                component = DocumentedComponent.Row,
                source = SourceReference("integration/minecraft-fabric-26.2/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftRowExample.kt", "row"),
                viewportMetadata = ShowcaseViewport(IntSize(320, 180), 1),
                tree =
                    tree(
                        DocumentedComponent.Column,
                        listOf(
                            ShowcaseTreeDetail.Size(320, 180),
                            ShowcaseTreeDetail.ColumnDefaultAlignment(HorizontalAlignment.Center),
                        ),
                        tree(DocumentedComponent.Spacer, listOf(ShowcaseTreeDetail.Height(20))),
                        tree(DocumentedComponent.Spacer, listOf(ShowcaseTreeDetail.Height(11))),
                        tree(DocumentedComponent.Row, listOf(ShowcaseTreeDetail.Spacing(10))),
                    ),
            ),
            ComponentScenario(
                component = DocumentedComponent.Column,
                source = SourceReference("integration/minecraft-fabric-26.2/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftColumnExample.kt", "column"),
                viewportMetadata = ShowcaseViewport(IntSize(320, 180), 1),
                tree =
                    tree(
                        DocumentedComponent.Column,
                        listOf(
                            ShowcaseTreeDetail.Size(320, 180),
                            ShowcaseTreeDetail.ColumnDefaultAlignment(HorizontalAlignment.Center),
                        ),
                        tree(DocumentedComponent.Spacer, listOf(ShowcaseTreeDetail.Height(20))),
                        tree(DocumentedComponent.Spacer, listOf(ShowcaseTreeDetail.Height(11))),
                        tree(DocumentedComponent.Spacer, listOf(ShowcaseTreeDetail.Height(4))),
                    ),
            ),
            ComponentScenario(
                component = DocumentedComponent.Box,
                source = SourceReference("integration/minecraft-fabric-26.2/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftBoxExample.kt", "box"),
                viewportMetadata = ShowcaseViewport(IntSize(320, 180), 1),
                tree =
                    tree(
                        DocumentedComponent.Box,
                        listOf(
                            ShowcaseTreeDetail.Size(320, 180),
                            ShowcaseTreeDetail.BoxContentAlignment(Alignment.Center),
                        ),
                    ),
            ),
            ComponentScenario(
                component = DocumentedComponent.Spacer,
                source = SourceReference("integration/minecraft-fabric-26.2/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftSpacerExample.kt", "spacer"),
                viewportMetadata = ShowcaseViewport(IntSize(320, 180), 1),
                tree =
                    tree(
                        DocumentedComponent.Column,
                        listOf(
                            ShowcaseTreeDetail.Size(320, 180),
                            ShowcaseTreeDetail.ColumnDefaultAlignment(HorizontalAlignment.Center),
                        ),
                        tree(DocumentedComponent.Spacer, listOf(ShowcaseTreeDetail.Height(20))),
                        tree(DocumentedComponent.Spacer, listOf(ShowcaseTreeDetail.Height(51))),
                    ),
            ),
        )

    /**
     * Overview scenario rendered before component pages.
     */
    internal val overview: OverviewScenario =
        OverviewScenario(
            source = SourceReference("integration/minecraft-fabric-26.2/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftOverviewExample.kt", "overview"),
            viewport = IntSize(320, 180),
            scale = 1,
            tree =
                ShowcaseTree(
                    DocumentedComponent.Column,
                    listOf(
                        ShowcaseTreeDetail.Size(320, 180),
                        ShowcaseTreeDetail.ColumnDefaultAlignment(HorizontalAlignment.Center),
                    ),
                    listOf(
                        ShowcaseTree(DocumentedComponent.Spacer, listOf(ShowcaseTreeDetail.Height(20)), emptyList()),
                        ShowcaseTree(DocumentedComponent.Spacer, listOf(ShowcaseTreeDetail.Height(11)), emptyList()),
                    ),
                ),
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
            listOf("README.md", "images/overview.png", "minecraft-26.2-parity.properties") +
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

            is ShowcaseTreeDetail.Height -> require(0 < detail.value) { "Showcase heights must be positive." }

            is ShowcaseTreeDetail.Padding -> require(0 <= detail.all) { "Showcase padding must be nonnegative." }

            is ShowcaseTreeDetail.Spacing -> require(0 <= detail.value) { "Showcase spacing must be nonnegative." }

            is ShowcaseTreeDetail.Weight -> require(detail.weight.isFinite() && 0 < detail.weight) { "Showcase weights must be positive finite values." }
        }
    }
}
