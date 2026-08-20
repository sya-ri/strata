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

This deterministic showcase is portable headless DrawCommand output, not a Minecraft screenshot or capture.

![Overview headless showcase](images/overview.png)

## Overview source

```kotlin
import sample
internal fun overview() {}
```

<details><summary>Overview component tree</summary>

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
        assertTrue(index.contains("not a Minecraft screenshot or capture"))
    }

    @Test
    fun rootRegionIsExactAndUsesOverviewSource() {
        val overview = ShowcaseOutput.Overview("import sample\ninternal fun overview() {}", "`- Column", byteArrayOf(1))
        val root = ShowcaseMarkdown.rootReadme(overview)
        assertEquals(
            """
            <!-- Generated file. Do not edit. -->

## Headless component showcase

This deterministic JVM-headless output is not a Minecraft screenshot or capture.

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
        assertTrue(root.contains("not a Minecraft screenshot or capture"))
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
                        "Generic modifiers used: FillMaxSize, Background(color=0xFF111827), Padding(all=4).\nComponent parameters shown: Spacing(value=2), Arrangement(value=SpaceEvenly), RowDefaultAlignment(alignment=Center).\nDirect-child parent data used: Weight(weight=1.0, fill=false), RowAlign(alignment=Bottom).",
                        "Row's content callback runs with RowScope; weight and vertical align modifiers apply only to direct children while that scope is active.",
                        "`- Row [FillMaxSize, Background(color=0xFF111827), Padding(all=4), Spacing(value=2), Arrangement(value=SpaceEvenly), RowDefaultAlignment(alignment=Center)]\n  |- Spacer [Size(width=12, height=12), Background(color=0xFF22D3EE)]\n  |- Spacer [Size(width=14, height=16), Background(color=0xFFA78BFA), Weight(weight=1.0, fill=false)]\n  `- Spacer [Size(width=12, height=8), Background(color=0xFFFBBF24), RowAlign(alignment=Bottom)]",
                    ),
                DocumentedComponent.Column to
                    expectedPage(
                        "Column",
                        "column",
                        source,
                        "Column lays out direct children vertically with typed spacing, arrangement, and horizontal alignment.",
                        "Generic modifiers used: FillMaxSize, Background(color=0xFF111827), Padding(all=4).\nComponent parameters shown: Spacing(value=2), Arrangement(value=SpaceAround), ColumnDefaultAlignment(alignment=Center).\nDirect-child parent data used: Weight(weight=1.0, fill=false), ColumnAlign(alignment=End).",
                        "Column's content callback runs with ColumnScope; weight and horizontal align modifiers apply only to direct children while that scope is active.",
                        "`- Column [FillMaxSize, Background(color=0xFF111827), Padding(all=4), Spacing(value=2), Arrangement(value=SpaceAround), ColumnDefaultAlignment(alignment=Center)]\n  |- Spacer [Size(width=12, height=12), Background(color=0xFF22D3EE)]\n  |- Spacer [Size(width=14, height=16), Background(color=0xFFA78BFA), Weight(weight=1.0, fill=false)]\n  `- Spacer [Size(width=12, height=8), Background(color=0xFFFBBF24), ColumnAlign(alignment=End)]",
                    ),
                DocumentedComponent.Box to
                    expectedPage(
                        "Box",
                        "box",
                        source,
                        "Box overlays direct children and positions each with its default alignment or a direct-child override.",
                        "Generic modifiers used: FillMaxSize, Background(color=0xFF111827), Padding(all=4).\nComponent parameters shown: BoxContentAlignment(alignment=Center).\nDirect-child parent data used: BoxAlign(alignment=TopStart), BoxAlign(alignment=BottomEnd).",
                        "Box's content callback runs with BoxScope; align applies only to direct children while that scope is active.",
                        "`- Box [FillMaxSize, Background(color=0xFF111827), Padding(all=4), BoxContentAlignment(alignment=Center)]\n  |- Spacer [Size(width=28, height=16), Background(color=0xFF22D3EE), BoxAlign(alignment=TopStart)]\n  |- Spacer [Size(width=36, height=20), Background(color=0xFFA78BFA)]\n  `- Spacer [Size(width=20, height=12), Background(color=0xFFFBBF24), BoxAlign(alignment=BottomEnd)]",
                    ),
                DocumentedComponent.Spacer to
                    expectedPage(
                        "Spacer",
                        "spacer",
                        source,
                        "Spacer has no intrinsic size or paint; size and background modifiers make this example visible.",
                        "Generic modifiers used: Size(width=36, height=12), Background(color=0xFFFB7185).\nComponent parameters shown: none.\nDirect-child parent data used: BoxAlign(alignment=Center).",
                        "Spacer has no content callback. In this example, its direct Box parent provides BoxScope.align while the parent callback is active.",
                        "`- Box [FillMaxSize, Background(color=0xFF111827), Padding(all=4), BoxContentAlignment(alignment=Center)]\n  `- Spacer [Size(width=36, height=12), Background(color=0xFFFB7185), BoxAlign(alignment=Center)]",
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
        assertTrue(spacerPage.contains("BoxScope.align"))
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

```text
$tree
```

</details>
        """.trimIndent().trimStart() + "\n"
}
