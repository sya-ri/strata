package dev.s7a.strata.integration.docs

import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.render.ArgbColor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies byte-exact generated Markdown structure, typed guidance, and tree rendering.
 */
internal class ShowcaseMarkdownTest {
    @Test
    fun combinedDocumentIsExactAndUsesOverviewSource() {
        val overview = ShowcaseOutput.Overview("import sample\ninternal fun overview() {}", "|- Text\n`- Button", byteArrayOf(1))
        val sections =
            ShowcaseScenarioCatalog.components.map { scenario ->
                ShowcaseOutput.Section(
                    scenario.component,
                    ShowcaseMarkdown.section(scenario, "import sample\ninternal fun example() {}"),
                    byteArrayOf(scenario.component.ordinal.toByte()),
                )
            }
        val screens =
            ShowcaseScenarioCatalog.screens.map { scenario ->
                ShowcaseOutput.Screen(
                    scenario.screen,
                    ShowcaseScreenMarkdown.section(scenario, "import sample\ninternal fun screen() {}"),
                    byteArrayOf(scenario.screen.ordinal.toByte()),
                )
            }
        val document = ShowcaseMarkdown.components(overview, sections, screens)
        assertTrue(document.startsWith("<!-- Generated file. Do not edit. -->\n\n# Minecraft component showcase\n"))
        assertTrue(document.contains("real Minecraft 26.2 `ConfirmScreen`"))
        val componentLinks = DocumentedComponent.entries.joinToString("\n") { component -> "- [${component.apiMethodName}](#${component.slug})" }
        assertTrue(document.contains(componentLinks))
        assertTrue(document.contains("The tree shows Minecraft components in logical draw order"))
        assertTrue(document.contains(overview.source))
        assertTrue(document.contains("exact ARGB equality"))
        assertEquals(1, "<!-- Generated file. Do not edit. -->".toRegex().findAll(document).count())
        DocumentedComponent.entries.forEach { component -> assertTrue(document.contains("<a id=\"${component.slug}\"></a>")) }
        DocumentedScreen.entries.forEach { screen -> assertTrue(document.contains("<a id=\"screen-${screen.slug}\"></a>")) }
    }

    @Test
    fun rootRegionIsExactAndUsesOverviewSource() {
        val overview = ShowcaseOutput.Overview("import sample\ninternal fun overview() {}", "|- Text\n`- Button", byteArrayOf(1))
        val root = ShowcaseMarkdown.rootReadme(overview)
        assertTrue(root.contains("## Minecraft component showcase"))
        assertTrue(root.contains("actual 320 by 180 `ConfirmScreen` reconstruction"))
        assertTrue(root.contains("![Strata component showcase](docs/components/overview.png)"))
        assertTrue(root.contains(overview.source))
        assertTrue(root.contains("exact native-screen, Fabric-adapter, and headless comparison"))
    }

    @Test
    fun allComponentSectionsMatchExactTypedFixturesAndSectionOrder() {
        val source = "import sample\ninternal fun example() {}"
        val sections =
            ShowcaseScenarioCatalog.components.associate { scenario ->
                scenario.component to ShowcaseMarkdown.section(scenario, source)
            }
        assertTrue(sections.getValue(DocumentedComponent.Slot).contains("back-item-front highlight order"))
        assertTrue(sections.getValue(DocumentedComponent.Text).contains("extracted Minecraft glyph advances, shadow layer, foreground layer, and native baseline"))
        assertTrue(sections.getValue(DocumentedComponent.TextField).contains("200 by 20 Minecraft EditBox sprites"))
        assertTrue(sections.getValue(DocumentedComponent.PlayerHead).contains("face-then-hat"))
        assertTrue(sections.getValue(DocumentedComponent.Grid).contains("incomplete final row"))
        assertTrue(sections.getValue(DocumentedComponent.Tab).contains("external selection semantics"))
        assertTrue(sections.getValue(DocumentedComponent.Stack).contains("not a generic div-like container"))
        val button = sections.getValue(DocumentedComponent.Button)
        listOf("onPointerEvent", "onPress", "onRelease", "onMove", "onDrag", "onScroll", "onHover").forEach { action ->
            assertTrue(button.contains("`$action`"))
        }
        sections.values.forEach { value ->
            assertTrue(value.startsWith("<a id=\""))
            assertTrue(value.contains("\n\n## "))
            assertTrue(value.contains("\n\n<details><summary>Component tree</summary>\n"))
            assertTrue(value.endsWith("\n"))
            assertTrue(value.endsWith("\n\n").not())
            assertTrue(value.contains('\r').not())
        }
    }

