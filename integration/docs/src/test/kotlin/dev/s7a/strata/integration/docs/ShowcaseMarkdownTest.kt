package dev.s7a.strata.integration.docs

import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.render.ArgbColor
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies byte-exact generated Markdown structure, typed guidance, and tree rendering.
 */
internal class ShowcaseMarkdownTest {
    @Test
    fun indexIsExactAndUsesOverviewSource() {
        val overview = ShowcaseOutput.Overview("import sample\ninternal fun overview() {}", "|- Text\n`- Button", byteArrayOf(1))
        val pages =
            DocumentedComponent.entries.map { component ->
                ShowcaseOutput.Page(component, component.slug, byteArrayOf(component.ordinal.toByte()))
            }
        val index = ShowcaseMarkdown.index(overview, pages)
        assertTrue(index.startsWith("<!-- Generated file. Do not edit. -->\n\n# Minecraft component showcase\n"))
        assertTrue(index.contains("real Minecraft 26.2 `ConfirmScreen`"))
        assertTrue(
            index.contains(
                "- [Text](text.md)\n- [TextField](text-field.md)\n- [Button](button.md)\n- [Scroll](scroll.md)\n- [Slot](slot.md)",
            ),
        )
        assertTrue(index.contains("The tree shows Minecraft components in logical draw order"))
        assertTrue(index.contains(overview.source))
        assertTrue(index.contains("exact ARGB equality"))
    }

    @Test
    fun rootRegionIsExactAndUsesOverviewSource() {
        val overview = ShowcaseOutput.Overview("import sample\ninternal fun overview() {}", "|- Text\n`- Button", byteArrayOf(1))
        val root = ShowcaseMarkdown.rootReadme(overview)
        assertTrue(root.contains("## Minecraft component showcase"))
        assertTrue(root.contains("actual 320 by 180 `ConfirmScreen` reconstruction"))
        assertTrue(root.contains("![Strata component showcase](docs/components/images/overview.png)"))
        assertTrue(root.contains(overview.source))
        assertTrue(root.contains("exact native-screen, Fabric-adapter, and headless comparison"))
    }

    @Test
    fun allComponentPagesMatchExactTypedFixturesAndSectionOrder() {
        val source = "import sample\ninternal fun example() {}"
        val pages =
            ShowcaseScenarioCatalog.components.associate { scenario ->
                scenario.component to ShowcaseMarkdown.page(scenario, source)
            }
        assertTrue(pages.getValue(DocumentedComponent.Slot).contains("back-content-front highlight order"))
        assertTrue(pages.getValue(DocumentedComponent.Text).contains("extracted Minecraft glyph advances, shadow layer, foreground layer, and native baseline"))
        assertTrue(pages.getValue(DocumentedComponent.TextField).contains("200 by 20 Minecraft EditBox sprites"))
        val button = pages.getValue(DocumentedComponent.Button)
        listOf("onPointerEvent", "onPress", "onRelease", "onMove", "onDrag", "onScroll", "onHover").forEach { action ->
            assertTrue(button.contains("`$action`"))
        }
        pages.values.forEach { value ->
            assertTrue(value.contains("<!-- Generated file. Do not edit. -->\n\n# "))
            assertTrue(value.contains("\n\n<details><summary>Component tree</summary>\n"))
            assertTrue(value.endsWith("\n"))
            assertTrue(value.endsWith("\n\n").not())
            assertTrue(value.contains('\r').not())
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
                    ShowcaseTreeDetail.BoxAlign(Alignment.BottomEnd),
                    ShowcaseTreeDetail.Spacing(3),
                    ShowcaseTreeDetail.Arrangement(Arrangement.SpaceBetween),
                    ShowcaseTreeDetail.RowDefaultAlignment(VerticalAlignment.Center),
                    ShowcaseTreeDetail.ColumnDefaultAlignment(HorizontalAlignment.Center),
                    ShowcaseTreeDetail.BoxContentAlignment(Alignment.Center),
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
            "BoxAlign(alignment=BottomEnd)",
            "Spacing(value=3)",
            "Arrangement(value=SpaceBetween)",
            "RowDefaultAlignment(alignment=Center)",
            "ColumnDefaultAlignment(alignment=Center)",
            "BoxContentAlignment(alignment=Center)",
            "ScrollRate(value=9)",
            "SlotHighlightable(value=true)",
        ).forEach { detail -> assertTrue(rendered.contains(detail)) }
        val buttonScenario = ShowcaseScenarioCatalog.components.single { scenario -> scenario.component == DocumentedComponent.Button }
        val buttonPage = ShowcaseMarkdown.page(buttonScenario, "import sample\ninternal fun button() {}")
        assertTrue(buttonPage.contains("# Button"))
        assertTrue(buttonPage.contains("reusable input actions live in modifiers"))
        assertTrue(buttonPage.contains("screen runtime installs its selected Minecraft profile only for the definition callback"))
        assertTrue(buttonPage.indexOf("# Button") < buttonPage.indexOf("![Button headless showcase]"))
    }
}
