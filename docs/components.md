<!-- Generated file. Do not edit. -->

# Minecraft component showcase

These deterministic crops come from real Minecraft 26.2 `ConfirmScreen`, `DirectJoinServerScreen`, `ContainerScreen`, Social Interactions, and native `ObjectSelectionList` screens reconstructed with Strata's complete standard component set, plus test Mod screens built from the same primitives and resource-pack assets.
The menu and generic-container images are active background modifiers on layout components rather than logical component entries.
One loaded Fabric GameTest requires exact ARGB equality among each native screen, the Fabric adapter, and the headless frame before it emits these component images.

[Open the machine-readable parity receipt](components/minecraft-26.2-parity.properties)

![Overview headless showcase](components/overview.png)

## Overview source

```kotlin
import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.menuBackground
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.size
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Builds the deterministic Minecraft ConfirmScreen content used by the Fabric and headless parity paths.
 *
 * @return one-shot screen definition reproducing the native title, message, and button-row geometry.
 */
internal fun createConfirmScreenDefinition(): ScreenDefinition =
    ScreenDefinition("Strata parity") {
        Stack(
            modifier = Modifier.Empty.size(320, 180).menuBackground(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                spacing = 24,
                horizontalAlignment = HorizontalAlignment.Center,
            ) {
                Column(
                    spacing = 8,
                    horizontalAlignment = HorizontalAlignment.Center,
                ) {
                    Text("Confirm action")
                    Text("Continue with this action?")
                }
                Row(spacing = 4) {
                    Button(
                        "Yes",
                        modifier = Modifier.Empty.onPress {},
                    )
                    Button(
                        "No",
                        modifier = Modifier.Empty.onPress {},
                    )
                }
            }
        }
    }
```

<details><summary>Overview component tree</summary>

The tree shows Minecraft components in logical draw order; platform-neutral layout scaffolding remains visible in the compiled source.

```text
`- Stack [Size(width=320, height=180)]
  `- Column [Spacing(value=24)]
    |- Column [Spacing(value=8)]
    | |- Text
    | `- Text
    `- Row [Spacing(value=4)]
      |- Button
      `- Button
```

</details>

## Components

- [Row](#row)
- [Column](#column)
- [Stack](#stack)
- [Grid](#grid)
- [Spacer](#spacer)
- [Text](#text)
- [TextField](#text-field)
- [Button](#button)
- [Tab](#tab)
- [Scroll](#scroll)
- [Image](#image)
- [Slot](#slot)
- [PlayerHead](#player-head)

<a id="row"></a>

## Row

Row places an ordered sibling sequence on one horizontal main axis, with typed arrangement, spacing, default vertical alignment, and direct-child overrides.

This image is a 320 by 180 component crop from the exact native/Fabric/headless parity frame recorded in [the verification receipt](components/minecraft-26.2-parity.properties).

![Row headless showcase](components/row.png)

### Compiled example

```kotlin
import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.menuBackground
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.size
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Builds the deterministic Minecraft ConfirmScreen content used by the Fabric and headless parity paths.
 *
 * @return one-shot screen definition reproducing the native title, message, and button-row geometry.
 */
internal fun createConfirmScreenDefinition(): ScreenDefinition =
    ScreenDefinition("Strata parity") {
        Stack(
            modifier = Modifier.Empty.size(320, 180).menuBackground(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                spacing = 24,
                horizontalAlignment = HorizontalAlignment.Center,
            ) {
                Column(
                    spacing = 8,
                    horizontalAlignment = HorizontalAlignment.Center,
                ) {
                    Text("Confirm action")
                    Text("Continue with this action?")
                }
                Row(spacing = 4) {
                    Button(
                        "Yes",
                        modifier = Modifier.Empty.onPress {},
                    )
                    Button(
                        "No",
                        modifier = Modifier.Empty.onPress {},
                    )
                }
            }
        }
    }
```

### Modifiers

Sizing, padding, paint, semantics, focus, and input modifiers apply to the Row itself; `spacing` and `horizontalArrangement` express structure, while `RowScope.weight` and `RowScope.align` affect only direct children.

### Parent scope

`Row` evaluates a callback-lifetime `RowScope`, emits children in declaration order, and exposes only vertical alignment and weight parent data to its direct children.

<details><summary>Component tree</summary>

The tree shows the featured Minecraft component; platform-neutral layout scaffolding remains visible in the compiled source.

```text
`- Row [Spacing(value=4)]
  |- Button
  `- Button
```

</details>

<a id="column"></a>

## Column

Column places an ordered sibling sequence on one vertical main axis, with typed arrangement, spacing, default horizontal alignment, and direct-child overrides.

This image is a 320 by 180 component crop from the exact native/Fabric/headless parity frame recorded in [the verification receipt](components/minecraft-26.2-parity.properties).

![Column headless showcase](components/column.png)

### Compiled example

```kotlin
import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.menuBackground
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.size
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Builds the deterministic Minecraft ConfirmScreen content used by the Fabric and headless parity paths.
 *
 * @return one-shot screen definition reproducing the native title, message, and button-row geometry.
 */
internal fun createConfirmScreenDefinition(): ScreenDefinition =
    ScreenDefinition("Strata parity") {
        Stack(
            modifier = Modifier.Empty.size(320, 180).menuBackground(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                spacing = 24,
                horizontalAlignment = HorizontalAlignment.Center,
            ) {
                Column(
                    spacing = 8,
                    horizontalAlignment = HorizontalAlignment.Center,
                ) {
                    Text("Confirm action")
                    Text("Continue with this action?")
                }
                Row(spacing = 4) {
                    Button(
                        "Yes",
                        modifier = Modifier.Empty.onPress {},
                    )
                    Button(
                        "No",
                        modifier = Modifier.Empty.onPress {},
                    )
                }
            }
        }
    }
```

### Modifiers

Sizing, padding, paint, semantics, focus, and input modifiers apply to the Column itself; `spacing` and `verticalArrangement` express structure, while `ColumnScope.weight` and `ColumnScope.align` affect only direct children.

### Parent scope

`Column` evaluates a callback-lifetime `ColumnScope`, emits children in declaration order, and exposes only horizontal alignment and weight parent data to its direct children.

<details><summary>Component tree</summary>

The tree shows the featured Minecraft component; platform-neutral layout scaffolding remains visible in the compiled source.

```text
`- Column [Spacing(value=24)]
  |- Column [Spacing(value=8)]
  | |- Text
  | `- Text
  `- Row [Spacing(value=4)]
    |- Button
    `- Button
```

</details>

<a id="stack"></a>

## Stack

Stack is the explicit overlay primitive: children share one content rectangle, receive two-axis alignment, and paint in declaration order. It is not a generic div-like container.

This image is a 320 by 180 component crop from the exact native/Fabric/headless parity frame recorded in [the verification receipt](components/minecraft-26.2-parity.properties).

![Stack headless showcase](components/stack.png)

### Compiled example

```kotlin
import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.menuBackground
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.size
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Builds the deterministic Minecraft ConfirmScreen content used by the Fabric and headless parity paths.
 *
 * @return one-shot screen definition reproducing the native title, message, and button-row geometry.
 */
