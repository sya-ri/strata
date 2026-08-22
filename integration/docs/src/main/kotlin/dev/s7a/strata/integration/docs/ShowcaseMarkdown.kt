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

These deterministic crops come from real Minecraft 26.2 `ConfirmScreen`, `DirectJoinServerScreen`, `ContainerScreen`, Social Interactions, and native `ObjectSelectionList` screens reconstructed with Strata's complete standard component set, plus test Mod screens built from the same primitives and resource-pack assets.
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
                DocumentedComponent.Image,
                DocumentedComponent.Spacer,
                -> "This image is a ${spec.viewport.width} by ${spec.viewport.height} component crop from the exact Fabric/headless Mod-screen comparison recorded in [the verification receipt](components/minecraft-26.2-parity.properties)."

                DocumentedComponent.Row,
                DocumentedComponent.Column,
                DocumentedComponent.Stack,
                DocumentedComponent.Grid,
                DocumentedComponent.Text,
                DocumentedComponent.TextField,
                DocumentedComponent.Button,
                DocumentedComponent.Tab,
                DocumentedComponent.Scroll,
                DocumentedComponent.Slot,
                DocumentedComponent.PlayerHead,
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
            DocumentedComponent.Row -> "Sizing, padding, paint, semantics, focus, and input modifiers apply to the Row itself; `spacing` and `horizontalArrangement` express structure, while `RowScope.weight` and `RowScope.align` affect only direct children."
            DocumentedComponent.Column -> "Sizing, padding, paint, semantics, focus, and input modifiers apply to the Column itself; `spacing` and `verticalArrangement` express structure, while `ColumnScope.weight` and `ColumnScope.align` affect only direct children."
            DocumentedComponent.Stack -> "Use Stack only when children intentionally overlap. Ordinary sizing and background modifiers belong on the Stack; `StackScope.align` positions an individual overlay child without coordinate padding."
            DocumentedComponent.Grid -> "Sizing, padding, and paint modifiers apply to the Grid. Fixed columns, independent horizontal and vertical spacing, and `GridScope.align` replace repeated Row declarations and per-cell coordinate padding."
            DocumentedComponent.Spacer -> "Sizing, weight, and paint modifiers give Spacer a deliberate empty footprint, such as a separator or progress connector; ordinary parent spacing and alignment should remain layout arguments rather than placeholder children."
            DocumentedComponent.Slot -> "Sizing is native-fixed at 18 by 18. `Slots.playerInventory(index)` binds player storage, `Slots.container(index)` addresses logical storage exposed by chests, ender chests, furnaces, and custom server menus, and `Slots.activeMenu(index)` remains the raw-menu escape hatch; the optional-content overload remains portable for custom item visuals."
            DocumentedComponent.Text -> "Ordinary sizing, padding, placement, and paint modifiers compose around `Text`; text content remains a typed component argument."
            DocumentedComponent.TextField -> "Pointer, keyboard, committed-character, preedit, and focus modifiers run as active retained behavior around `TextField`; a consuming focused modifier overrides built-in editing."
            DocumentedComponent.Button -> "Pointer behavior is active modifier behavior. `onPointerEvent`, `onPress`, `onRelease`, `onMove`, `onDrag`, `onScroll`, and `onHover` can be composed without adding component-specific callback parameters."
            DocumentedComponent.Tab -> "Selection is caller-owned data, while `Underline` or `Custom` controls its reusable selected-state presentation. All pointer actions remain ordinary event modifiers, exactly as for Button and other interactive components."
            DocumentedComponent.Scroll -> "Ordinary sizing and placement modifiers define the viewport. Pointer action modifiers compose outside the component while native wheel and scrollbar motion remain retained Scroll behavior."
            DocumentedComponent.Image -> "Sizing and placement modifiers compose around `Image`; `imageBackground` paints the same immutable resource behind any layout component with typed stretch or tile mapping."
            DocumentedComponent.PlayerHead -> "Sizing and placement modifiers compose around `PlayerHead`; its immutable skin argument stays separate from Social, player-list, scoreboard, profile, and Mod-specific row state."
        }

    private fun parentScopeGuidance(component: DocumentedComponent): String =
        when (component) {
            DocumentedComponent.Row -> "`Row` evaluates a callback-lifetime `RowScope`, emits children in declaration order, and exposes only vertical alignment and weight parent data to its direct children."
            DocumentedComponent.Column -> "`Column` evaluates a callback-lifetime `ColumnScope`, emits children in declaration order, and exposes only horizontal alignment and weight parent data to its direct children."
            DocumentedComponent.Stack -> "`Stack` evaluates a callback-lifetime `StackScope`; it measures and paints overlapping direct children in declaration order and exposes two-axis alignment parent data."
            DocumentedComponent.Grid -> "`Grid` evaluates a callback-lifetime `GridScope`; it assigns direct children row-major and exposes two-axis alignment only inside each measured cell."
            DocumentedComponent.Spacer -> "`Spacer` has no content scope or children. Its size and modifier chain alone define its retained layout and paint behavior."
            DocumentedComponent.Slot -> "`Slot` is a member extension on the active `UiScope`. Its optional callback emits at most one 16 by 16 content root, while its bound overload obtains the version platform implicitly and retains no public Minecraft type."
            DocumentedComponent.Text -> "`Text` is a top-level extension on the active `UiScope`. The screen runtime installs its selected Minecraft profile only for the definition callback, and the component has no content callback or parent-data API."
            DocumentedComponent.TextField -> "`TextField` is a member extension on the active `UiScope`. The implicit runtime context supplies assets, while caller-owned `TextFieldState` owns the editable value."
            DocumentedComponent.Button -> "`Button` is a top-level extension on the active `UiScope`. The screen runtime installs its selected Minecraft profile only for the definition callback, and pointer event modifiers remain valid only through their retained modifier-node lifetime."
            DocumentedComponent.Tab -> "`Tab` is a top-level extension on the active `UiScope`. A custom selected indicator emits exactly one nested root; the selected value and event actions remain application-owned."
            DocumentedComponent.Scroll -> "`Scroll` is a member extension on the active `UiScope`. Its callback emits exactly one content root, remains callback-lifetime and owner-thread confined, and may use the same implicit Minecraft component DSL."
            DocumentedComponent.Image -> "`Image` is a top-level extension on the active `UiScope`. It retains detached pixels rather than a Minecraft resource object, so the Fabric loader may resolve a resource-pack replacement before the description is built."
            DocumentedComponent.PlayerHead -> "`PlayerHead` is a top-level extension on the active `UiScope`. The Fabric skin loader snapshots the current selected resource or downloaded texture first, and the retained component owns only detached pixels."
        }

    private fun typedSummary(component: DocumentedComponent): String =
        when (component) {
            DocumentedComponent.Row -> "Row places an ordered sibling sequence on one horizontal main axis, with typed arrangement, spacing, default vertical alignment, and direct-child overrides."
            DocumentedComponent.Column -> "Column places an ordered sibling sequence on one vertical main axis, with typed arrangement, spacing, default horizontal alignment, and direct-child overrides."
            DocumentedComponent.Stack -> "Stack is the explicit overlay primitive: children share one content rectangle, receive two-axis alignment, and paint in declaration order. It is not a generic div-like container."
            DocumentedComponent.Grid -> "Grid assigns children row-major to a fixed column count, measures each column and row from its largest member, and supports an incomplete final row without placeholders."
            DocumentedComponent.Spacer -> "Spacer is an empty measurable primitive for genuine visual separators, connectors, and weighted empty regions; it carries no screen-specific meaning."
            DocumentedComponent.Slot -> "Slot reproduces the native 18 by 18 hit region and 24 by 24 back-item-front highlight order; its binding overload polls real ItemStack state and delegates interaction through Minecraft's active container menu."
            DocumentedComponent.Text -> "Text renders a printable-ASCII literal with the extracted Minecraft glyph advances, shadow layer, foreground layer, and native baseline."
            DocumentedComponent.TextField -> "TextField reproduces the 200 by 20 Minecraft EditBox sprites, text origin, glyph colors, owner-thread value state, focus, and bounded editing behavior."
            DocumentedComponent.Button -> "Button renders verified fixed-height Minecraft sprite and label states, including the native 150- and 200-pixel widths, while reusable input actions live in modifiers."
            DocumentedComponent.Tab -> "Tab combines the verified button surface with external selection semantics and a reusable underline or caller-defined selected indicator, without encoding a particular screen's tab model."
            DocumentedComponent.Scroll -> "Scroll reproduces the verified Minecraft 26.2 menu-list background, clipped centered content, separators, tiled scrollbar sprites, wheel rate, and proportional thumb movement in native draw order."
            DocumentedComponent.Image -> "Image maps one immutable resource-pack image to an exact logical size with deterministic nearest sampling; it is reusable for icons, portraits, diagrams, and Mod-owned panels."
            DocumentedComponent.PlayerHead -> "PlayerHead reproduces Minecraft 26.2 face-then-hat rendering from a 64 by 64 skin; its default 24 by 24 extent matches Social Interactions while remaining reusable in lists, profiles, scoreboards, and Mod screens."
        }

    private fun details(details: List<ShowcaseTreeDetail>): String = if (details.isEmpty()) "" else " [${details.joinToString(", ") { detail -> ShowcaseDetailMarkdown.text(detail) }}]"

    private fun markdown(value: String): String = value.replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n') + "\n"
}
