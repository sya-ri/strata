package dev.s7a.strata.integration.docs

/**
 * Produces deterministic Markdown for the combined component showcase and README region.
 */
internal object ShowcaseMarkdown {
    /**
     * Builds the generated component document in catalog order.
     *
     * @param overview rendered overview output.
     * @param sections rendered component sections.
     * @param screens verified complete-screen use cases.
     * @return UTF-8-ready LF Markdown with one terminal newline.
     */
    internal fun components(
        overview: ShowcaseOutput.Overview,
        sections: List<ShowcaseOutput.Section>,
        screens: List<ShowcaseOutput.Screen>,
    ): String =
        markdown(
            """<!-- Generated file. Do not edit. -->

# Minecraft component showcase

Each component image is the complete frame of the dedicated minimal `ScreenDefinition` shown in its compiled example, containing the featured primitive and only the parent layout needed to demonstrate its responsibility.
The documentation task freshly renders these definitions with the headless runtime and explicit Minecraft asset files without starting Minecraft or creating a GPU context, and publishes the entire resulting frame without cropping a larger showcase screen.
Separate native full-screen parity scenes for `ConfirmScreen`, `DirectJoinServerScreen`, `ContainerScreen`, Social Interactions, and `ObjectSelectionList`, plus complete test Mod screens, remain independent acceptance evidence for real assets, fonts, textures, placement, and draw order.
Animated examples publish the canonical frame at time zero; their native capture must exactly match a complete supported animation phase, which need not be the stored phase.
The synchronized inventory image is the explicit exception: generation verifies a previously captured native image against its image and current compiled-source hashes instead of emulating a loaded server.
The menu and generic-container images are active background modifiers on layout components rather than logical component entries.

[Open the deterministic headless render receipt](components/headless-render.properties)

[Open the independent native parity receipt](evidence/minecraft-26.2-parity.properties)

![Overview headless showcase](components/overview.png)

## Overview source

```kotlin
${overview.source}
```

<details><summary>Overview component tree</summary>

The tree shows Minecraft components in logical draw order; platform-neutral layout scaffolding remains visible in the compiled source.

```text
${overview.tree}
```

</details>

## Components

${sections.joinToString("\n") { section -> "- [${section.title}](#${section.slug})" }}

${sections.joinToString("\n\n") { section -> section.section.trimEnd('\n') }}

## Complete screens

These screens exercise the primitives in real vanilla-shaped and Mod-shaped use cases.
Purpose-specific compositions stay in the compiled examples instead of becoming standard components; reusable capabilities remain available as general layout, image, text, input, slot-binding, and player-rendering primitives.

${screens.joinToString("\n") { screen -> "- [${screen.title}](#${screen.slug})" }}

${screens.joinToString("\n\n") { screen -> screen.section.trimEnd('\n') }}
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

## Minecraft component showcase

This deterministic image is a fresh 320 by 180 headless `ConfirmScreen` reconstruction using explicit Minecraft asset files.
Generation does not start Minecraft or create a GPU context; native-screen, Fabric-adapter, and headless comparisons run in a separate [acceptance gate](docs/evidence/minecraft-26.2-parity.properties).

![Strata component showcase](docs/components/overview.png)

### Overview source

```kotlin
${overview.source}
```

[Open the complete component showcase](docs/components.md)
""",
        )

    /**
     * Builds one component section containing its admission rationale, image, source, modifier guidance, parent-scope guidance, and tree.
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
            "This ${physical.width} by ${physical.height} PNG is the complete frame of the compiled dedicated `ScreenDefinition`, with a ${spec.viewport.width} by ${spec.viewport.height} logical viewport at GUI scale ${spec.scale}. Headless rendering samples the assets at this physical density; the image is not upscaled from a lower-resolution raster or cropped from a larger screen. Its source, asset, viewport, and image hashes are recorded in [the headless render receipt](components/headless-render.properties)."
        return markdown(
            """<a id="${spec.component.slug}"></a>

## ${spec.component.apiMethodName}

${ComponentDocumentationCatalog.summary(spec.component)}

### Built-in admission

${ComponentDocumentationCatalog.admissionGuidance(spec.component)}

$renderSentence

![${spec.component.apiMethodName} headless showcase](components/${spec.component.slug}.png)

### Compiled example

```kotlin
$source
```

### Modifiers

${ComponentDocumentationCatalog.modifierGuidance(spec.component)}

### Parent scope

${ComponentDocumentationCatalog.parentScopeGuidance(spec.component)}

<details><summary>Component tree</summary>

The tree mirrors the complete dedicated definition, including the featured component, its minimum parent layout, and the children used to demonstrate its responsibility.

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