internal fun createConfirmScreenDefinition(): ScreenDefinition =
    ScreenDefinition("Strata parity") {
        Stack(
            modifier = Modifier.Empty.size(320, 180).menuBackground(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                spacing = 24,
                horizontalAlignment = HorizontalAlignment.Center,
            ) {
                Column(
                    spacing = 8,
                    horizontalAlignment = HorizontalAlignment.Center,
                ) {
                    Text("Confirm action")
                    Text("Continue with this action?")
                }
                Row(spacing = 4) {
                    Button(
                        "Yes",
                        modifier = Modifier.Empty.onPress {},
                    )
                    Button(
                        "No",
                        modifier = Modifier.Empty.onPress {},
                    )
                }
            }
        }
    }
```

### Modifiers

Use Stack only when children intentionally overlap. Ordinary sizing and background modifiers belong on the Stack; `StackScope.align` positions an individual overlay child without coordinate padding.

### Parent scope

`Stack` evaluates a callback-lifetime `StackScope`; it measures and paints overlapping direct children in declaration order and exposes two-axis alignment parent data.

<details><summary>Component tree</summary>

The tree shows the featured Minecraft component; platform-neutral layout scaffolding remains visible in the compiled source.

```text
`- Stack [Size(width=320, height=180), StackContentAlignment(alignment=Center)]
  `- Column [Spacing(value=24)]
```

</details>

<a id="grid"></a>

## Grid

Grid assigns children row-major to a fixed column count, measures each column and row from its largest member, and supports an incomplete final row without placeholders.

This image is a 320 by 240 component crop from the exact native/Fabric/headless parity frame recorded in [the verification receipt](components/minecraft-26.2-parity.properties).

![Grid headless showcase](components/grid.png)

### Compiled example

```kotlin
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Grid
import dev.s7a.strata.component.Slot
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.containerBackground
import dev.s7a.strata.modifier.menuBackground
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Builds the empty three-row Minecraft chest screen used by native, Fabric, and headless parity paths.
 *
 * @return one-shot screen definition reproducing the generic container, labels, 63 Slot hit regions, and hovered highlight order.
 */
internal fun createSlotScreenDefinition(): ScreenDefinition =
    ScreenDefinition("Chest") {
        Stack(
            modifier =
                Modifier.Empty
                    .size(320, 240)
                    .background(ArgbColor(0xFF000000.toInt()))
                    .menuBackground(),
            contentAlignment = Alignment.Center,
        ) {
            Stack(
                modifier = Modifier.Empty.containerBackground(rows = 3),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier.Empty.size(162, 156),
                    spacing = 3,
                ) {
                    Column(spacing = 2) {
                        Text(
                            "Chest",
                            style = TextStyle.ContainerLabel,
                            modifier = Modifier.Empty.padding(left = 1),
                        )
                        Grid(columns = 9) {
                            repeat(27) {
                                Slot()
                            }
                        }
                    }
                    Column {
                        Text(
                            "Inventory",
                            style = TextStyle.ContainerLabel,
                            modifier = Modifier.Empty.padding(left = 1),
                        )
                        Grid(columns = 9, modifier = Modifier.Empty.padding(top = 1)) {
                            repeat(27) {
                                Slot()
                            }
                        }
                        Grid(columns = 9, modifier = Modifier.Empty.padding(top = 4)) {
                            repeat(9) {
                                Slot()
                            }
                        }
                    }
                }
            }
        }
    }
```

### Modifiers

Sizing, padding, and paint modifiers apply to the Grid. Fixed columns, independent horizontal and vertical spacing, and `GridScope.align` replace repeated Row declarations and per-cell coordinate padding.

### Parent scope

`Grid` evaluates a callback-lifetime `GridScope`; it assigns direct children row-major and exposes two-axis alignment only inside each measured cell.

<details><summary>Component tree</summary>

The tree shows the featured Minecraft component; platform-neutral layout scaffolding remains visible in the compiled source.

```text
`- Grid [GridColumns(value=9)]
  |- Slot
  |- Slot
  |- Slot
  |- Slot
  |- Slot
  |- Slot
  |- Slot
  |- Slot
  |- Slot
  |- Slot
  |- Slot
  |- Slot
  |- Slot
  |- Slot
  |- Slot
  |- Slot
  |- Slot
  |- Slot
  |- Slot
  |- Slot
  |- Slot
  |- Slot
  |- Slot
  |- Slot
  |- Slot
  |- Slot
  `- Slot
```

</details>

<a id="spacer"></a>

## Spacer

Spacer is an empty measurable primitive for genuine visual separators, connectors, and weighted empty regions; it carries no screen-specific meaning.

This image is a 320 by 180 component crop from the exact Fabric/headless Mod-screen comparison recorded in [the verification receipt](components/minecraft-26.2-parity.properties).

![Spacer headless showcase](components/spacer.png)

### Compiled example

```kotlin
import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Image
import dev.s7a.strata.component.ImageScale
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.component.UiScope
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.imageBackground
import dev.s7a.strata.modifier.menuBackground
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Builds one advancement-inspired Mod screen from resource-pack sources and an application-owned component.
 *
 * The standard runtime remains limited to reusable primitives; [ExampleProgressGraph] may encode this Mod's progression domain because it remains downstream application code.
 *
 * @param window active advancement-window source.
 * @param background active advancement-background tile source.
 * @param obtained active obtained task-frame source.
 * @param unobtained active unobtained task-frame source.
 * @return one-shot definition for the verified Fabric and headless screen.
 */
internal fun createProgressScreenDefinition(
    window: ImageSource = advancementWindow,
    background: ImageSource = advancementBackground,
    obtained: ImageSource = obtainedTaskFrame,
    unobtained: ImageSource = unobtainedTaskFrame,
): ScreenDefinition =
    ScreenDefinition("Power milestones") {
        Stack(
            modifier =
                Modifier.Empty
                    .size(320, 180)
                    .background(ArgbColor(0xFF000000.toInt()))
                    .menuBackground(),
            contentAlignment = Alignment.Center,
        ) {
            Stack(modifier = Modifier.Empty.size(252, 140)) {
                Image(window, sourceRegion = IntRect(0, 0, 252, 140))
                Column(
                    modifier = Modifier.Empty.padding(left = 9, top = 6, right = 9, bottom = 9),
                    spacing = 4,
                    horizontalAlignment = HorizontalAlignment.Center,
                ) {
                    Text("Power milestones", style = TextStyle.ContainerLabel)
                    ExampleProgressGraph(background, obtained, unobtained)
                }
            }
            Button(
                "Done",
                width = 200,
                modifier =
                    Modifier.Empty
                        .padding(bottom = 6)
                        .align(Alignment.BottomCenter)
                        .onPress {},
            )
        }
    }

