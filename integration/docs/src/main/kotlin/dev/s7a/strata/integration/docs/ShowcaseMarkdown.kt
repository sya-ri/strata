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

This deterministic showcase is portable headless DrawCommand output, not a Minecraft screenshot or capture.

![Overview headless showcase](images/overview.png)

## Overview source

```kotlin
${overview.source}
```

<details><summary>Overview component tree</summary>

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

This deterministic JVM-headless output is not a Minecraft screenshot or capture.

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
    ): String {
        val featured = spec.tree.featured(spec.component)
        return markdown(
            """<!-- Generated file. Do not edit. -->

# ${spec.component.apiMethodName}

${typedSummary(featured)}

![${spec.component.apiMethodName} headless showcase](images/${spec.component.slug}.png)

## Compiled example

```kotlin
$source
```

## Modifiers

${modifierGuidance(spec.component, featured)}

## Parent scope

${parentScopeGuidance(spec.component)}

<details><summary>Component tree</summary>

```text
${tree(spec.tree)}
```

</details>
""",
        )
    }

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

    private fun modifierGuidance(
        component: DocumentedComponent,
        featured: ShowcaseTree,
    ): String {
        val parentDataSource =
            if (component == DocumentedComponent.Spacer) featured.details else featured.children.flatMap { child -> child.details }
        return listOf(
            "Generic modifiers used: ${ShowcaseDetailMarkdown.genericGuidance(featured.details)}.",
            "Component parameters shown: ${ShowcaseDetailMarkdown.componentParameterGuidance(featured.details)}.",
            "Direct-child parent data used: ${ShowcaseDetailMarkdown.parentDataGuidance(parentDataSource)}.",
        ).joinToString("\n")
    }

    private fun parentScopeGuidance(component: DocumentedComponent): String =
        when (component) {
            DocumentedComponent.Row -> "Row's content callback runs with RowScope; weight and vertical align modifiers apply only to direct children while that scope is active."
            DocumentedComponent.Column -> "Column's content callback runs with ColumnScope; weight and horizontal align modifiers apply only to direct children while that scope is active."
            DocumentedComponent.Box -> "Box's content callback runs with BoxScope; align applies only to direct children while that scope is active."
            DocumentedComponent.Spacer -> "Spacer has no content callback. In this example, its direct Box parent provides BoxScope.align while the parent callback is active."
        }

    private fun typedSummary(tree: ShowcaseTree): String =
        when (tree.component) {
            DocumentedComponent.Row -> "Row lays out direct children horizontally with typed spacing, arrangement, and vertical alignment."
            DocumentedComponent.Column -> "Column lays out direct children vertically with typed spacing, arrangement, and horizontal alignment."
            DocumentedComponent.Box -> "Box overlays direct children and positions each with its default alignment or a direct-child override."
            DocumentedComponent.Spacer -> "Spacer has no intrinsic size or paint; size and background modifiers make this example visible."
        }

    private fun details(details: List<ShowcaseTreeDetail>): String = if (details.isEmpty()) "" else " [${details.joinToString(", ") { detail -> ShowcaseDetailMarkdown.text(detail) }}]"

    private fun markdown(value: String): String = value.replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n') + "\n"
}
