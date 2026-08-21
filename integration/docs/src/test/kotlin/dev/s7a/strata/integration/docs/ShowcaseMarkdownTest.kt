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
    fun indexIsExactAndUsesOverviewSource() {
        val overview = ShowcaseOutput.Overview("import sample\ninternal fun overview() {}", "`- Column", byteArrayOf(1))
        val pages =
            listOf(
                ShowcaseOutput.Page(DocumentedComponent.Row, "row", byteArrayOf(2)),
                ShowcaseOutput.Page(DocumentedComponent.Column, "column", byteArrayOf(3)),
                ShowcaseOutput.Page(DocumentedComponent.Box, "box", byteArrayOf(4)),
                ShowcaseOutput.Page(DocumentedComponent.Spacer, "spacer", byteArrayOf(5)),
            )
        val index = ShowcaseMarkdown.index(overview, pages)
        assertEquals(
            """
            <!-- Generated file. Do not edit. -->

# Headless component showcase

These deterministic headless crops use the active Minecraft 26.2 menu texture, button sprites, ASCII font, component geometry, and logical draw order.
One loaded Fabric GameTest requires exact ARGB equality between the native screen, the Fabric adapter, and the headless frame before producing these files.

[Open the machine-readable parity receipt](minecraft-26.2-parity.properties)

![Overview headless showcase](images/overview.png)

## Overview source

```kotlin
import sample
internal fun overview() {}
```

<details><summary>Overview component tree</summary>

The tree shows layout components; Minecraft text and pointer-button leaves are omitted and remain visible in the compiled source.

```text
`- Column
```

</details>

## Components

- [Row](row.md)
- [Column](column.md)
- [Box](box.md)
- [Spacer](spacer.md)
            """.trimIndent().trimStart() + "\n",
            index,
        )
        assertTrue(index.contains(overview.source))
        assertTrue(index.contains("exact ARGB equality"))
    }

    @Test
    fun rootRegionIsExactAndUsesOverviewSource() {
        val overview = ShowcaseOutput.Overview("import sample\ninternal fun overview() {}", "`- Column", byteArrayOf(1))
        val root = ShowcaseMarkdown.rootReadme(overview)
        assertEquals(
            """
            <!-- Generated file. Do not edit. -->

## Headless component showcase

This deterministic headless crop uses Minecraft 26.2 assets and font pixels from the same frame that passed exact native-screen and Fabric-adapter comparison.

![Strata component showcase](docs/components/images/overview.png)

### Overview source

```kotlin
import sample
internal fun overview() {}
```

[Open the component showcase index](docs/components/README.md)
            """.trimIndent().trimStart() + "\n",
            root,
        )
        assertTrue(root.contains(overview.source))
        assertTrue(root.contains("exact native-screen and Fabric-adapter comparison"))
    }

    @Test
    fun allComponentPagesMatchExactTypedFixturesAndSectionOrder() {
        val source = "import sample\ninternal fun example() {}"
        val pages =
            ShowcaseScenarioCatalog.components.associate { scenario ->
                scenario.component to ShowcaseMarkdown.page(scenario, source)
            }
        val expected =
            mapOf(
                DocumentedComponent.Row to
                    expectedPage(
                        "Row",
                        "row",
                        source,
                        "Row lays out direct children horizontally with typed spacing, arrangement, and vertical alignment.",
                        "The compiled panel fixes its outer size, uses Spacer height modifiers for vertical placement, and sets Row spacing to 10. Its Minecraft button children use no RowScope parent data.",
                        "Row's content callback runs with RowScope; weight and vertical align modifiers apply only to direct children while that scope is active.",
                        "`- Column [Size(width=320, height=180), ColumnDefaultAlignment(alignment=Center)]\n  |- Spacer [Height(value=20)]\n  |- Spacer [Height(value=11)]\n  `- Row [Spacing(value=10)]",
                    ),
                DocumentedComponent.Column to
                    expectedPage(
                        "Column",
                        "column",
                        source,
                        "Column lays out direct children vertically with typed spacing, arrangement, and horizontal alignment.",
                        "The compiled panel fixes Column to 320 by 180, centers children horizontally, and uses Spacer height modifiers for exact native vertical placement.",
                        "Column's content callback runs with ColumnScope; weight and horizontal align modifiers apply only to direct children while that scope is active.",
                        "`- Column [Size(width=320, height=180), ColumnDefaultAlignment(alignment=Center)]\n  |- Spacer [Height(value=20)]\n  |- Spacer [Height(value=11)]\n  `- Spacer [Height(value=4)]",
                    ),
                DocumentedComponent.Box to
                    expectedPage(
                        "Box",
                        "box",
                        source,
                        "Box overlays direct children and positions each with its default alignment or a direct-child override.",
                        "The compiled panel fixes Box to 320 by 180 with Center as its default. Its two pointer-button children use BoxScope.align with TopStart and BottomEnd overrides.",
                        "Box's content callback runs with BoxScope; align applies only to direct children while that scope is active.",
                        "`- Box [Size(width=320, height=180), BoxContentAlignment(alignment=Center)]",
                    ),
                DocumentedComponent.Spacer to
                    expectedPage(
                        "Spacer",
                        "spacer",
                        source,
                        "Spacer has no intrinsic size or paint; height modifiers make the native vertical gaps visible around Minecraft text and buttons.",
                        "The compiled panel uses Spacer height modifiers to reproduce native vertical gaps. Spacer remains an intrinsic-zero, non-painting leaf.",
                        "Spacer has no content callback or Spacer-specific parent-data API. In this example it is a direct Column child, while its ordinary height modifier is not Column parent data.",
                        "`- Column [Size(width=320, height=180), ColumnDefaultAlignment(alignment=Center)]\n  |- Spacer [Height(value=20)]\n  `- Spacer [Height(value=51)]",
                    ),
            )
        expected.forEach { (component, fixture) -> assertEquals(fixture, pages.getValue(component)) }
        expected.values.forEach { value ->
            assertTrue(value.contains("<!-- Generated file. Do not edit. -->\n\n# "))
            assertTrue(value.contains("\n\n<details><summary>Component tree</summary>\n"))
            assertTrue(value.endsWith("\n"))
            assertTrue(value.endsWith("\n\n").not())
            assertTrue(value.contains('\r').not())
        }
    }

    @Test
    fun everyTypedTreeDetailRendersWithFixedArgbAndSpacerFeatureSelection() {
        val tree =
            ShowcaseTree(
                DocumentedComponent.Box,
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
        ).forEach { detail -> assertTrue(rendered.contains(detail)) }
        val spacerPage = ShowcaseMarkdown.page(ShowcaseScenarioCatalog.components.last(), "import sample\ninternal fun spacer() {}")
        assertTrue(spacerPage.contains("# Spacer"))
        assertTrue(spacerPage.contains("Spacer has no intrinsic size or paint"))
        assertTrue(spacerPage.contains("direct Column child"))
        assertTrue(spacerPage.indexOf("# Spacer") < spacerPage.indexOf("![Spacer headless showcase]"))
    }

    private fun expectedPage(
        title: String,
        slug: String,
        source: String,
        summary: String,
        modifiers: String,
        parentScope: String,
        tree: String,
    ): String =
        """
        <!-- Generated file. Do not edit. -->

# $title

$summary

This image is a 320 by 180 crop from the exact native/Fabric/headless parity frame recorded in [the verification receipt](minecraft-26.2-parity.properties).

![$title headless showcase](images/$slug.png)

## Compiled example

```kotlin
$source
```

## Modifiers

$modifiers

## Parent scope

$parentScope

<details><summary>Component tree</summary>

The tree shows layout components; Minecraft text and pointer-button leaves are omitted and remain visible in the compiled source.

```text
$tree
```

</details>
        """.trimIndent().trimStart() + "\n"
}