/**
 * Emits one application-owned progression graph by composing only public Strata primitives.
 *
 * This downstream component is deliberately not part of the standard runtime because its node meanings and progression domain belong to the application.
 * It retains no callback or scope after synchronous emission.
 *
 * @receiver active owner-thread UI scope.
 * @param background immutable or resource-backed background tile.
 * @param obtained immutable or resource-backed obtained frame.
 * @param unobtained immutable or resource-backed unobtained frame.
 * @param modifier active behavior surrounding the fixed graph.
 * @param key optional stable sibling identity.
 * @throws IllegalStateException when used from another thread or outside its callback lifetime.
 */
internal fun UiScope.ExampleProgressGraph(
    background: ImageSource,
    obtained: ImageSource,
    unobtained: ImageSource,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    Row(
        modifier = modifier.size(234, 113).imageBackground(background, ImageScale.Tile),
        key = key,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = VerticalAlignment.Center,
    ) {
        progressNode(obtained, ArgbColor(0xFF22D3EE.toInt()), "Generator")
        Spacer(modifier = Modifier.Empty.size(32, 2).background(connectionColor))
        Column(
            spacing = 4,
            horizontalAlignment = HorizontalAlignment.Center,
        ) {
            progressNode(obtained, ArgbColor(0xFFFBBF24.toInt()), "Storage")
            Spacer(modifier = Modifier.Empty.size(2, 12).background(connectionColor))
            progressNode(unobtained, ArgbColor(0xFFA78BFA.toInt()), "Automation")
        }
    }
}

private fun UiScope.progressNode(
    frame: ImageSource,
    color: ArgbColor,
    label: String,
) {
    this.Column(
        horizontalAlignment = HorizontalAlignment.Center,
        spacing = 1,
    ) {
        Stack(
            modifier = Modifier.Empty.size(26, 26),
            contentAlignment = Alignment.Center,
        ) {
            Image(frame)
            Spacer(modifier = Modifier.Empty.size(16, 16).background(color))
        }
        Text(label)
    }
}

private val advancementWindow = ImageSource.Resource(ResourceId("minecraft", "textures/gui/advancements/window.png"))
private val advancementBackground = ImageSource.Resource(ResourceId("minecraft", "textures/gui/advancements/backgrounds/stone.png"))
private val obtainedTaskFrame = ImageSource.Resource(ResourceId("minecraft", "textures/gui/sprites/advancements/task_frame_obtained.png"))
private val unobtainedTaskFrame = ImageSource.Resource(ResourceId("minecraft", "textures/gui/sprites/advancements/task_frame_unobtained.png"))
private val connectionColor = ArgbColor(0xFF7A7A7A.toInt())
```

### Modifiers

Sizing, weight, and paint modifiers give Spacer a deliberate empty footprint, such as a separator or progress connector; ordinary parent spacing and alignment should remain layout arguments rather than placeholder children.

### Parent scope

`Spacer` has no content scope or children. Its size and modifier chain alone define its retained layout and paint behavior.

<details><summary>Component tree</summary>

The tree shows the featured Minecraft component; platform-neutral layout scaffolding remains visible in the compiled source.

```text
`- Spacer [Size(width=32, height=2), Background(color=0xFF7A7A7A)]
```

</details>

<a id="text"></a>

## Text

Text renders a printable-ASCII literal with the extracted Minecraft glyph advances, shadow layer, foreground layer, and native baseline.

This image is a 150 by 20 component crop from the exact native/Fabric/headless parity frame recorded in [the verification receipt](components/minecraft-26.2-parity.properties).

![Text headless showcase](components/text.png)

### Compiled example

```kotlin
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.size
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Builds the literal Text component used by the verified ConfirmScreen title.
 *
 * @return one-shot screen definition whose content resolves components from the host-installed Minecraft profile.
 */
internal fun textExample(): ScreenDefinition =
    ScreenDefinition("Text") {
        Stack(
            modifier = Modifier.Empty.size(150, 20),
            contentAlignment = Alignment.Center,
        ) {
            Text("Confirm action")
        }
    }
```

### Modifiers

Ordinary sizing, padding, placement, and paint modifiers compose around `Text`; text content remains a typed component argument.

### Parent scope

`Text` is a top-level extension on the active `UiScope`. The screen runtime installs its selected Minecraft profile only for the definition callback, and the component has no content callback or parent-data API.

<details><summary>Component tree</summary>

The tree shows the featured Minecraft component; platform-neutral layout scaffolding remains visible in the compiled source.

```text
`- Text [Size(width=150, height=20)]
```

</details>

<a id="text-field"></a>

## TextField

TextField reproduces the 200 by 20 Minecraft EditBox sprites, text origin, glyph colors, owner-thread value state, focus, and bounded editing behavior.

This image is a 200 by 20 component crop from the exact native/Fabric/headless parity frame recorded in [the verification receipt](components/minecraft-26.2-parity.properties).

![TextField headless showcase](components/text-field.png)

### Compiled example

```kotlin
import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextField
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.menuBackground
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Builds the Minecraft Direct Connection screen used by native, Fabric, and headless parity paths.
 *
 * @return one-shot screen definition with the actual EditBox and 200-pixel Button geometry.
 */
internal fun createDirectJoinScreenDefinition(): ScreenDefinition {
    val address = TextFieldState("play.example.net", maxLength = 128)
    return ScreenDefinition("Direct Connection") {
        Stack(modifier = Modifier.Empty.size(320, 240).menuBackground()) {
            Column(
                modifier = Modifier.Empty.size(320, 212),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = HorizontalAlignment.Center,
            ) {
                Stack(
                    modifier = Modifier.Empty.size(320, 29),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Text("Direct Connection")
                }
                Column(
                    modifier = Modifier.Empty.size(200, 112),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(spacing = 7) {
                        Text(
                            "Server Address",
                            style = TextStyle.Inactive,
                            modifier = Modifier.Empty.padding(left = 1),
                        )
                        TextField(address)
                    }
                    Column(spacing = 4) {
                        Button(
                            "Join Server",
                            width = 200,
                            modifier = Modifier.Empty.onPress {},
                        )
                        Button(
                            "Cancel",
                            width = 200,
                            modifier = Modifier.Empty.onPress {},
                        )
                    }
                }
            }
        }
    }
}
```

### Modifiers

Pointer, keyboard, committed-character, preedit, and focus modifiers run as active retained behavior around `TextField`; a consuming focused modifier overrides built-in editing.

### Parent scope

`TextField` is a member extension on the active `UiScope`. The implicit runtime context supplies assets, while caller-owned `TextFieldState` owns the editable value.

<details><summary>Component tree</summary>

The tree shows the featured Minecraft component; platform-neutral layout scaffolding remains visible in the compiled source.

```text
`- TextField [Size(width=200, height=20)]
```

</details>

<a id="button"></a>

## Button

Button renders verified fixed-height Minecraft sprite and label states, including the native 150- and 200-pixel widths, while reusable input actions live in modifiers.

This image is a 150 by 20 component crop from the exact native/Fabric/headless parity frame recorded in [the verification receipt](components/minecraft-26.2-parity.properties).

![Button headless showcase](components/button.png)

