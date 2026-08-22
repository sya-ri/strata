package dev.s7a.strata.integration.docs

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.render.ArgbColor

/**
 * Owns the deterministic Minecraft component catalog used by both generator launchers.
 */
internal object ShowcaseScenarioCatalog {
    /**
     * Component scenarios in the exact public API inventory order.
     */
    internal val components: List<ComponentScenario> =
        listOf(
            ComponentScenario(
                component = DocumentedComponent.Row,
                source = overviewSource(),
                viewportMetadata = ShowcaseViewport(IntSize(320, 180), 1),
                tree =
                    tree(
                        DocumentedComponent.Row,
                        listOf(ShowcaseTreeDetail.Spacing(4)),
                        tree(DocumentedComponent.Button, emptyList()),
                        tree(DocumentedComponent.Button, emptyList()),
                    ),
            ),
            ComponentScenario(
                component = DocumentedComponent.Column,
                source = overviewSource(),
                viewportMetadata = ShowcaseViewport(IntSize(320, 180), 1),
                tree =
                    tree(
                        DocumentedComponent.Column,
                        listOf(ShowcaseTreeDetail.Spacing(24)),
                        tree(
                            DocumentedComponent.Column,
                            listOf(ShowcaseTreeDetail.Spacing(8)),
                            tree(DocumentedComponent.Text, emptyList()),
                            tree(DocumentedComponent.Text, emptyList()),
                        ),
                        tree(
                            DocumentedComponent.Row,
                            listOf(ShowcaseTreeDetail.Spacing(4)),
                            tree(DocumentedComponent.Button, emptyList()),
                            tree(DocumentedComponent.Button, emptyList()),
                        ),
                    ),
            ),
            ComponentScenario(
                component = DocumentedComponent.Stack,
                source = overviewSource(),
                viewportMetadata = ShowcaseViewport(IntSize(320, 180), 1),
                tree =
                    tree(
                        DocumentedComponent.Stack,
                        listOf(
                            ShowcaseTreeDetail.Size(320, 180),
                            ShowcaseTreeDetail.StackContentAlignment(Alignment.Center),
                        ),
                        tree(DocumentedComponent.Column, listOf(ShowcaseTreeDetail.Spacing(24))),
                    ),
            ),
            ComponentScenario(
                component = DocumentedComponent.Grid,
                source = slotSource(),
                viewportMetadata = ShowcaseViewport(IntSize(320, 240), 1),
                tree =
                    tree(
                        DocumentedComponent.Grid,
                        listOf(ShowcaseTreeDetail.GridColumns(9)),
                        *Array(27) { tree(DocumentedComponent.Slot, emptyList()) },
                    ),
            ),
            ComponentScenario(
                component = DocumentedComponent.Spacer,
                source = progressSource(),
                viewportMetadata = ShowcaseViewport(IntSize(320, 180), 1),
                tree =
                    tree(
                        DocumentedComponent.Spacer,
                        listOf(
                            ShowcaseTreeDetail.Size(32, 2),
                            ShowcaseTreeDetail.Background(ArgbColor(0xFF7A7A7A.toInt())),
                        ),
                    ),
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
                component = DocumentedComponent.Tab,
                source = socialSource(),
                viewportMetadata = ShowcaseViewport(IntSize(320, 240), 1),
                tree = tree(DocumentedComponent.Tab, listOf(ShowcaseTreeDetail.Size(73, 20))),
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
                component = DocumentedComponent.Image,
                source = industrialSource(),
                viewportMetadata = ShowcaseViewport(IntSize(32, 32), 1),
                tree = tree(DocumentedComponent.Image, listOf(ShowcaseTreeDetail.Size(32, 32))),
            ),
            ComponentScenario(
                component = DocumentedComponent.Slot,
                source = slotSource(),
                viewportMetadata = ShowcaseViewport(IntSize(24, 24), 1),
                tree =
                    tree(
                        DocumentedComponent.Slot,
                        listOf(ShowcaseTreeDetail.SlotHighlightable(true), ShowcaseTreeDetail.Size(18, 18)),
                    ),
            ),
            ComponentScenario(
                component = DocumentedComponent.PlayerHead,
                source =
                    SourceReference(
                        "integration/minecraft-fabric-26.2/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftPlayerHeadExample.kt",
                        "player-head",
                    ),
                viewportMetadata = ShowcaseViewport(IntSize(24, 24), 1),
                tree = tree(DocumentedComponent.PlayerHead, listOf(ShowcaseTreeDetail.Size(24, 24))),
            ),
        )

    /**
     * Verified ConfirmScreen overview rendered before component sections.
     */
    internal val overview: OverviewScenario =
        OverviewScenario(
            source = overviewSource(),
            viewport = IntSize(320, 180),
            scale = 1,
            trees =
                listOf(
                    tree(
                        DocumentedComponent.Stack,
                        listOf(ShowcaseTreeDetail.Size(320, 180)),
                        tree(
                            DocumentedComponent.Column,
                            listOf(ShowcaseTreeDetail.Spacing(24)),
                            tree(
                                DocumentedComponent.Column,
                                listOf(ShowcaseTreeDetail.Spacing(8)),
                                tree(DocumentedComponent.Text, emptyList()),
                                tree(DocumentedComponent.Text, emptyList()),
                            ),
                            tree(
                                DocumentedComponent.Row,
                                listOf(ShowcaseTreeDetail.Spacing(4)),
                                tree(DocumentedComponent.Button, emptyList()),
                                tree(DocumentedComponent.Button, emptyList()),
                            ),
                        ),
                    ),
                ),
        )

