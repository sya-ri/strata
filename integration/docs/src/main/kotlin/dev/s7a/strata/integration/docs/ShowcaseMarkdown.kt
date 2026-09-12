package dev.s7a.strata.integration.docs

/**
 * Produces deterministic Markdown for the component catalog and README preview.
 */
internal object ShowcaseMarkdown {
    /**
     * Builds the generated component document in catalog order.
     *
     * @param sections rendered component sections.
     * @return UTF-8-ready LF Markdown with one terminal newline.
     */
    internal fun components(
        sections: List<ShowcaseOutput.Section>,
    ): String =
        markdown(
            """<!-- Generated file. Do not edit. -->

# Component catalog

Choose components by the responsibility they serve in your screen.
Each entry shows a rendered example, practical composition guidance, and a link to the API reference for signatures and overloads.
Use the [complete screen examples](../examples/screens.md) to see how these primitives work together.

## Choose a component

${ShowcaseComponentGuide.comparison(sections)}

## Rendered examples

Images come from the compiled examples included with Strata.
Expand an entry for its source, modifier and parent-scope guidance, or component tree.
Menu and container backgrounds are modifiers on layout components.

${sections.joinToString("\n\n") { section -> section.section.trimEnd('\n') }}

## Image verification

Each image is the complete frame of its compiled dedicated `ScreenDefinition`, sampled at the stated physical density without upscaling or cropping.
Generation renders these definitions with the headless runtime and explicit Minecraft assets without starting Minecraft or creating a GPU context.
Animated examples use the frame at time zero; the independent native check accepts a complete supported animation phase.

The [headless render receipt](../components/headless-render.properties) records source, asset, viewport, and image hashes.
The separate [native parity receipt](../evidence/minecraft-26.2-parity.properties) records loaded-game comparisons.
See [documentation maintenance](../development/documentation.md) for regeneration and acceptance tasks.
""",
        )

    /**
     * Builds the generated root README region for the overview page.
     *
     * @return UTF-8-ready LF Markdown with one terminal newline.
     */
    internal fun rootReadme(): String =
        markdown(
            """<!-- Generated file. Do not edit. -->

## A screen built from components

A confirmation screen combines text, buttons, and layout components into a reusable UI definition.

![Strata component showcase](docs/components/overview.png)

[Compare components and browse their examples](docs/reference/components.md).
""",
        )

    /**
     * Builds one component section containing its image, source, modifier guidance, parent-scope guidance, and tree.
     *
     * @param spec typed catalog metadata.
     * @param source extracted compiled-example source.
     * @return UTF-8-ready LF Markdown with one terminal newline.
     */
    internal fun section(
        spec: ComponentScenario,
        source: String,
    ): String {
        val physical = spec.viewportMetadata.physicalSize
        val renderSentence =
            "${physical.width} by ${physical.height} PNG; ${spec.viewport.width} by ${spec.viewport.height} logical viewport at GUI scale ${spec.scale}. [Image verification](#image-verification)."
        return markdown(
            """<a id="${spec.component.slug}"></a>

## ${spec.component.apiMethodName}

${ShowcaseComponentGuide.purpose(spec.component)}

![${spec.component.apiMethodName} headless showcase](../components/${spec.component.slug}.png)

[API reference](${ShowcaseComponentGuide.apiUrl(spec.component)})

<details><summary>Usage and compiled example</summary>

${ComponentDocumentationCatalog.summary(spec.component)}

### Compiled example

```kotlin
$source
```

### Modifiers

${ComponentDocumentationCatalog.modifierGuidance(spec.component)}

### Parent scope

${ComponentDocumentationCatalog.parentScopeGuidance(spec.component)}

</details>

<details><summary>Component tree</summary>

```text
${tree(spec.tree)}
```

</details>

<details><summary>Image verification</summary>

$renderSentence

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

    /**
     * Renders ordered logical component roots after platform-neutral scaffolding is omitted.
     *
     * @param values ordered typed component roots.
     * @return deterministic ASCII forest text.
     */
    internal fun forest(values: List<ShowcaseTree>): String = values.mapIndexed { index, value -> tree(value, "", index == values.lastIndex) }.joinToString("\n")

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

    private fun details(details: List<ShowcaseTreeDetail>): String = if (details.isEmpty()) "" else " [${details.joinToString(", ") { detail -> ShowcaseDetailMarkdown.text(detail) }}]"

    private fun markdown(value: String): String = value.replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n') + "\n"
}