### Compiled example

```kotlin
import dev.s7a.strata.component.Button
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onHover
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Builds the pointer Button used by the verified ConfirmScreen action row.
 *
 * @return one-shot screen definition whose content resolves components from the host-installed Minecraft profile.
 */
internal fun buttonExample(): ScreenDefinition =
    ScreenDefinition("Button") {
        Button(
            "Yes",
            modifier =
                Modifier.Empty
                    .onPress {}
                    .onHover {},
        )
    }
```

### Modifiers

Pointer behavior is active modifier behavior. `onPointerEvent`, `onPress`, `onRelease`, `onMove`, `onDrag`, `onScroll`, and `onHover` can be composed without adding component-specific callback parameters.

### Parent scope

`Button` is a top-level extension on the active `UiScope`. The screen runtime installs its selected Minecraft profile only for the definition callback, and pointer event modifiers remain valid only through their retained modifier-node lifetime.

<details><summary>Component tree</summary>

The tree shows the featured Minecraft component; platform-neutral layout scaffolding remains visible in the compiled source.

```text
`- Button
```

</details>

<a id="tab"></a>

## Tab

Tab combines the verified button surface with external selection semantics and a reusable underline or caller-defined selected indicator, without encoding a particular screen's tab model.

This image is a 320 by 240 component crop from the exact native/Fabric/headless parity frame recorded in [the verification receipt](components/minecraft-26.2-parity.properties).

![Tab headless showcase](components/tab.png)

### Compiled example

```kotlin
import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Image
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.NineSliceCenterMode
import dev.s7a.strata.component.PlayerHead
import dev.s7a.strata.component.PlayerSkinSource
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Tab
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextField
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.imageBackground
import dev.s7a.strata.modifier.initialFocus
import dev.s7a.strata.modifier.menuBackground
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Builds the deterministic one-player Minecraft Social Interactions screen from general-purpose primitives.
 *
 * Social-entry composition remains application code: the public runtime supplies PlayerHead, text, actions, images, fields, layout, and active backgrounds without exposing a purpose-specific SocialEntry component.
 *
 * @param panel active-resource `social_interactions/background` source.
 * @param searchIcon active-resource `icon/search` source.
 * @param playerSkin selected player lookup or detached skin source.
 * @return one-shot screen definition reproducing the native screen geometry and draw order.
 */
internal fun createSocialScreenDefinition(
    panel: ImageSource = socialPanel,
    searchIcon: ImageSource = socialSearchIcon,
    playerSkin: PlayerSkinSource = PlayerSkinSource.Name("Player0"),
): ScreenDefinition {
    val search = TextFieldState("", maxLength = 16)
    return ScreenDefinition("Social Interactions") {
        Stack(
            modifier =
                Modifier.Empty
                    .size(320, 240)
                    .background(ArgbColor(0xFF000000.toInt()))
                    .menuBackground(),
        ) {
            Stack(
                modifier = Modifier.Empty.size(320, 176),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Stack(
                    modifier =
                        Modifier.Empty
                            .padding(left = 4)
                            .size(236, 112)
                            .imageBackground(
                                panel,
                                Insets.all(8),
                                NineSliceCenterMode.Tiled,
                            ),
                ) {}
            }
            Column(
                modifier = Modifier.Empty.size(222, 234).align(Alignment.TopCenter),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.Empty.padding(top = 12)) {
                    Column(
                        modifier = Modifier.Empty.size(222, 32),
                        spacing = 14,
                    ) {
                        Text(
                            "Social Interactions",
                            modifier = Modifier.Empty.align(HorizontalAlignment.Center),
                        )
                        Text("Player0 - New World - 1 player")
                    }
                    Row(modifier = Modifier.Empty.padding(left = 1, top = 1), spacing = 1) {
                        Tab("All", selected = true, width = 73, modifier = Modifier.Empty.onPress {})
                        Tab("Hidden", selected = false, width = 73, modifier = Modifier.Empty.onPress {})
                        Tab("Blocked", selected = false, width = 73, modifier = Modifier.Empty.onPress {})
                    }
                    Row(
                        modifier = Modifier.Empty.padding(left = 5, top = 9),
                        spacing = 3,
                        verticalAlignment = VerticalAlignment.Center,
                    ) {
                        Image(
                            searchIcon,
                            size = IntSize(12, 12),
                            modifier = Modifier.Empty.padding(top = 2),
                        )
                        TextField(
                            search,
                            size = IntSize(200, 15),
                            textStyle = TextStyle.Normal,
                            modifier = Modifier.Empty.initialFocus(),
                        )
                    }
                    Row(
                        modifier =
                            Modifier.Empty
                                .padding(left = 3, top = 3)
                                .size(216, 32)
                                .background(ArgbColor(0xFF4A4A4A.toInt())),
                        spacing = 4,
                        verticalAlignment = VerticalAlignment.Center,
                    ) {
                        PlayerHead(source = playerSkin, modifier = Modifier.Empty.padding(left = 4))
                        Text("Player0")
                    }
                }
                Button(
                    "Done",
                    width = 200,
                    modifier = Modifier.Empty.align(HorizontalAlignment.Center).onPress {},
                )
            }
        }
    }
}

private val socialPanel = ImageSource.Resource(ResourceId("minecraft", "textures/gui/sprites/social_interactions/background.png"))
private val socialSearchIcon = ImageSource.Resource(ResourceId("minecraft", "textures/gui/sprites/icon/search.png"))
```

### Modifiers

Selection is caller-owned data, while `Underline` or `Custom` controls its reusable selected-state presentation. All pointer actions remain ordinary event modifiers, exactly as for Button and other interactive components.

### Parent scope

`Tab` is a top-level extension on the active `UiScope`. A custom selected indicator emits exactly one nested root; the selected value and event actions remain application-owned.

<details><summary>Component tree</summary>

The tree shows the featured Minecraft component; platform-neutral layout scaffolding remains visible in the compiled source.

```text
`- Tab [Size(width=73, height=20)]
```

</details>

<a id="scroll"></a>

## Scroll

Scroll reproduces the verified Minecraft 26.2 menu-list background, clipped centered content, separators, tiled scrollbar sprites, wheel rate, and proportional thumb movement in native draw order.

This image is a 320 by 94 component crop from the exact native/Fabric/headless parity frame recorded in [the verification receipt](components/minecraft-26.2-parity.properties).

![Scroll headless showcase](components/scroll.png)

### Compiled example