    /**
     * Complete vanilla and Mod use cases rendered after the primitive component catalog.
     */
    internal val screens: List<ScreenScenario> =
        listOf(
            ScreenScenario(
                DocumentedScreen.SocialInteractions,
                SourceReference(
                    "integration/minecraft-fabric-26.2/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftSocialExample.kt",
                    "social-screen",
                ),
                ShowcaseViewport(IntSize(320, 240), 1),
            ),
            ScreenScenario(
                DocumentedScreen.SynchronizedInventory,
                SourceReference(
                    "integration/minecraft-fabric-26.2/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftInventoryExample.kt",
                    "inventory-screen",
                ),
                ShowcaseViewport(IntSize(320, 240), 1),
            ),
            ScreenScenario(
                DocumentedScreen.IndustrialController,
                industrialSource(),
                ShowcaseViewport(IntSize(320, 180), 1),
            ),
            ScreenScenario(
                DocumentedScreen.PowerMilestones,
                SourceReference(
                    "integration/minecraft-fabric-26.2/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftProgressExample.kt",
                    "progress-screen",
                ),
                ShowcaseViewport(IntSize(320, 180), 1),
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
        val sources = listOf(overview.source) + components.map { scenario -> scenario.source } + screens.map { scenario -> scenario.source }
        sources.groupBy { source -> source.slug }.forEach { (slug, references) ->
            require(references.distinctBy { reference -> reference.relativePath to reference.slug }.size == 1) {
                "Showcase source slug $slug identifies multiple regions."
            }
        }
        val outputPaths =
            listOf("components.md", "overview.png", "minecraft-26.2-parity.properties") +
                components
                    .map { scenario -> "${scenario.component.slug}.png" }
                    .plus(screens.map { scenario -> "screen-${scenario.screen.slug}.png" })
        require(outputPaths.toSet().size == outputPaths.size) { "Showcase output paths must be unique." }
        require(0 < overview.viewport.width && 0 < overview.viewport.height && 0 < overview.scale) {
            "Overview viewport metadata must be positive."
        }
        components.forEach { scenario ->
            require(0 < scenario.viewport.width && 0 < scenario.viewport.height && 0 < scenario.scale) {
                "Component viewport metadata must be positive for ${scenario.component.apiMethodName}."
            }
        }
        require(screens.map { scenario -> scenario.screen } == DocumentedScreen.entries) {
            "Showcase screen catalog does not cover screens in typed order."
        }
        screens.forEach { scenario ->
            require(0 < scenario.viewportWidth && 0 < scenario.viewportHeight && scenario.scale == 1) {
                "Screen viewport metadata must be positive at scale one for ${scenario.screen.title}."
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

    private fun overviewSource(): SourceReference =
        SourceReference(
            "integration/minecraft-fabric-26.2/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftOverviewExample.kt",
            "overview",
        )

    private fun slotSource(): SourceReference =
        SourceReference(
            "integration/minecraft-fabric-26.2/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftSlotExample.kt",
            "slot",
        )

    private fun socialSource(): SourceReference =
        SourceReference(
            "integration/minecraft-fabric-26.2/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftSocialExample.kt",
            "social-screen",
        )

    private fun progressSource(): SourceReference =
        SourceReference(
            "integration/minecraft-fabric-26.2/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftProgressExample.kt",
            "progress-screen",
        )

    private fun industrialSource(): SourceReference =
        SourceReference(
            "integration/minecraft-fabric-26.2/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftIndustrialExample.kt",
            "industrial-screen",
        )

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
            is ShowcaseTreeDetail.StackAlign,
            is ShowcaseTreeDetail.GridAlign,
            is ShowcaseTreeDetail.Arrangement,
            is ShowcaseTreeDetail.RowDefaultAlignment,
            is ShowcaseTreeDetail.ColumnDefaultAlignment,
            is ShowcaseTreeDetail.StackContentAlignment,
            is ShowcaseTreeDetail.GridContentAlignment,
            -> Unit

            is ShowcaseTreeDetail.Size -> require(0 < detail.width && 0 < detail.height) { "Showcase sizes must be positive." }

            is ShowcaseTreeDetail.Height -> require(0 < detail.value) { "Showcase heights must be positive." }

            is ShowcaseTreeDetail.Padding -> require(0 <= detail.all) { "Showcase padding must be nonnegative." }

            is ShowcaseTreeDetail.Spacing -> require(0 <= detail.value) { "Showcase spacing must be nonnegative." }

            is ShowcaseTreeDetail.GridColumns -> require(0 < detail.value) { "Showcase Grid columns must be positive." }

            is ShowcaseTreeDetail.ScrollRate -> require(0 < detail.value) { "Showcase Scroll rates must be positive." }

            is ShowcaseTreeDetail.SlotHighlightable -> Unit

            is ShowcaseTreeDetail.Weight -> require(detail.weight.isFinite() && 0 < detail.weight) { "Showcase weights must be positive finite values." }
        }
    }
}
