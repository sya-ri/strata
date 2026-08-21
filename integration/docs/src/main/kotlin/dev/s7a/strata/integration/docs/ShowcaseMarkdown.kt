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

# Minecraft component showcase

These deterministic crops come from real Minecraft 26.2 `ConfirmScreen`, `DirectJoinServerScreen`, `ContainerScreen`, and native `ObjectSelectionList` screens reconstructed with Strata's `MenuBackground`, `ContainerBackground`, `Slot`, `Text`, `TextField`, `Button`, and `Scroll` components.
One loaded Fabric GameTest requires exact ARGB equality among each native screen, the Fabric adapter, and the headless frame before it emits these component images.

[Open the machine-readable parity receipt](minecraft-26.2-parity.properties)

![Overview headless showcase](images/overview.png)

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

## Minecraft component showcase

This deterministic image is the actual 320 by 180 `ConfirmScreen` reconstruction from the frame that passed exact native-screen, Fabric-adapter, and headless comparison.

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

This image is a ${spec.viewport.width} by ${spec.viewport.height} component crop from the exact native/Fabric/headless parity frame recorded in [the verification receipt](minecraft-26.2-parity.properties).

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

The tree shows the featured Minecraft component; platform-neutral layout scaffolding remains visible in the compiled source.

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
            DocumentedComponent.MenuBackground -> "`Modifier.fillMaxSize()` supplies the finite viewport that `MenuBackground` fills with the active Minecraft menu texture."
            DocumentedComponent.ContainerBackground -> "Sizing and placement modifiers position the fixed 176-pixel-wide panel; `rows` selects the native one-through-six-row height and texture regions."
            DocumentedComponent.Slot -> "Sizing is native-fixed at 18 by 18. Pointer action modifiers compose around `Slot`, while `highlightable` controls only its built-in back/front hover layers."
            DocumentedComponent.Text -> "Ordinary sizing, padding, placement, and paint modifiers compose around `Text`; text content remains a typed component argument."
            DocumentedComponent.TextField -> "Pointer, keyboard, committed-character, preedit, and focus modifiers run as active retained behavior around `TextField`; a consuming focused modifier overrides built-in editing."
            DocumentedComponent.Button -> "Pointer behavior is active modifier behavior. `onPointerEvent`, `onPress`, `onRelease`, `onMove`, `onDrag`, `onScroll`, and `onHover` can be composed without adding component-specific callback parameters."
            DocumentedComponent.Scroll -> "Ordinary sizing and placement modifiers define the viewport. Pointer action modifiers compose outside the component while native wheel and scrollbar motion remain retained Scroll behavior."
        }

    private fun parentScopeGuidance(component: DocumentedComponent): String =
        when (component) {
            DocumentedComponent.MenuBackground -> "`MenuBackground` is a member extension on the active `UiScope`. The runtime supplies `MinecraftUiContext` implicitly for the screen-content callback; application code never names or retains it."
            DocumentedComponent.ContainerBackground -> "`ContainerBackground` is a leaf member extension on the active `UiScope`. The implicit runtime context supplies the selected `generic_54.png` snapshot, and the component exposes no content scope or parent data."
            DocumentedComponent.Slot -> "`Slot` is a member extension on the active `UiScope`. Its optional callback emits at most one 16 by 16 content root between the native highlight-back and highlight-front phases."
            DocumentedComponent.Text -> "`Text` is a member extension on the active `UiScope`. The runtime supplies `MinecraftUiContext` implicitly, and the component has no content callback or parent-data API."
            DocumentedComponent.TextField -> "`TextField` is a member extension on the active `UiScope`. The implicit runtime context supplies assets, while caller-owned `MinecraftTextFieldState` owns the editable value."
            DocumentedComponent.Button -> "`Button` is a member extension on the active `UiScope`. The runtime supplies `MinecraftUiContext` implicitly, and pointer event modifiers remain valid only through their retained modifier-node lifetime."
            DocumentedComponent.Scroll -> "`Scroll` is a member extension on the active `UiScope`. Its callback emits exactly one content root, remains callback-lifetime and owner-thread confined, and may use the same implicit Minecraft component DSL."
        }

    private fun typedSummary(component: DocumentedComponent): String =
        when (component) {
            DocumentedComponent.MenuBackground -> "MenuBackground paints the selected Minecraft menu texture with the same tiling, clipping, and draw order as the verified native screen."
            DocumentedComponent.ContainerBackground -> "ContainerBackground reproduces the generic chest panel's row-dependent height and exact upper-then-lower `generic_54.png` blits."
            DocumentedComponent.Slot -> "Slot reproduces the native 18 by 18 hit region and the 24 by 24 back-content-front highlight order used by an actual empty chest screen."
            DocumentedComponent.Text -> "Text renders a printable-ASCII literal with the extracted Minecraft glyph advances, shadow layer, foreground layer, and native baseline."
            DocumentedComponent.TextField -> "TextField reproduces the 200 by 20 Minecraft EditBox sprites, text origin, glyph colors, owner-thread value state, focus, and bounded editing behavior."
            DocumentedComponent.Button -> "Button renders verified fixed-height Minecraft sprite and label states, including the native 150- and 200-pixel widths, while reusable input actions live in modifiers."
            DocumentedComponent.Scroll -> "Scroll reproduces the verified Minecraft 26.2 menu-list background, clipped centered content, separators, tiled scrollbar sprites, wheel rate, and proportional thumb movement in native draw order."
        }

    private fun details(details: List<ShowcaseTreeDetail>): String = if (details.isEmpty()) "" else " [${details.joinToString(", ") { detail -> ShowcaseDetailMarkdown.text(detail) }}]"

    private fun markdown(value: String): String = value.replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n') + "\n"
}