```kotlin
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Scroll
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.fillMaxSize
import dev.s7a.strata.modifier.menuBackground
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Builds the deterministic Minecraft selection-list screen used by the native, Fabric, and headless parity paths.
 *
 * @return one-shot screen definition reproducing the native list viewport, row geometry, separators, scrollbar, and text.
 */
internal fun createScrollScreenDefinition(): ScreenDefinition =
    ScreenDefinition("Strata Scroll parity") {
        Stack(modifier = Modifier.Empty.size(320, 180).menuBackground()) {
            // Native ObjectSelectionList geometry reserves distinct 33-pixel header and 53-pixel footer bands.
            Scroll(
                modifier = Modifier.Empty.padding(top = 33, bottom = 53).fillMaxSize(),
            ) {
                Column(
                    modifier = Modifier.Empty.size(270, 216),
                    horizontalAlignment = HorizontalAlignment.Center,
                ) {
                    listOf(
                        "Entry 01",
                        "Entry 02",
                        "Entry 03",
                        "Entry 04",
                        "Entry 05",
                        "Entry 06",
                        "Entry 07",
                        "Entry 08",
                        "Entry 09",
                        "Entry 10",
                        "Entry 11",
                        "Entry 12",
                    ).forEach { label ->
                        Text(label, modifier = Modifier.Empty.padding(top = 5, bottom = 4))
                    }
                }
            }
        }
    }
```

### Modifiers

Ordinary sizing and placement modifiers define the viewport. Pointer action modifiers compose outside the component while native wheel and scrollbar motion remain retained Scroll behavior.

### Parent scope

`Scroll` is a member extension on the active `UiScope`. Its callback emits exactly one content root, remains callback-lifetime and owner-thread confined, and may use the same implicit Minecraft component DSL.

<details><summary>Component tree</summary>

The tree shows the featured Minecraft component; platform-neutral layout scaffolding remains visible in the compiled source.

```text
`- Scroll [Size(width=320, height=94), ScrollRate(value=9)]
  |- Text
  |- Text
  |- Text
  |- Text
  |- Text
  |- Text
  |- Text
  |- Text
  |- Text
  |- Text
  |- Text
  `- Text
```

</details>

<a id="image"></a>

## Image

Image maps one immutable resource-pack image to an exact logical size with deterministic nearest sampling; it is reusable for icons, portraits, diagrams, and Mod-owned panels.

This image is a 32 by 32 component crop from the exact Fabric/headless Mod-screen comparison recorded in [the verification receipt](components/minecraft-26.2-parity.properties).

![Image headless showcase](components/image.png)

### Compiled example

```kotlin
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Grid
import dev.s7a.strata.component.ImageScale
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Slot
import dev.s7a.strata.component.SlotBinding
import dev.s7a.strata.component.Slots
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.UiScope
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.imageBackground
import dev.s7a.strata.modifier.menuBackground
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Builds a resource-pack-aware coal generator screen from general-purpose components.
 *
 * The default fuel and charge slots address the active server-owned container while the lower grid addresses the player's inventory through the active menu.
 * Tests that exercise the same pixels without a live menu may supply null bindings without changing the component structure.
 *
 * @param panel active Mod-resource panel source.
 * @param fuelBinding server-owned combustible-input slot.
 * @param chargeBinding server-owned chargeable-item slot.
 * @param playerInventory resolves each logical player-inventory index used by the lower grid.
 * @return one-shot definition containing only reusable layout, image-background, text, gauge, and slot primitives.
 */
internal fun createIndustrialScreenDefinition(
    panel: ImageSource = coalGeneratorPanel,
    fuelBinding: SlotBinding? = Slots.container(0),
    chargeBinding: SlotBinding? = Slots.container(1),
    playerInventory: (Int) -> SlotBinding? = Slots::playerInventory,
): ScreenDefinition =
    ScreenDefinition("Coal Generator") {
        Stack(
            modifier =
                Modifier.Empty
                    .size(320, 180)
                    .background(ArgbColor(0xFF000000.toInt()))
                    .menuBackground(),
            contentAlignment = Alignment.Center,
        ) {
            Stack(
                modifier =
                    Modifier.Empty
                        .size(176, 166)
                        .imageBackground(panel, ImageScale.Stretch),
            ) {
                Column(
                    modifier = Modifier.Empty.padding(left = 7, top = 5, right = 7, bottom = 7),
                    spacing = 5,
                ) {
                    Text("Coal Generator")
                    Row(
                        modifier = Modifier.Empty.size(162, 36),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = VerticalAlignment.Center,
                    ) {
                        machineSlot("Fuel", fuelBinding)
                        Column(
                            spacing = 3,
                            horizontalAlignment = HorizontalAlignment.Center,
                        ) {
                            Text("32 E/t")
                            Stack(
                                modifier = Modifier.Empty.size(54, 8).background(bufferTrackColor),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Spacer(modifier = Modifier.Empty.size(41, 6).background(bufferFillColor))
                            }
                        }
                        machineSlot("Charge", chargeBinding)
                    }
                    Column(spacing = 1) {
                        Text("Inventory")
                        Grid(columns = 9) {
                            repeat(27) { index ->
                                Slot(bind = playerInventory(9 + index))
                            }
                        }
                        Grid(columns = 9, modifier = Modifier.Empty.padding(top = 4)) {
                            repeat(9) { index ->
                                Slot(bind = playerInventory(index))
                            }
                        }
                    }
                }
            }
        }
    }

private fun UiScope.machineSlot(
    label: String,
    binding: SlotBinding?,
) {
    this.Column(
        spacing = 1,
        horizontalAlignment = HorizontalAlignment.Center,
    ) {
        Text(label)
        Slot(bind = binding)
    }
}

private val coalGeneratorPanel = ImageSource.Resource(ResourceId("strata_test", "textures/gui/coal_generator.png"))
private val bufferTrackColor = ArgbColor(0xFF1A2226.toInt())
private val bufferFillColor = ArgbColor(0xFF20C7DF.toInt())
```

### Modifiers

Sizing and placement modifiers compose around `Image`; `imageBackground` paints the same immutable resource behind any layout component with typed stretch or tile mapping.

### Parent scope

`Image` is a top-level extension on the active `UiScope`. It retains detached pixels rather than a Minecraft resource object, so the Fabric loader may resolve a resource-pack replacement before the description is built.

<details><summary>Component tree</summary>

The tree shows the featured Minecraft component; platform-neutral layout scaffolding remains visible in the compiled source.

```text
`- Image [Size(width=32, height=32)]
```

</details>

<a id="slot"></a>

## Slot

Slot reproduces the native 18 by 18 hit region and 24 by 24 back-item-front highlight order; its binding overload polls real ItemStack state and delegates interaction through Minecraft's active container menu.

This image is a 24 by 24 component crop from the exact native/Fabric/headless parity frame recorded in [the verification receipt](components/minecraft-26.2-parity.properties).

![Slot headless showcase](components/slot.png)

### Compiled example

```kotlin
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Grid
import dev.s7a.strata.component.Slot
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.containerBackground
import dev.s7a.strata.modifier.menuBackground
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Builds the empty three-row Minecraft chest screen used by native, Fabric, and headless parity paths.
 *
 * @return one-shot screen definition reproducing the generic container, labels, 63 Slot hit regions, and hovered highlight order.
 */
