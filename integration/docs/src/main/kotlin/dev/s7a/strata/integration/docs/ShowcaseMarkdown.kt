package dev.s7a.strata.integration.docs

/**
 * Produces deterministic Markdown for the component showcase pages and README region.
 */
internal object ShowcaseMarkdown {
    /**
     * Builds the generated component index in catalog order.
     *
     * @param overview rendered overview output.
     * @param pages rendered component pages.
     * @return UTF-8-ready LF Markdown with one terminal newline.
     */
    internal fun index(
        overview: ShowcaseOutput.Overview,
        pages: List<ShowcaseOutput.Page>,
    ): String =
        markdown(
            """<!-- Generated file. Do not edit. -->

# Headless component showcase

These deterministic headless crops use the active Minecraft 26.2 menu texture, button sprites, ASCII font, component geometry, and logical draw order.
One loaded Fabric GameTest requires exact ARGB equality between the native screen, the Fabric adapter, and the headless frame before producing these files.

[Open the machine-readable parity receipt](minecraft-26.2-parity.properties)

![Overview headless showcase](images/overview.png)

## Overview source

```kotlin
${overview.source}
```

<details><summary>Overview component tree</summary>

The tree shows layout components; Minecraft text and pointer-button leaves are omitted and remain visible in the compiled source.

```text
${overview.tree}
```

</details>

## Components

${pages.joinToString("\n") { page -> "- [${page.title}](${page.slug}.md)" }}
""",
        )

    /**
     * Builds the generated root README region for the overview page.
     *
     * @param overview rendered overview output.
     * @return UTF-8-ready LF Markdown with one terminal newline.
     */
    internal fun rootReadme(overview: ShowcaseOutput.Overview): String =
        markdown(
            """<!-- Generated file. Do not edit. -->

## Headless component showcase

This deterministic headless crop uses Minecraft 26.2 assets and font pixels from the same frame that passed exact native-screen and Fabric-adapter comparison.

![Strata component showcase](docs/components/images/overview.png)

### Overview source

```kotlin
${overview.source}
```

[Open the component showcase index](docs/components/README.md)
""",
        )

    /**
     * Builds one component page containing its image, source, modifier guidance, parent-scope guidance, and tree.
     *
     * @param spec typed catalog metadata.
     * @param source extracted compiled-example source.
     * @return UTF-8-ready LF Markdown with one terminal newline.
     */
    internal fun page(
        spec: ComponentScenario,
        source: String,
    ): String =
        markdown(
            """<!-- Generated file. Do not edit. -->

# ${spec.component.apiMethodName}

${typedSummary(spec.component)}

This image is a 320 by 180 crop from the exact native/Fabric/headless parity frame recorded in [the verification receipt](minecraft-26.2-parity.properties).

![${spec.component.apiMethodName} headless showcase](images/${spec.component.slug}.png)

## Compiled example

```kotlin
$source
```

## Modifiers

${modifierGuidance(spec.component)}

## Parent scope

${parentScopeGuidance(spec.component)}

<details><summary>Component tree</summary>

The tree shows layout components; Minecraft text and pointer-button leaves are omitted and remain visible in the compiled source.

```text
${tree(spec.tree)}
```

</details>
""",
        )

    /**
     * Renders a logical tree for the overview and page metadata blocks.
     *
     * @param value typed logical tree.
     * @return deterministic ASCII tree text.
     */
    internal fun tree(value: ShowcaseTree): String = tree(value, "", true)

    private fun tree(
        value: ShowcaseTree,
        prefix: String,
        last: Boolean,
    ): String {
        val lines = ArrayList<String>()
        val branch = if (last) "`-" else "|-"
        lines += "$prefix$branch ${value.component.apiMethodName}${details(value.details)}"
        value.children.forEachIndexed { index, child ->
            val childLast = index == value.children.lastIndex
            val childPrefix = prefix + if (last) "  " else "| "
            lines += tree(child, childPrefix, childLast)
        }
        return lines.joinToString("\n")
    }

    private fun modifierGuidance(component: DocumentedComponent): String =
        when (component) {
            DocumentedComponent.Row -> "The compiled panel fixes its outer size, uses Spacer height modifiers for vertical placement, and sets Row spacing to 10. Its Minecraft button children use no RowScope parent data."
            DocumentedComponent.Column -> "The compiled panel fixes Column to 320 by 180, centers children horizontally, and uses Spacer height modifiers for exact native vertical placement."
            DocumentedComponent.Box -> "The compiled panel fixes Box to 320 by 180 with Center as its default. Its two pointer-button children use BoxScope.align with TopStart and BottomEnd overrides."
            DocumentedComponent.Spacer -> "The compiled panel uses Spacer height modifiers to reproduce native vertical gaps. Spacer remains an intrinsic-zero, non-painting leaf."
        }

    private fun parentScopeGuidance(component: DocumentedComponent): String =
        when (component) {
            DocumentedComponent.Row -> "Row's content callback runs with RowScope; weight and vertical align modifiers apply only to direct children while that scope is active."
            DocumentedComponent.Column -> "Column's content callback runs with ColumnScope; weight and horizontal align modifiers apply only to direct children while that scope is active."
            DocumentedComponent.Box -> "Box's content callback runs with BoxScope; align applies only to direct children while that scope is active."
            DocumentedComponent.Spacer -> "Spacer has no content callback or Spacer-specific parent-data API. In this example it is a direct Column child, while its ordinary height modifier is not Column parent data."
        }

    private fun typedSummary(component: DocumentedComponent): String =
        when (component) {
            DocumentedComponent.Row -> "Row lays out direct children horizontally with typed spacing, arrangement, and vertical alignment."
            DocumentedComponent.Column -> "Column lays out direct children vertically with typed spacing, arrangement, and horizontal alignment."
            DocumentedComponent.Box -> "Box overlays direct children and positions each with its default alignment or a direct-child override."
            DocumentedComponent.Spacer -> "Spacer has no intrinsic size or paint; height modifiers make the native vertical gaps visible around Minecraft text and buttons."
        }

    private fun details(details: List<ShowcaseTreeDetail>): String = if (details.isEmpty()) "" else " [${details.joinToString(", ") { detail -> ShowcaseDetailMarkdown.text(detail) }}]"

    private fun markdown(value: String): String = value.replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n') + "\n"
}
