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
     * @return UTF-8-ready LF Markdown with one terminal newline.
     */
    internal fun components(
        overview: ShowcaseOutput.Overview,
        sections: List<ShowcaseOutput.Section>,
    ): String =
        markdown(
            """<!-- Generated file. Do not edit. -->

# Minecraft component showcase

These deterministic crops come from real Minecraft 26.2 `ConfirmScreen`, `DirectJoinServerScreen`, `ContainerScreen`, and native `ObjectSelectionList` screens reconstructed with Strata's `Text`, `TextField`, `Button`, `Scroll`, and `Slot` components, plus a test Mod screen built with `Image` and a resource-pack asset.
The menu and generic-container images are active background modifiers on layout components rather than logical component entries.
One loaded Fabric GameTest requires exact ARGB equality among each native screen, the Fabric adapter, and the headless frame before it emits these component images.

[Open the machine-readable parity receipt](components/minecraft-26.2-parity.properties)

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

This deterministic image is the actual 320 by 180 `ConfirmScreen` reconstruction from the frame that passed exact native-screen, Fabric-adapter, and headless comparison.

![Strata component showcase](docs/components/overview.png)

### Overview source

```kotlin
${overview.source}
```

[Open the complete component showcase](docs/components.md)
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
        val paritySentence =
            when (spec.component) {
                DocumentedComponent.Image -> "This image is a ${spec.viewport.width} by ${spec.viewport.height} component crop from the exact Fabric/headless Mod-screen comparison recorded in [the verification receipt](components/minecraft-26.2-parity.properties)."

                DocumentedComponent.Text,
                DocumentedComponent.TextField,
                DocumentedComponent.Button,
                DocumentedComponent.Scroll,
                DocumentedComponent.Slot,
                -> "This image is a ${spec.viewport.width} by ${spec.viewport.height} component crop from the exact native/Fabric/headless parity frame recorded in [the verification receipt](components/minecraft-26.2-parity.properties)."
            }
        return markdown(
            """<a id="${spec.component.slug}"></a>

## ${spec.component.apiMethodName}

${typedSummary(spec.component)}

$paritySentence

![${spec.component.apiMethodName} headless showcase](components/${spec.component.slug}.png)

### Compiled example

```kotlin
$source
```

### Modifiers

${modifierGuidance(spec.component)}

### Parent scope

${parentScopeGuidance(spec.component)}

<details><summary>Component tree</summary>

The tree shows the featured Minecraft component; platform-neutral layout scaffolding remains visible in the compiled source.

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

    private fun modifierGuidance(component: DocumentedComponent): String =
        when (component) {
            DocumentedComponent.Slot -> "Sizing is native-fixed at 18 by 18. `Slot(bind = MinecraftSlots.playerInventory(index))` binds player inventory while `MinecraftSlots.activeMenu(index)` covers vanilla and custom container menus; the optional-content overload remains portable for custom item visuals."
            DocumentedComponent.Text -> "Ordinary sizing, padding, placement, and paint modifiers compose around `Text`; text content remains a typed component argument."
            DocumentedComponent.TextField -> "Pointer, keyboard, committed-character, preedit, and focus modifiers run as active retained behavior around `TextField`; a consuming focused modifier overrides built-in editing."
            DocumentedComponent.Button -> "Pointer behavior is active modifier behavior. `onPointerEvent`, `onPress`, `onRelease`, `onMove`, `onDrag`, `onScroll`, and `onHover` can be composed without adding component-specific callback parameters."
            DocumentedComponent.Scroll -> "Ordinary sizing and placement modifiers define the viewport. Pointer action modifiers compose outside the component while native wheel and scrollbar motion remain retained Scroll behavior."
            DocumentedComponent.Image -> "Sizing and placement modifiers compose around `Image`; `imageBackground` paints the same immutable resource behind any layout component with typed stretch or tile mapping."
        }

    private fun parentScopeGuidance(component: DocumentedComponent): String =
        when (component) {
            DocumentedComponent.Slot -> "`Slot` is a member extension on the active `UiScope`. Its optional callback emits at most one 16 by 16 content root, while its bound overload obtains the version platform implicitly and retains no public Minecraft type."
            DocumentedComponent.Text -> "`Text` is a top-level extension on the active `UiScope`. The screen runtime installs its selected Minecraft profile only for the definition callback, and the component has no content callback or parent-data API."
            DocumentedComponent.TextField -> "`TextField` is a member extension on the active `UiScope`. The implicit runtime context supplies assets, while caller-owned `MinecraftTextFieldState` owns the editable value."
            DocumentedComponent.Button -> "`Button` is a top-level extension on the active `UiScope`. The screen runtime installs its selected Minecraft profile only for the definition callback, and pointer event modifiers remain valid only through their retained modifier-node lifetime."
            DocumentedComponent.Scroll -> "`Scroll` is a member extension on the active `UiScope`. Its callback emits exactly one content root, remains callback-lifetime and owner-thread confined, and may use the same implicit Minecraft component DSL."
            DocumentedComponent.Image -> "`Image` is a top-level extension on the active `UiScope`. It retains detached pixels rather than a Minecraft resource object, so the Fabric loader may resolve a resource-pack replacement before the description is built."
        }

    private fun typedSummary(component: DocumentedComponent): String =
        when (component) {
            DocumentedComponent.Slot -> "Slot reproduces the native 18 by 18 hit region and 24 by 24 back-item-front highlight order; its binding overload polls real ItemStack state and delegates interaction through Minecraft's active container menu."
            DocumentedComponent.Text -> "Text renders a printable-ASCII literal with the extracted Minecraft glyph advances, shadow layer, foreground layer, and native baseline."
            DocumentedComponent.TextField -> "TextField reproduces the 200 by 20 Minecraft EditBox sprites, text origin, glyph colors, owner-thread value state, focus, and bounded editing behavior."
            DocumentedComponent.Button -> "Button renders verified fixed-height Minecraft sprite and label states, including the native 150- and 200-pixel widths, while reusable input actions live in modifiers."
            DocumentedComponent.Scroll -> "Scroll reproduces the verified Minecraft 26.2 menu-list background, clipped centered content, separators, tiled scrollbar sprites, wheel rate, and proportional thumb movement in native draw order."
            DocumentedComponent.Image -> "Image maps one immutable resource-pack image to an exact logical size with deterministic nearest sampling; it is reusable for icons, portraits, diagrams, and Mod-owned panels."
        }

    private fun details(details: List<ShowcaseTreeDetail>): String = if (details.isEmpty()) "" else " [${details.joinToString(", ") { detail -> ShowcaseDetailMarkdown.text(detail) }}]"

    private fun markdown(value: String): String = value.replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n') + "\n"
}
