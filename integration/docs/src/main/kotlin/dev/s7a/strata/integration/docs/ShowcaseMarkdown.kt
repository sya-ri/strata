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
One loaded Minecraft 26.2 Fabric GameTest renders that definition independently through the Fabric adapter and headless runtime, requires exact ARGB equality, and publishes the entire resulting frame without cropping a larger showcase screen.
Separate native full-screen parity scenes for `ConfirmScreen`, `DirectJoinServerScreen`, `ContainerScreen`, Social Interactions, and `ObjectSelectionList`, plus complete test Mod screens, remain acceptance evidence for real assets, fonts, textures, placement, and draw order.
The menu and generic-container images are active background modifiers on layout components rather than logical component entries.

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
            "This ${spec.viewport.width} by ${spec.viewport.height} image is the complete frame of the compiled dedicated `ScreenDefinition`, after exact Fabric/headless ARGB comparison recorded in [the verification receipt](components/minecraft-26.2-parity.properties); it is not cropped from a larger screen."
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

    @Suppress("CyclomaticComplexMethod")
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
            DocumentedComponent.Checkbox -> "Sizing and placement modifiers compose around `Checkbox`; caller-owned state and typed checked-change actions keep the reusable boolean control independent of a settings domain."
            DocumentedComponent.CycleButton -> "Sizing and placement modifiers compose around `CycleButton`; its immutable option set and typed change action remain generic rather than encoding one game's option model."
            DocumentedComponent.Slider -> "Sizing and placement modifiers compose around `Slider`; caller-owned range state and typed value-change actions remain reusable across volume, brightness, machine power, and other numeric domains."
            DocumentedComponent.Tab -> "Selection is caller-owned data, while `Underline` or `Custom` controls its reusable selected-state presentation. All pointer actions remain ordinary event modifiers, exactly as for Button and other interactive components."
            DocumentedComponent.ScrollArea -> "Ordinary sizing and placement modifiers define only the clipped viewport. The shared `ScrollState` links optional external controls without forcing a scrollbar into the component tree."
            DocumentedComponent.Scrollbar -> "Sizing and parent placement modifiers position `Scrollbar` independently from its viewport; sharing `ScrollState` is the only link required."
            DocumentedComponent.VirtualList -> "Sizing is expressed by `viewportSize`; modifier actions receive leading and trailing load requests while caller-owned state supports index, key, and boundary navigation."
            DocumentedComponent.SelectionList -> "Viewport behavior composes with typed selection actions and caller-owned selection state; row visuals remain application composition rather than a screen-specific built-in."
            DocumentedComponent.Image -> "Sizing and placement modifiers compose around `Image`; `imageBackground` paints the same immutable resource behind any layout component with typed stretch or tile mapping."
            DocumentedComponent.PlayerHead -> "Sizing and placement modifiers compose around `PlayerHead`; its immutable skin argument stays separate from Social, player-list, scoreboard, profile, and Mod-specific row state."
            DocumentedComponent.LoadingIndicator -> "Sizing and placement modifiers compose around `LoadingIndicator`; explicit host frame time advances its discrete profile animation without application-owned timer state."
            DocumentedComponent.ProgressBar -> "Sizing and placement modifiers compose around `ProgressBar`; its normalized value is immutable component data while the active profile supplies resource-pack-aware fill, completed-fill, and border sprites."
        }

    @Suppress("CyclomaticComplexMethod")
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
            DocumentedComponent.Checkbox -> "`Checkbox` is a leaf extension on the active `UiScope`; `CheckboxState` is caller-owned, owner-thread confined, and may be shared with application state adapters."
            DocumentedComponent.CycleButton -> "`CycleButton` is a leaf extension on the active `UiScope`; it snapshots labels for the validated finite option set and retains no child scope."
            DocumentedComponent.Slider -> "`Slider` is a leaf extension on the active `UiScope`; `SliderState` owns normalization and quantization while the active profile owns rendering."
            DocumentedComponent.Tab -> "`Tab` is a top-level extension on the active `UiScope`. A custom selected indicator emits exactly one nested root; the selected value and event actions remain application-owned."
            DocumentedComponent.ScrollArea -> "`ScrollArea` evaluates a callback-lifetime `UiScope` that emits exactly one content root; the caller owns the linked state and may omit a scrollbar."
            DocumentedComponent.Scrollbar -> "`Scrollbar` is an independent leaf in any surrounding layout. It observes caller-owned `ScrollState` and releases that observation when its retained node is disposed."
            DocumentedComponent.VirtualList -> "`VirtualList` evaluates row callbacks only for visible rows plus bounded overscan; stable keys preserve retained identity while the caller owns source and navigation state."
            DocumentedComponent.SelectionList -> "`SelectionList` wraps visible virtual rows with generic selection semantics and press handling while leaving each row's single content root to the caller."
            DocumentedComponent.Image -> "`Image` is a top-level extension on the active `UiScope`. It retains detached pixels rather than a Minecraft resource object, so the Fabric loader may resolve a resource-pack replacement before the description is built."
            DocumentedComponent.PlayerHead -> "`PlayerHead` is a top-level extension on the active `UiScope`. The Fabric skin loader snapshots the current selected resource or downloaded texture first, and the retained component owns only detached pixels."
            DocumentedComponent.LoadingIndicator -> "`LoadingIndicator` is a top-level extension on the active `UiScope`. The Fabric host supplies one timestamp per native render pass and the retained node invalidates only when its discrete animation cell changes."
            DocumentedComponent.ProgressBar -> "`ProgressBar` is a top-level extension on the active `UiScope`. The implicit profile resolves the active resource pack before retaining immutable sprite pixels."
        }

    @Suppress("CyclomaticComplexMethod")
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
            DocumentedComponent.Checkbox -> "Checkbox reproduces the verified 20-pixel Minecraft checkbox surface, label spacing, focused input, checked semantics, and caller-owned boolean state."
            DocumentedComponent.CycleButton -> "CycleButton reuses the verified button surface for a finite generic option sequence with forward, backward, wheel, and keyboard navigation."
            DocumentedComponent.Slider -> "Slider reproduces Minecraft's profile-backed track and handle while normalizing finite numeric ranges and optional discrete steps in caller-owned state."
            DocumentedComponent.Tab -> "Tab combines the verified button surface with external selection semantics and a reusable underline or caller-defined selected indicator, without encoding a particular screen's tab model."
            DocumentedComponent.ScrollArea -> "ScrollArea reproduces the verified Minecraft menu-list background, clipping, separators, and wheel behavior without owning or positioning a scrollbar."
            DocumentedComponent.Scrollbar -> "Scrollbar reproduces the verified tiled track and proportional thumb while remaining an independently placed observer of shared scroll metrics."
            DocumentedComponent.VirtualList -> "VirtualList retains only visible fixed-height rows plus bounded overscan, supports prepended and appended loading, and can jump by index or stable key."
            DocumentedComponent.SelectionList -> "SelectionList adds generic caller-owned selection and typed selection-change actions to VirtualList without encoding Social, inventory, advancement, or Mod-specific rows."
            DocumentedComponent.Image -> "Image maps one immutable resource-pack image to an exact logical size with deterministic nearest sampling; it is reusable for icons, portraits, diagrams, and Mod-owned panels."
            DocumentedComponent.PlayerHead -> "PlayerHead reproduces Minecraft 26.2 face-then-hat rendering from a 64 by 64 skin; its default 24 by 24 extent matches Social Interactions while remaining reusable in lists, profiles, scoreboards, and Mod screens."
            DocumentedComponent.LoadingIndicator -> "LoadingIndicator reproduces the Minecraft 26.2 friends-loading sprite as three vertical 5 by 2 cells with the native six-tick frame duration; older runtimes use the same pack-overridable path before their compatibility fallback."
            DocumentedComponent.ProgressBar -> "ProgressBar uses the reusable bundle progress border, partial fill, and completed fill with their native two-pixel nine-slice borders and exposes read-only progress semantics."
        }

    private fun details(details: List<ShowcaseTreeDetail>): String = if (details.isEmpty()) "" else " [${details.joinToString(", ") { detail -> ShowcaseDetailMarkdown.text(detail) }}]"

    private fun markdown(value: String): String = value.replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n') + "\n"
}
