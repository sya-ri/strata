package dev.s7a.strata.integration.docs

import dev.s7a.strata.geometry.IntSize

/**
 * Owns the deterministic Minecraft component catalog used by both generator launchers.
 */
internal object ShowcaseScenarioCatalog {
    /**
     * Component scenarios in the required MenuBackground, Text, Button order.
     */
    internal val components: List<ComponentScenario> =
        listOf(
            ComponentScenario(
                component = DocumentedComponent.MenuBackground,
                source =
                    SourceReference(
                        "integration/minecraft-fabric-26.2/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftMenuBackgroundExample.kt",
                        "menu-background",
                    ),
                viewportMetadata = ShowcaseViewport(IntSize(32, 32), 1),
                tree = tree(DocumentedComponent.MenuBackground, listOf(ShowcaseTreeDetail.FillMaxSize)),
            ),
            ComponentScenario(
                component = DocumentedComponent.Text,
                source =
                    SourceReference(
                        "integration/minecraft-fabric-26.2/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftTextExample.kt",
                        "text",
                    ),
                viewportMetadata = ShowcaseViewport(IntSize(150, 20), 1),
                tree = tree(DocumentedComponent.Text, listOf(ShowcaseTreeDetail.Size(150, 20))),
            ),
            ComponentScenario(
                component = DocumentedComponent.Button,
                source =
                    SourceReference(
                        "integration/minecraft-fabric-26.2/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftButtonExample.kt",
                        "button",
                    ),
                viewportMetadata = ShowcaseViewport(IntSize(150, 20), 1),
                tree = tree(DocumentedComponent.Button, emptyList()),
            ),
        )

    /**
     * Verified ConfirmScreen overview rendered before component pages.
     */
    internal val overview: OverviewScenario =
        OverviewScenario(
            source = SourceReference("integration/minecraft-fabric-26.2/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftOverviewExample.kt", "overview"),
            viewport = IntSize(320, 180),
            scale = 1,
            tree =
                ShowcaseTree(
                    DocumentedComponent.MenuBackground,
                    listOf(ShowcaseTreeDetail.FillMaxSize),
                    listOf(
                        ShowcaseTree(DocumentedComponent.Text, emptyList(), emptyList()),
                        ShowcaseTree(DocumentedComponent.Text, emptyList(), emptyList()),
                        ShowcaseTree(DocumentedComponent.Button, emptyList(), emptyList()),
                        ShowcaseTree(DocumentedComponent.Button, emptyList(), emptyList()),
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