internal fun createSlotScreenDefinition(): ScreenDefinition =
    ScreenDefinition("Chest") {
        Stack(
            modifier =
                Modifier.Empty
                    .size(320, 240)
                    .background(ArgbColor(0xFF000000.toInt()))
                    .menuBackground(),
            contentAlignment = Alignment.Center,
        ) {
            Stack(
                modifier = Modifier.Empty.containerBackground(rows = 3),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier.Empty.size(162, 156),
                    spacing = 3,
                ) {
                    Column(spacing = 2) {
                        Text(
                            "Chest",
                            style = TextStyle.ContainerLabel,
                            modifier = Modifier.Empty.padding(left = 1),
                        )
                        Grid(columns = 9) {
                            repeat(27) {
                                Slot()
                            }
                        }
                    }
                    Column {
                        Text(
                            "Inventory",
                            style = TextStyle.ContainerLabel,
                            modifier = Modifier.Empty.padding(left = 1),
                        )
                        Grid(columns = 9, modifier = Modifier.Empty.padding(top = 1)) {
                            repeat(27) {
                                Slot()
                            }
                        }
                        Grid(columns = 9, modifier = Modifier.Empty.padding(top = 4)) {
                            repeat(9) {
                                Slot()
                            }
                        }
                    }
                }
            }
        }
    }
```

### Modifiers

Sizing is native-fixed at 18 by 18. `Slots.playerInventory(index)` binds player storage, `Slots.container(index)` addresses logical storage exposed by chests, ender chests, furnaces, and custom server menus, and `Slots.activeMenu(index)` remains the raw-menu escape hatch; the optional-content overload remains portable for custom item visuals.

### Parent scope

`Slot` is a member extension on the active `UiScope`. Its optional callback emits at most one 16 by 16 content root, while its bound overload obtains the version platform implicitly and retains no public Minecraft type.

<details><summary>Component tree</summary>

The tree shows the featured Minecraft component; platform-neutral layout scaffolding remains visible in the compiled source.

```text
`- Slot [SlotHighlightable(value=true), Size(width=18, height=18)]
```

</details>

<a id="player-head"></a>

## PlayerHead

PlayerHead reproduces Minecraft 26.2 face-then-hat rendering from a 64 by 64 skin; its default 24 by 24 extent matches Social Interactions while remaining reusable in lists, profiles, scoreboards, and Mod screens.

This image is a 24 by 24 component crop from the exact native/Fabric/headless parity frame recorded in [the verification receipt](components/minecraft-26.2-parity.properties).

![PlayerHead headless showcase](components/player-head.png)

### Compiled example

```kotlin
import dev.s7a.strata.component.PlayerHead
import dev.s7a.strata.component.PlayerSkinSource
import dev.s7a.strata.component.Stack
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Builds a reusable player-head screen from a profile lookup or detached skin selected by the version adapter.
 *
 * @param skin player identity lookup or immutable 64 by 64 skin source.
 * @return one-shot definition reproducing the Social Interactions 24 by 24 face and hat layers.
 */
internal fun createPlayerHeadScreenDefinition(
    skin: PlayerSkinSource = PlayerSkinSource.Name("Player0"),
): ScreenDefinition =
    ScreenDefinition("Player head") {
        Stack(
            modifier = Modifier.Empty.size(64, 64).background(ArgbColor(0xFF000000.toInt())),
            contentAlignment = Alignment.Center,
        ) {
            PlayerHead(source = skin)
        }
    }
```

### Modifiers

Sizing and placement modifiers compose around `PlayerHead`; its immutable skin argument stays separate from Social, player-list, scoreboard, profile, and Mod-specific row state.

### Parent scope

`PlayerHead` is a top-level extension on the active `UiScope`. The Fabric skin loader snapshots the current selected resource or downloaded texture first, and the retained component owns only detached pixels.

<details><summary>Component tree</summary>

The tree shows the featured Minecraft component; platform-neutral layout scaffolding remains visible in the compiled source.

```text
`- PlayerHead [Size(width=24, height=24)]
```

</details>

## Complete screens

These screens exercise the primitives in real vanilla-shaped and Mod-shaped use cases.
Purpose-specific compositions stay in the compiled examples instead of becoming standard components; reusable capabilities remain available as general layout, image, text, input, slot-binding, and player-rendering primitives.

- [Social Interactions](#screen-social)
- [Synchronized inventory](#screen-inventory)
- [Industrial controller](#screen-industrial)
- [Power milestones](#screen-progress)

<a id="screen-social"></a>

## Social Interactions

A Social Interactions reconstruction composes `Text`, `TextField`, `Scroll`, `PlayerHead`, and ordinary layout primitives without introducing a purpose-specific SocialEntry component.

A loaded Fabric GameTest requires exact ARGB equality between the native Minecraft screen, the Strata Fabric screen, and the headless frame before this image is accepted.

![Social Interactions screen showcase](components/screen-social.png)

### Compiled screen

```kotlin
import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Image
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.NineSliceCenterMode
import dev.s7a.strata.component.PlayerHead
import dev.s7a.strata.component.PlayerSkinSource
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Tab
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextField
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.imageBackground
import dev.s7a.strata.modifier.initialFocus
import dev.s7a.strata.modifier.menuBackground
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Builds the deterministic one-player Minecraft Social Interactions screen from general-purpose primitives.
 *
 * Social-entry composition remains application code: the public runtime supplies PlayerHead, text, actions, images, fields, layout, and active backgrounds without exposing a purpose-specific SocialEntry component.
 *
 * @param panel active-resource `social_interactions/background` source.
 * @param searchIcon active-resource `icon/search` source.
 * @param playerSkin selected player lookup or detached skin source.
 * @return one-shot screen definition reproducing the native screen geometry and draw order.
 */
internal fun createSocialScreenDefinition(
    panel: ImageSource = socialPanel,
    searchIcon: ImageSource = socialSearchIcon,
    playerSkin: PlayerSkinSource = PlayerSkinSource.Name("Player0"),
): ScreenDefinition {
    val search = TextFieldState("", maxLength = 16)
    return ScreenDefinition("Social Interactions") {
        Stack(
            modifier =
                Modifier.Empty
                    .size(320, 240)
                    .background(ArgbColor(0xFF000000.toInt()))
                    .menuBackground(),
        ) {
            Stack(
                modifier = Modifier.Empty.size(320, 176),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Stack(
                    modifier =
                        Modifier.Empty
                            .padding(left = 4)
                            .size(236, 112)
                            .imageBackground(
                                panel,
                                Insets.all(8),
                                NineSliceCenterMode.Tiled,
                            ),
                ) {}
            }
            Column(
                modifier = Modifier.Empty.size(222, 234).align(Alignment.TopCenter),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.Empty.padding(top = 12)) {
                    Column(
                        modifier = Modifier.Empty.size(222, 32),
                        spacing = 14,
                    ) {
                        Text(
                            "Social Interactions",
                            modifier = Modifier.Empty.align(HorizontalAlignment.Center),
                        )
                        Text("Player0 - New World - 1 player")
                    }
                    Row(modifier = Modifier.Empty.padding(left = 1, top = 1), spacing = 1) {
                        Tab("All", selected = true, width = 73, modifier = Modifier.Empty.onPress {})
                        Tab("Hidden", selected = false, width = 73, modifier = Modifier.Empty.onPress {})
                        Tab("Blocked", selected = false, width = 73, modifier = Modifier.Empty.onPress {})
                    }
                    Row(
                        modifier = Modifier.Empty.padding(left = 5, top = 9),
                        spacing = 3,
                        verticalAlignment = VerticalAlignment.Center,
                    ) {
                        Image(
                            searchIcon,
                            size = IntSize(12, 12),
                            modifier = Modifier.Empty.padding(top = 2),
                        )
                        TextField(
                            search,
                            size = IntSize(200, 15),
                            textStyle = TextStyle.Normal,
                            modifier = Modifier.Empty.initialFocus(),
                        )
                    }
                    Row(
                        modifier =
                            Modifier.Empty
                                .padding(left = 3, top = 3)
                                .size(216, 32)
                                .background(ArgbColor(0xFF4A4A4A.toInt())),
                        spacing = 4,
                        verticalAlignment = VerticalAlignment.Center,
                    ) {
                        PlayerHead(source = playerSkin, modifier = Modifier.Empty.padding(left = 4))
                        Text("Player0")
                    }
                }
                Button(
                    "Done",
                    width = 200,
                    modifier = Modifier.Empty.align(HorizontalAlignment.Center).onPress {},
                )
            }
        }
    }
}

