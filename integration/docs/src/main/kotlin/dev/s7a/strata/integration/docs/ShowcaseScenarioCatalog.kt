package dev.s7a.strata.integration.docs

import dev.s7a.strata.geometry.IntSize

/**
 * Owns the deterministic Minecraft component catalog used by both generator launchers.
 */
internal object ShowcaseScenarioCatalog {
    /**
     * Component scenarios in the required Text, TextField, Button, Scroll, Slot order.
     */
    internal val components: List<ComponentScenario> =
        listOf(
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
                component = DocumentedComponent.TextField,
                source =
                    SourceReference(
                        "integration/minecraft-fabric-26.2/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftTextFieldExample.kt",
                        "text-field",
                    ),
                viewportMetadata = ShowcaseViewport(IntSize(200, 20), 1),
                tree = tree(DocumentedComponent.TextField, listOf(ShowcaseTreeDetail.Size(200, 20))),
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
            ComponentScenario(
                component = DocumentedComponent.Scroll,
                source =
                    SourceReference(
                        "integration/minecraft-fabric-26.2/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftScrollExample.kt",
                        "scroll",
                    ),
                viewportMetadata = ShowcaseViewport(IntSize(320, 94), 1),
                tree =
                    tree(
                        DocumentedComponent.Scroll,
                        listOf(ShowcaseTreeDetail.Size(320, 94), ShowcaseTreeDetail.ScrollRate(9)),
                        *Array(12) { tree(DocumentedComponent.Text, emptyList()) },
                    ),
            ),
            ComponentScenario(
                component = DocumentedComponent.Slot,
                source =
                    SourceReference(
                        "integration/minecraft-fabric-26.2/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftSlotExample.kt",
                        "slot",
                    ),
                viewportMetadata = ShowcaseViewport(IntSize(24, 24), 1),
                tree =
                    tree(
                        DocumentedComponent.Slot,
                        listOf(ShowcaseTreeDetail.SlotHighlightable(true), ShowcaseTreeDetail.Size(18, 18)),
                    ),
            ),
            ComponentScenario(
                component = DocumentedComponent.Image,
                source =
                    SourceReference(
                        "integration/minecraft-fabric-26.2/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftIndustrialExample.kt",
                        "image",
                    ),
                viewportMetadata = ShowcaseViewport(IntSize(32, 32), 1),
                tree = tree(DocumentedComponent.Image, listOf(ShowcaseTreeDetail.Size(32, 32))),
            ),
        )

    /**
     * Verified ConfirmScreen overview rendered before component sections.
     */
    internal val overview: OverviewScenario =
        OverviewScenario(
            source = SourceReference("integration/minecraft-fabric-26.2/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftOverviewExample.kt", "overview"),
            viewport = IntSize(320, 180),
            scale = 1,
            trees =
                listOf(
                    ShowcaseTree(DocumentedComponent.Text, emptyList(), emptyList()),
                    ShowcaseTree(DocumentedComponent.Text, emptyList(), emptyList()),
                    ShowcaseTree(DocumentedComponent.Button, emptyList(), emptyList()),
                    ShowcaseTree(DocumentedComponent.Button, emptyList(), emptyList()),
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
            listOf("components.md", "overview.png", "minecraft-26.2-parity.properties") +
                components.map { scenario -> "${scenario.component.slug}.png" }
        require(outputPaths.toSet().size == outputPaths.size) { "Showcase output paths must be unique." }
        require(0 < overview.viewport.width && 0 < overview.viewport.height && 0 < overview.scale) {
            "Overview viewport metadata must be positive."
        }
        components.forEach { scenario ->
            require(0 < scenario.viewport.width && 0 < scenario.viewport.height && 0 < scenario.scale) {
                "Component viewport metadata must be positive for ${scenario.component.apiMethodName}."
            }
        }
        overview.trees.forEach(::validateDetails)
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

            is ShowcaseTreeDetail.ScrollRate -> require(0 < detail.value) { "Showcase Scroll rates must be positive." }

            is ShowcaseTreeDetail.SlotHighlightable -> Unit

            is ShowcaseTreeDetail.Weight -> require(detail.weight.isFinite() && 0 < detail.weight) { "Showcase weights must be positive finite values." }
        }
    }
}