    @Test
    fun allCompleteScreenSectionsStateEvidenceAndPrimitiveBoundaryTruthfully() {
        val sections =
            ShowcaseScenarioCatalog.screens.associate { scenario ->
                scenario.screen to ShowcaseScreenMarkdown.section(scenario, "import sample\ninternal fun screen() {}")
            }

        assertTrue(sections.getValue(DocumentedScreen.SocialInteractions).contains("exact ARGB equality between the native Minecraft screen"))
        assertTrue(sections.getValue(DocumentedScreen.SocialInteractions).contains("without introducing a purpose-specific SocialEntry component"))
        assertTrue(sections.getValue(DocumentedScreen.SynchronizedInventory).contains("loaded Fabric client/server GameTest"))
        assertTrue(sections.getValue(DocumentedScreen.SynchronizedInventory).contains("ender-chest, furnace, or custom inventory"))
        assertTrue(sections.getValue(DocumentedScreen.IndustrialController).contains("resource-pack-aware Mod controller"))
        assertTrue(sections.getValue(DocumentedScreen.PowerMilestones).contains("ExampleProgressGraph` deliberately stays in downstream example code"))
        sections.forEach { (screen, value) ->
            assertTrue(value.contains("![${screen.title} screen showcase](components/screen-${screen.slug}.png)"))
            assertTrue(value.contains("### Compiled screen"))
            assertTrue(value.contains("### Primitive boundary"))
            assertTrue(value.endsWith("\n"))
        }
    }

    @Test
    fun everyTypedTreeDetailRendersWithFixedArgbAndMinecraftFeatureSelection() {
        val tree =
            ShowcaseTree(
                DocumentedComponent.Text,
                listOf(
                    ShowcaseTreeDetail.FillMaxSize,
                    ShowcaseTreeDetail.Size(7, 9),
                    ShowcaseTreeDetail.Height(11),
                    ShowcaseTreeDetail.Padding(2),
                    ShowcaseTreeDetail.Background(ArgbColor(0x80ABCDEF.toInt())),
                    ShowcaseTreeDetail.Weight(1.5f, false),
                    ShowcaseTreeDetail.RowAlign(VerticalAlignment.Bottom),
                    ShowcaseTreeDetail.ColumnAlign(HorizontalAlignment.End),
                    ShowcaseTreeDetail.StackAlign(Alignment.BottomEnd),
                    ShowcaseTreeDetail.GridAlign(Alignment.CenterStart),
                    ShowcaseTreeDetail.GridColumns(9),
                    ShowcaseTreeDetail.Spacing(3),
                    ShowcaseTreeDetail.Arrangement(Arrangement.SpaceBetween),
                    ShowcaseTreeDetail.RowDefaultAlignment(VerticalAlignment.Center),
                    ShowcaseTreeDetail.ColumnDefaultAlignment(HorizontalAlignment.Center),
                    ShowcaseTreeDetail.StackContentAlignment(Alignment.Center),
                    ShowcaseTreeDetail.GridContentAlignment(Alignment.TopStart),
                    ShowcaseTreeDetail.ScrollRate(9),
                    ShowcaseTreeDetail.SlotHighlightable(true),
                ),
            )
        val rendered = ShowcaseMarkdown.tree(tree)
        listOf(
            "FillMaxSize",
            "Size(width=7, height=9)",
            "Height(value=11)",
            "Padding(all=2)",
            "Background(color=0x80ABCDEF)",
            "Weight(weight=1.5, fill=false)",
            "RowAlign(alignment=Bottom)",
            "ColumnAlign(alignment=End)",
            "StackAlign(alignment=BottomEnd)",
            "GridAlign(alignment=CenterStart)",
            "GridColumns(value=9)",
            "Spacing(value=3)",
            "Arrangement(value=SpaceBetween)",
            "RowDefaultAlignment(alignment=Center)",
            "ColumnDefaultAlignment(alignment=Center)",
            "StackContentAlignment(alignment=Center)",
            "GridContentAlignment(alignment=TopStart)",
            "ScrollRate(value=9)",
            "SlotHighlightable(value=true)",
        ).forEach { detail -> assertTrue(rendered.contains(detail)) }
        val buttonScenario = ShowcaseScenarioCatalog.components.single { scenario -> scenario.component == DocumentedComponent.Button }
        val buttonPage = ShowcaseMarkdown.section(buttonScenario, "import sample\ninternal fun button() {}")
        assertTrue(buttonPage.contains("## Button"))
        assertTrue(buttonPage.contains("reusable input actions live in modifiers"))
        assertTrue(buttonPage.contains("screen runtime installs its selected Minecraft profile only for the definition callback"))
        assertTrue(buttonPage.indexOf("## Button") < buttonPage.indexOf("![Button headless showcase]"))
    }
}