private val socialPanel = ImageSource.Resource(ResourceId("minecraft", "textures/gui/sprites/social_interactions/background.png"))
private val socialSearchIcon = ImageSource.Resource(ResourceId("minecraft", "textures/gui/sprites/icon/search.png"))
```

### Primitive boundary

The player row remains example-owned because its mute/report relationship is Social-screen domain state. `PlayerHead`, scrolling, text input, text, buttons, and pointer actions remain reusable standard primitives.

<a id="screen-inventory"></a>

## Synchronized inventory

A loaded multiplayer container screen binds its lower grid to the real player inventory and can bind the upper grid to chest, ender-chest, furnace, or custom server-menu storage.

A loaded Fabric client/server GameTest performs authoritative inventory interaction and records the resulting real Fabric screen; this bound screen intentionally has no portable-only headless substitute.

![Synchronized inventory screen showcase](components/screen-inventory.png)

### Compiled screen

```kotlin
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Grid
import dev.s7a.strata.component.Slot
import dev.s7a.strata.component.SlotBinding
import dev.s7a.strata.component.Slots
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.containerBackground
import dev.s7a.strata.modifier.menuBackground
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Builds a generic chest-shaped screen whose lower 36 Slots are bound to the active player's inventory.
 *
 * The upper grid remains empty unless [primaryContainerBinding] is supplied by a server-owned container test.
 * The returned definition requires the Fabric version adapter and is not renderable by the portable-only headless host.
 *
 * @param primaryPlayerBinding binding used by the first hotbar cell.
 * @param primaryContainerBinding optional binding used by the first upper Container cell.
 * @return one-shot screen definition used to verify live item rendering and authoritative container input in a loaded client.
 */
internal fun createInventorySlotScreenDefinition(
    primaryPlayerBinding: SlotBinding = Slots.playerInventory(0),
    primaryContainerBinding: SlotBinding? = null,
): ScreenDefinition =
    ScreenDefinition("Synchronized inventory") {
        Stack(
            modifier =
                Modifier.Empty
                    .size(320, 240)
                    .background(ArgbColor(0xFF000000.toInt()))
                    .menuBackground(),
            contentAlignment = Alignment.Center,
        ) {
            Stack(
                modifier = Modifier.Empty.containerBackground(rows = 3),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier.Empty.size(162, 156),
                    spacing = 3,
                ) {
                    Column(spacing = 2) {
                        Text(
                            "Chest",
                            style = TextStyle.ContainerLabel,
                            modifier = Modifier.Empty.padding(left = 1),
                        )
                        Grid(columns = 9) {
                            repeat(27) { index ->
                                if (index == 0 && primaryContainerBinding != null) {
                                    Slot(bind = primaryContainerBinding)
                                } else {
                                    Slot()
                                }
                            }
                        }
                    }
                    Column {
                        Text(
                            "Inventory",
                            style = TextStyle.ContainerLabel,
                            modifier = Modifier.Empty.padding(left = 1),
                        )
                        Grid(columns = 9, modifier = Modifier.Empty.padding(top = 1)) {
                            repeat(27) { index ->
                                Slot(bind = Slots.playerInventory(9 + index))
                            }
                        }
                        Grid(columns = 9, modifier = Modifier.Empty.padding(top = 4)) {
                            repeat(9) { index ->
                                Slot(
                                    bind =
                                        if (index == 0) {
                                            primaryPlayerBinding
                                        } else {
                                            Slots.playerInventory(index)
                                        },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
```

### Primitive boundary

`Slot` and `SlotBinding` are reusable primitives. The chest-shaped grouping and server menu decide which player, container, ender-chest, furnace, or custom inventory indices each slot binds.

<a id="screen-industrial"></a>

## Industrial controller

A resource-pack-aware Mod controller composes a public custom image, Minecraft text, buttons, and layout primitives into an energy-machine interface.

A loaded Fabric GameTest requires exact ARGB equality between the Strata Fabric screen and the headless frame while resolving assets from Minecraft's active resource manager.

![Industrial controller screen showcase](components/screen-industrial.png)

### Compiled screen

```kotlin
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Grid
import dev.s7a.strata.component.ImageScale
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Slot
import dev.s7a.strata.component.SlotBinding
import dev.s7a.strata.component.Slots
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.UiScope
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.imageBackground
import dev.s7a.strata.modifier.menuBackground
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Builds a resource-pack-aware coal generator screen from general-purpose components.
 *
 * The default fuel and charge slots address the active server-owned container while the lower grid addresses the player's inventory through the active menu.
 * Tests that exercise the same pixels without a live menu may supply null bindings without changing the component structure.
 *
 * @param panel active Mod-resource panel source.
 * @param fuelBinding server-owned combustible-input slot.
 * @param chargeBinding server-owned chargeable-item slot.
 * @param playerInventory resolves each logical player-inventory index used by the lower grid.
 * @return one-shot definition containing only reusable layout, image-background, text, gauge, and slot primitives.
 */
internal fun createIndustrialScreenDefinition(
    panel: ImageSource = coalGeneratorPanel,
    fuelBinding: SlotBinding? = Slots.container(0),
    chargeBinding: SlotBinding? = Slots.container(1),
    playerInventory: (Int) -> SlotBinding? = Slots::playerInventory,
): ScreenDefinition =
    ScreenDefinition("Coal Generator") {
        Stack(
            modifier =
                Modifier.Empty
                    .size(320, 180)
                    .background(ArgbColor(0xFF000000.toInt()))
                    .menuBackground(),
            contentAlignment = Alignment.Center,
        ) {
            Stack(
                modifier =
                    Modifier.Empty
                        .size(176, 166)
                        .imageBackground(panel, ImageScale.Stretch),
            ) {
                Column(
                    modifier = Modifier.Empty.padding(left = 7, top = 5, right = 7, bottom = 7),
                    spacing = 5,
                ) {
                    Text("Coal Generator")
                    Row(
                        modifier = Modifier.Empty.size(162, 36),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = VerticalAlignment.Center,
                    ) {
                        machineSlot("Fuel", fuelBinding)
                        Column(
                            spacing = 3,
                            horizontalAlignment = HorizontalAlignment.Center,
                        ) {
                            Text("32 E/t")
                            Stack(
                                modifier = Modifier.Empty.size(54, 8).background(bufferTrackColor),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Spacer(modifier = Modifier.Empty.size(41, 6).background(bufferFillColor))
                            }
                        }
                        machineSlot("Charge", chargeBinding)
                    }
                    Column(spacing = 1) {
                        Text("Inventory")
                        Grid(columns = 9) {
                            repeat(27) { index ->
                                Slot(bind = playerInventory(9 + index))
                            }
                        }
                        Grid(columns = 9, modifier = Modifier.Empty.padding(top = 4)) {
                            repeat(9) { index ->
                                Slot(bind = playerInventory(index))
                            }
                        }
                    }
                }
            }
        }
    }

private fun UiScope.machineSlot(
    label: String,
    binding: SlotBinding?,
) {
    this.Column(
        spacing = 1,
        horizontalAlignment = HorizontalAlignment.Center,
    ) {
        Text(label)
        Slot(bind = binding)
    }
}

private val coalGeneratorPanel = ImageSource.Resource(ResourceId("strata_test", "textures/gui/coal_generator.png"))
private val bufferTrackColor = ArgbColor(0xFF1A2226.toInt())
private val bufferFillColor = ArgbColor(0xFF20C7DF.toInt())
```

### Primitive boundary

The runtime supplies general image, background, text, button, slot, and input primitives. Energy capacity, charge state, machine recipes, and networking remain application-owned state and server protocol.

<a id="screen-progress"></a>

## Power milestones

An advancement-inspired Mod progression screen composes active vanilla advancement assets with an application-owned downstream graph component.

A loaded Fabric GameTest requires exact ARGB equality between the Strata Fabric screen and the headless frame while resolving assets from Minecraft's active resource manager.

![Power milestones screen showcase](components/screen-progress.png)

### Compiled screen

```kotlin
import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Image
import dev.s7a.strata.component.ImageScale
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.component.UiScope
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.imageBackground
import dev.s7a.strata.modifier.menuBackground
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Builds one advancement-inspired Mod screen from resource-pack sources and an application-owned component.
 *
 * The standard runtime remains limited to reusable primitives; [ExampleProgressGraph] may encode this Mod's progression domain because it remains downstream application code.
 *
 * @param window active advancement-window source.
 * @param background active advancement-background tile source.
 * @param obtained active obtained task-frame source.
 * @param unobtained active unobtained task-frame source.
 * @return one-shot definition for the verified Fabric and headless screen.
 */
internal fun createProgressScreenDefinition(
    window: ImageSource = advancementWindow,
    background: ImageSource = advancementBackground,
    obtained: ImageSource = obtainedTaskFrame,
    unobtained: ImageSource = unobtainedTaskFrame,
): ScreenDefinition =
    ScreenDefinition("Power milestones") {
        Stack(
            modifier =
                Modifier.Empty
                    .size(320, 180)
                    .background(ArgbColor(0xFF000000.toInt()))
                    .menuBackground(),
            contentAlignment = Alignment.Center,
        ) {
            Stack(modifier = Modifier.Empty.size(252, 140)) {
                Image(window, sourceRegion = IntRect(0, 0, 252, 140))
                Column(
                    modifier = Modifier.Empty.padding(left = 9, top = 6, right = 9, bottom = 9),
                    spacing = 4,
                    horizontalAlignment = HorizontalAlignment.Center,
                ) {
                    Text("Power milestones", style = TextStyle.ContainerLabel)
                    ExampleProgressGraph(background, obtained, unobtained)
                }
            }
            Button(
                "Done",
                width = 200,
                modifier =
                    Modifier.Empty
                        .padding(bottom = 6)
                        .align(Alignment.BottomCenter)
                        .onPress {},
            )
        }
    }

/**
 * Emits one application-owned progression graph by composing only public Strata primitives.
 *
 * This downstream component is deliberately not part of the standard runtime because its node meanings and progression domain belong to the application.
 * It retains no callback or scope after synchronous emission.
 *
 * @receiver active owner-thread UI scope.
 * @param background immutable or resource-backed background tile.
 * @param obtained immutable or resource-backed obtained frame.
 * @param unobtained immutable or resource-backed unobtained frame.
 * @param modifier active behavior surrounding the fixed graph.
 * @param key optional stable sibling identity.
 * @throws IllegalStateException when used from another thread or outside its callback lifetime.
 */
internal fun UiScope.ExampleProgressGraph(
    background: ImageSource,
    obtained: ImageSource,
    unobtained: ImageSource,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    Row(
        modifier = modifier.size(234, 113).imageBackground(background, ImageScale.Tile),
        key = key,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = VerticalAlignment.Center,
    ) {
        progressNode(obtained, ArgbColor(0xFF22D3EE.toInt()), "Generator")
        Spacer(modifier = Modifier.Empty.size(32, 2).background(connectionColor))
        Column(
            spacing = 4,
            horizontalAlignment = HorizontalAlignment.Center,
        ) {
            progressNode(obtained, ArgbColor(0xFFFBBF24.toInt()), "Storage")
            Spacer(modifier = Modifier.Empty.size(2, 12).background(connectionColor))
            progressNode(unobtained, ArgbColor(0xFFA78BFA.toInt()), "Automation")
        }
    }
}

private fun UiScope.progressNode(
    frame: ImageSource,
    color: ArgbColor,
    label: String,
) {
    this.Column(
        horizontalAlignment = HorizontalAlignment.Center,
        spacing = 1,
    ) {
        Stack(
            modifier = Modifier.Empty.size(26, 26),
            contentAlignment = Alignment.Center,
        ) {
            Image(frame)
            Spacer(modifier = Modifier.Empty.size(16, 16).background(color))
        }
        Text(label)
    }
}

private val advancementWindow = ImageSource.Resource(ResourceId("minecraft", "textures/gui/advancements/window.png"))
private val advancementBackground = ImageSource.Resource(ResourceId("minecraft", "textures/gui/advancements/backgrounds/stone.png"))
private val obtainedTaskFrame = ImageSource.Resource(ResourceId("minecraft", "textures/gui/sprites/advancements/task_frame_obtained.png"))
private val unobtainedTaskFrame = ImageSource.Resource(ResourceId("minecraft", "textures/gui/sprites/advancements/task_frame_unobtained.png"))
private val connectionColor = ArgbColor(0xFF7A7A7A.toInt())
```

### Primitive boundary

`ExampleProgressGraph` deliberately stays in downstream example code because milestone names and graph meaning are specific to this Mod. Images, backgrounds, text, buttons, layout, and pointer actions remain reusable primitives.
