<!-- Generated file. Do not edit. -->

# Minecraft component showcase

These deterministic crops come from real Minecraft 26.2 `ConfirmScreen`, `DirectJoinServerScreen`, `ContainerScreen`, and native `ObjectSelectionList` screens reconstructed with Strata's `Text`, `TextField`, `Button`, `Scroll`, and `Slot` components, plus a test Mod screen built with `Image` and a resource-pack asset.
The menu and generic-container images are active background modifiers on layout components rather than logical component entries.
One loaded Fabric GameTest requires exact ARGB equality among each native screen, the Fabric adapter, and the headless frame before it emits these component images.

[Open the machine-readable parity receipt](components/minecraft-26.2-parity.properties)

![Overview headless showcase](components/overview.png)

## Overview source

```kotlin
import dev.s7a.strata.dsl.Box
import dev.s7a.strata.dsl.Column
import dev.s7a.strata.dsl.Row
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.size
import dev.s7a.strata.runtime.minecraft.Button
import dev.s7a.strata.runtime.minecraft.MinecraftScreenDefinition
import dev.s7a.strata.runtime.minecraft.Text
import dev.s7a.strata.runtime.minecraft.createMinecraftScreenDefinition
import dev.s7a.strata.runtime.minecraft.menuBackground

/**
 * Builds the deterministic Minecraft 26.2 ConfirmScreen content used by the Fabric and headless parity paths.
 *
 * @return one-shot screen definition reproducing the native title, message, and button-row geometry.
 */
internal fun createConfirmScreenDefinition(): MinecraftScreenDefinition =
    createMinecraftScreenDefinition("Strata parity") {
        Box(
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
|- Text
|- Text
|- Button
`- Button
```

</details>

## Components

- [Text](#text)
- [TextField](#text-field)
- [Button](#button)
- [Scroll](#scroll)
- [Slot](#slot)
- [Image](#image)
- [PlayerHead](#player-head)

<a id="text"></a>

## Text

Text renders a printable-ASCII literal with the extracted Minecraft glyph advances, shadow layer, foreground layer, and native baseline.

This image is a 150 by 20 component crop from the exact native/Fabric/headless parity frame recorded in [the verification receipt](components/minecraft-26.2-parity.properties).

![Text headless showcase](components/text.png)

### Compiled example

```kotlin
import dev.s7a.strata.dsl.Box
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.size
import dev.s7a.strata.runtime.minecraft.MinecraftScreenDefinition
import dev.s7a.strata.runtime.minecraft.Text
import dev.s7a.strata.runtime.minecraft.createMinecraftScreenDefinition

/**
 * Builds the literal Text component used by the verified ConfirmScreen title.
 *
 * @return one-shot screen definition whose content resolves components from the host-installed Minecraft profile.
 */
internal fun textExample(): MinecraftScreenDefinition =
    createMinecraftScreenDefinition("Text") {
        Box(
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
import dev.s7a.strata.dsl.Box
import dev.s7a.strata.dsl.Column
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.runtime.minecraft.Button
import dev.s7a.strata.runtime.minecraft.MinecraftScreenDefinition
import dev.s7a.strata.runtime.minecraft.MinecraftTextStyle
import dev.s7a.strata.runtime.minecraft.Text
import dev.s7a.strata.runtime.minecraft.TextField
import dev.s7a.strata.runtime.minecraft.createMinecraftScreenDefinition
import dev.s7a.strata.runtime.minecraft.createMinecraftTextFieldState
import dev.s7a.strata.runtime.minecraft.menuBackground

/**
 * Builds the Minecraft 26.2 Direct Connection screen used by native, Fabric, and headless parity paths.
 *
 * @return one-shot screen definition with the actual EditBox and 200-pixel Button geometry.
 */
internal fun createDirectJoinScreenDefinition(): MinecraftScreenDefinition {
    val address = createMinecraftTextFieldState("play.example.net", maxLength = 128)
    return createMinecraftScreenDefinition("Direct Connection") {
        Box(modifier = Modifier.Empty.size(320, 240).menuBackground()) {
            Column(
                modifier = Modifier.Empty.size(320, 212),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = HorizontalAlignment.Center,
            ) {
                Box(
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
                            style = MinecraftTextStyle.Inactive,
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

`TextField` is a member extension on the active `UiScope`. The implicit runtime context supplies assets, while caller-owned `MinecraftTextFieldState` owns the editable value.

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
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onHover
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.runtime.minecraft.Button
import dev.s7a.strata.runtime.minecraft.MinecraftScreenDefinition
import dev.s7a.strata.runtime.minecraft.createMinecraftScreenDefinition

/**
 * Builds the pointer Button used by the verified ConfirmScreen action row.
 *
 * @return one-shot screen definition whose content resolves components from the host-installed Minecraft profile.
 */
internal fun buttonExample(): MinecraftScreenDefinition =
    createMinecraftScreenDefinition("Button") {
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

<a id="scroll"></a>

## Scroll

Scroll reproduces the verified Minecraft 26.2 menu-list background, clipped centered content, separators, tiled scrollbar sprites, wheel rate, and proportional thumb movement in native draw order.

This image is a 320 by 94 component crop from the exact native/Fabric/headless parity frame recorded in [the verification receipt](components/minecraft-26.2-parity.properties).

![Scroll headless showcase](components/scroll.png)

### Compiled example

```kotlin
import dev.s7a.strata.dsl.Box
import dev.s7a.strata.dsl.Column
import dev.s7a.strata.dsl.Spacer
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.fillMaxSize
import dev.s7a.strata.modifier.size
import dev.s7a.strata.runtime.minecraft.MinecraftScreenDefinition
import dev.s7a.strata.runtime.minecraft.Scroll
import dev.s7a.strata.runtime.minecraft.Text
import dev.s7a.strata.runtime.minecraft.createMinecraftScreenDefinition
import dev.s7a.strata.runtime.minecraft.menuBackground

/**
 * Builds the deterministic Minecraft 26.2 selection-list screen used by the native, Fabric, and headless parity paths.
 *
 * @return one-shot screen definition reproducing the native list viewport, row geometry, separators, scrollbar, and text.
 */
internal fun createScrollScreenDefinition(): MinecraftScreenDefinition =
    createMinecraftScreenDefinition("Strata Scroll parity") {
        Box(modifier = Modifier.Empty.size(320, 180).menuBackground()) {
            Column(modifier = Modifier.Empty.fillMaxSize()) {
                Spacer(modifier = Modifier.Empty.size(320, 33))
                Scroll(modifier = Modifier.Empty.size(320, 94)) {
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
                            Box(
                                modifier = Modifier.Empty.size(270, 18),
                                contentAlignment = Alignment.TopCenter,
                            ) {
                                Column(horizontalAlignment = HorizontalAlignment.Center) {
                                    Spacer(modifier = Modifier.Empty.size(0, 5))
                                    Text(label)
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.Empty.size(320, 53))
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

<a id="slot"></a>

## Slot

Slot reproduces the native 18 by 18 hit region and 24 by 24 back-item-front highlight order; its binding overload polls real ItemStack state and delegates interaction through Minecraft's active container menu.

This image is a 24 by 24 component crop from the exact native/Fabric/headless parity frame recorded in [the verification receipt](components/minecraft-26.2-parity.properties).

![Slot headless showcase](components/slot.png)

### Compiled example

```kotlin
import dev.s7a.strata.dsl.Box
import dev.s7a.strata.dsl.Column
import dev.s7a.strata.dsl.Row
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.minecraft.MinecraftScreenDefinition
import dev.s7a.strata.runtime.minecraft.MinecraftTextStyle
import dev.s7a.strata.runtime.minecraft.Slot
import dev.s7a.strata.runtime.minecraft.Text
import dev.s7a.strata.runtime.minecraft.containerBackground
import dev.s7a.strata.runtime.minecraft.createMinecraftScreenDefinition
import dev.s7a.strata.runtime.minecraft.menuBackground

/**
 * Builds the empty three-row Minecraft 26.2 chest screen used by native, Fabric, and headless parity paths.
 *
 * @return one-shot screen definition reproducing the generic container, labels, 63 Slot hit regions, and hovered highlight order.
 */
internal fun createSlotScreenDefinition(): MinecraftScreenDefinition =
    createMinecraftScreenDefinition("Chest") {
        Box(
            modifier =
                Modifier.Empty
                    .size(320, 240)
                    .background(ArgbColor(0xFF000000.toInt()))
                    .menuBackground(),
            contentAlignment = Alignment.Center,
        ) {
            Box(
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
                            style = MinecraftTextStyle.ContainerLabel,
                            modifier = Modifier.Empty.padding(left = 1),
                        )
                        Column {
                            repeat(3) {
                                Row {
                                    repeat(9) {
                                        Slot()
                                    }
                                }
                            }
                        }
                    }
                    Column {
                        Text(
                            "Inventory",
                            style = MinecraftTextStyle.ContainerLabel,
                            modifier = Modifier.Empty.padding(left = 1),
                        )
                        Column(modifier = Modifier.Empty.padding(top = 1)) {
                            repeat(3) {
                                Row {
                                    repeat(9) {
                                        Slot()
                                    }
                                }
                            }
                        }
                        Row(modifier = Modifier.Empty.padding(top = 4)) {
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

Sizing is native-fixed at 18 by 18. `MinecraftSlots.playerInventory(index)` binds player storage, `MinecraftSlots.container(index)` addresses logical storage exposed by chests, ender chests, furnaces, and custom server menus, and `MinecraftSlots.activeMenu(index)` remains the raw-menu escape hatch; the optional-content overload remains portable for custom item visuals.

### Parent scope

`Slot` is a member extension on the active `UiScope`. Its optional callback emits at most one 16 by 16 content root, while its bound overload obtains the version platform implicitly and retains no public Minecraft type.

<details><summary>Component tree</summary>

The tree shows the featured Minecraft component; platform-neutral layout scaffolding remains visible in the compiled source.

```text
`- Slot [SlotHighlightable(value=true), Size(width=18, height=18)]
```

</details>

<a id="image"></a>

## Image

Image maps one immutable resource-pack image to an exact logical size with deterministic nearest sampling; it is reusable for icons, portraits, diagrams, and Mod-owned panels.

This image is a 32 by 32 component crop from the exact Fabric/headless Mod-screen comparison recorded in [the verification receipt](components/minecraft-26.2-parity.properties).

![Image headless showcase](components/image.png)

### Compiled example

```kotlin
import dev.s7a.strata.dsl.Box
import dev.s7a.strata.dsl.Column
import dev.s7a.strata.dsl.Row
import dev.s7a.strata.dsl.Spacer
import dev.s7a.strata.dsl.UiScope
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.minecraft.Button
import dev.s7a.strata.runtime.minecraft.Image
import dev.s7a.strata.runtime.minecraft.MinecraftScreenDefinition
import dev.s7a.strata.runtime.minecraft.Slot
import dev.s7a.strata.runtime.minecraft.Text
import dev.s7a.strata.runtime.minecraft.createMinecraftScreenDefinition
import dev.s7a.strata.runtime.minecraft.imageBackground

/**
 * Builds a reusable industrial Mod screen from general-purpose Strata primitives and one replaceable resource-pack asset.
 *
 * @param panel immutable panel pixels loaded by the version adapter from the active resource manager.
 * @return one-shot definition containing image, text, slot, layout, gauge composition, and button primitives.
 */
internal fun createIndustrialScreenDefinition(panel: DrawImage): MinecraftScreenDefinition =
    createMinecraftScreenDefinition("Industrial controller") {
        Box(
            modifier = Modifier.Empty.size(320, 180).imageBackground(panel),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                spacing = 8,
                horizontalAlignment = HorizontalAlignment.Center,
            ) {
                Image(panel, IntSize(32, 32))
                Text("ENERGY CONTROL")
                Box(
                    modifier = Modifier.Empty.size(150, 8).background(ArgbColor(0xFF101820.toInt())),
                ) {
                    Spacer(modifier = Modifier.Empty.size(112, 8).background(ArgbColor(0xFF22D3EE.toInt())))
                }
                Row(spacing = 4) {
                    machineSlot(ArgbColor(0xFFFBBF24.toInt()))
                    machineSlot(ArgbColor(0xFF22D3EE.toInt()))
                    machineSlot(ArgbColor(0xFFA78BFA.toInt()))
                }
                Button(
                    "Toggle power",
                    width = 100,
                    modifier = Modifier.Empty.onPress {},
                )
            }
        }
    }

private fun UiScope.machineSlot(color: ArgbColor) {
    Slot {
        Spacer(modifier = Modifier.Empty.size(16, 16).background(color))
    }
}
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

<a id="player-head"></a>

## PlayerHead

PlayerHead reproduces Minecraft 26.2 face-then-hat rendering from a 64 by 64 skin; its default 24 by 24 extent matches Social Interactions while remaining reusable in lists, profiles, scoreboards, and Mod screens.

This image is a 24 by 24 component crop from the exact native/Fabric/headless parity frame recorded in [the verification receipt](components/minecraft-26.2-parity.properties).

![PlayerHead headless showcase](components/player-head.png)

### Compiled example

```kotlin
import dev.s7a.strata.dsl.Box
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.minecraft.MinecraftScreenDefinition
import dev.s7a.strata.runtime.minecraft.PlayerHead
import dev.s7a.strata.runtime.minecraft.createMinecraftScreenDefinition

/**
 * Builds a reusable player-head screen from a detached skin selected by the version adapter.
 *
 * @param skin immutable 64 by 64 player skin from Minecraft's resource or downloaded-texture path.
 * @return one-shot definition reproducing the Social Interactions 24 by 24 face and hat layers.
 */
internal fun createPlayerHeadScreenDefinition(skin: DrawImage): MinecraftScreenDefinition =
    createMinecraftScreenDefinition("Player head") {
        Box(
            modifier = Modifier.Empty.size(64, 64).background(ArgbColor(0xFF000000.toInt())),
            contentAlignment = Alignment.Center,
        ) {
            PlayerHead(skin)
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
import dev.s7a.strata.dsl.Box
import dev.s7a.strata.dsl.Column
import dev.s7a.strata.dsl.Row
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.initialFocus
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.minecraft.Button
import dev.s7a.strata.runtime.minecraft.Image
import dev.s7a.strata.runtime.minecraft.MinecraftNineSliceCenterMode
import dev.s7a.strata.runtime.minecraft.MinecraftScreenDefinition
import dev.s7a.strata.runtime.minecraft.MinecraftTextFieldState
import dev.s7a.strata.runtime.minecraft.MinecraftTextStyle
import dev.s7a.strata.runtime.minecraft.PlayerHead
import dev.s7a.strata.runtime.minecraft.Text
import dev.s7a.strata.runtime.minecraft.TextField
import dev.s7a.strata.runtime.minecraft.createMinecraftScreenDefinition
import dev.s7a.strata.runtime.minecraft.createMinecraftTextFieldState
import dev.s7a.strata.runtime.minecraft.imageBackground
import dev.s7a.strata.runtime.minecraft.menuBackground

/**
 * Builds the deterministic one-player Minecraft 26.2 Social Interactions screen from general-purpose primitives.
 *
 * Social-entry composition remains application code: the public runtime supplies PlayerHead, text, actions, images, fields, layout, and active backgrounds without exposing a purpose-specific SocialEntry component.
 *
 * @param panel exact active-resource `social_interactions/background` pixels.
 * @param searchIcon exact active-resource `icon/search` pixels.
 * @param playerSkin detached selected-player skin pixels.
 * @return one-shot screen definition reproducing the native screen geometry and draw order.
 */
internal fun createSocialScreenDefinition(
    panel: DrawImage,
    searchIcon: DrawImage,
    playerSkin: DrawImage,
): MinecraftScreenDefinition {
    val search = createMinecraftTextFieldState("", maxLength = 16)
    return createMinecraftScreenDefinition("Social Interactions") {
        Box(
            modifier =
                Modifier.Empty
                    .size(320, 240)
                    .background(ArgbColor(0xFF000000.toInt()))
                    .menuBackground(),
        ) {
            Box(
                modifier = Modifier.Empty.size(320, 176),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    modifier =
                        Modifier.Empty
                            .padding(left = 4)
                            .size(236, 112)
                            .imageBackground(
                                panel,
                                Insets.all(8),
                                MinecraftNineSliceCenterMode.Tiled,
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
                        Box(modifier = Modifier.Empty.size(73, 20)) {
                            Button("All", width = 73, modifier = Modifier.Empty.onPress {})
                            Box(
                                modifier = Modifier.Empty.size(73, 16),
                                contentAlignment = Alignment.BottomCenter,
                            ) {
                                Box(
                                    modifier =
                                        Modifier.Empty
                                            .size(13, 1)
                                            .background(ArgbColor(0xFF3F3F3F.toInt())),
                                ) {}
                            }
                            Box(
                                modifier = Modifier.Empty.size(72, 15),
                                contentAlignment = Alignment.BottomCenter,
                            ) {
                                Box(
                                    modifier =
                                        Modifier.Empty
                                            .size(13, 1)
                                            .background(ArgbColor(0xFFFFFFFF.toInt())),
                                ) {}
                            }
                        }
                        Button("Hidden", width = 73, modifier = Modifier.Empty.onPress {})
                        Button("Blocked", width = 73, modifier = Modifier.Empty.onPress {})
                    }
                    Row(
                        modifier = Modifier.Empty.padding(left = 5, top = 9),
                        spacing = 3,
                        verticalAlignment = VerticalAlignment.Center,
                    ) {
                        Image(
                            searchIcon,
                            IntSize(12, 12),
                            modifier = Modifier.Empty.padding(top = 2),
                        )
                        TextField(
                            search,
                            size = IntSize(200, 15),
                            textStyle = MinecraftTextStyle.Normal,
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
                        PlayerHead(playerSkin, modifier = Modifier.Empty.padding(left = 4))
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
import dev.s7a.strata.dsl.Box
import dev.s7a.strata.dsl.Column
import dev.s7a.strata.dsl.Row
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.minecraft.MinecraftScreenDefinition
import dev.s7a.strata.runtime.minecraft.MinecraftSlotBinding
import dev.s7a.strata.runtime.minecraft.MinecraftSlots
import dev.s7a.strata.runtime.minecraft.MinecraftTextStyle
import dev.s7a.strata.runtime.minecraft.Slot
import dev.s7a.strata.runtime.minecraft.Text
import dev.s7a.strata.runtime.minecraft.containerBackground
import dev.s7a.strata.runtime.minecraft.createMinecraftScreenDefinition
import dev.s7a.strata.runtime.minecraft.menuBackground

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
    primaryPlayerBinding: MinecraftSlotBinding = MinecraftSlots.playerInventory(0),
    primaryContainerBinding: MinecraftSlotBinding? = null,
): MinecraftScreenDefinition =
    createMinecraftScreenDefinition("Synchronized inventory") {
        Box(
            modifier =
                Modifier.Empty
                    .size(320, 240)
                    .background(ArgbColor(0xFF000000.toInt()))
                    .menuBackground(),
            contentAlignment = Alignment.Center,
        ) {
            Box(
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
                            style = MinecraftTextStyle.ContainerLabel,
                            modifier = Modifier.Empty.padding(left = 1),
                        )
                        Column {
                            repeat(3) { row ->
                                Row {
                                    repeat(9) { column ->
                                        if (row == 0 && column == 0 && primaryContainerBinding != null) {
                                            Slot(bind = primaryContainerBinding)
                                        } else {
                                            Slot()
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Column {
                        Text(
                            "Inventory",
                            style = MinecraftTextStyle.ContainerLabel,
                            modifier = Modifier.Empty.padding(left = 1),
                        )
                        Column(modifier = Modifier.Empty.padding(top = 1)) {
                            repeat(3) { row ->
                                Row {
                                    repeat(9) { column ->
                                        Slot(bind = MinecraftSlots.playerInventory(9 + row * 9 + column))
                                    }
                                }
                            }
                        }
                        Row(modifier = Modifier.Empty.padding(top = 4)) {
                            repeat(9) { column ->
                                Slot(
                                    bind =
                                        if (column == 0) {
                                            primaryPlayerBinding
                                        } else {
                                            MinecraftSlots.playerInventory(column)
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

`Slot` and `MinecraftSlotBinding` are reusable primitives. The chest-shaped grouping and server menu decide which player, container, ender-chest, furnace, or custom inventory indices each slot binds.

<a id="screen-industrial"></a>

## Industrial controller

A resource-pack-aware Mod controller composes a public custom image, Minecraft text, buttons, and layout primitives into an energy-machine interface.

A loaded Fabric GameTest requires exact ARGB equality between the Strata Fabric screen and the headless frame while resolving assets from Minecraft's active resource manager.

![Industrial controller screen showcase](components/screen-industrial.png)

### Compiled screen

```kotlin
import dev.s7a.strata.dsl.Box
import dev.s7a.strata.dsl.Column
import dev.s7a.strata.dsl.Row
import dev.s7a.strata.dsl.Spacer
import dev.s7a.strata.dsl.UiScope
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.minecraft.Button
import dev.s7a.strata.runtime.minecraft.Image
import dev.s7a.strata.runtime.minecraft.MinecraftScreenDefinition
import dev.s7a.strata.runtime.minecraft.Slot
import dev.s7a.strata.runtime.minecraft.Text
import dev.s7a.strata.runtime.minecraft.createMinecraftScreenDefinition
import dev.s7a.strata.runtime.minecraft.imageBackground

/**
 * Builds a reusable industrial Mod screen from general-purpose Strata primitives and one replaceable resource-pack asset.
 *
 * @param panel immutable panel pixels loaded by the version adapter from the active resource manager.
 * @return one-shot definition containing image, text, slot, layout, gauge composition, and button primitives.
 */
internal fun createIndustrialScreenDefinition(panel: DrawImage): MinecraftScreenDefinition =
    createMinecraftScreenDefinition("Industrial controller") {
        Box(
            modifier = Modifier.Empty.size(320, 180).imageBackground(panel),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                spacing = 8,
                horizontalAlignment = HorizontalAlignment.Center,
            ) {
                Image(panel, IntSize(32, 32))
                Text("ENERGY CONTROL")
                Box(
                    modifier = Modifier.Empty.size(150, 8).background(ArgbColor(0xFF101820.toInt())),
                ) {
                    Spacer(modifier = Modifier.Empty.size(112, 8).background(ArgbColor(0xFF22D3EE.toInt())))
                }
                Row(spacing = 4) {
                    machineSlot(ArgbColor(0xFFFBBF24.toInt()))
                    machineSlot(ArgbColor(0xFF22D3EE.toInt()))
                    machineSlot(ArgbColor(0xFFA78BFA.toInt()))
                }
                Button(
                    "Toggle power",
                    width = 100,
                    modifier = Modifier.Empty.onPress {},
                )
            }
        }
    }

private fun UiScope.machineSlot(color: ArgbColor) {
    Slot {
        Spacer(modifier = Modifier.Empty.size(16, 16).background(color))
    }
}
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
import dev.s7a.strata.dsl.Box
import dev.s7a.strata.dsl.BoxScope
import dev.s7a.strata.dsl.Column
import dev.s7a.strata.dsl.UiScope
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.minecraft.Button
import dev.s7a.strata.runtime.minecraft.Image
import dev.s7a.strata.runtime.minecraft.MinecraftImageScale
import dev.s7a.strata.runtime.minecraft.MinecraftScreenDefinition
import dev.s7a.strata.runtime.minecraft.MinecraftTextStyle
import dev.s7a.strata.runtime.minecraft.Text
import dev.s7a.strata.runtime.minecraft.createMinecraftScreenDefinition
import dev.s7a.strata.runtime.minecraft.imageBackground
import dev.s7a.strata.runtime.minecraft.menuBackground

/**
 * Builds one advancement-inspired Mod screen from active Minecraft assets and a downstream component.
 *
 * The standard runtime remains limited to reusable primitives; [ExampleProgressGraph] is application-owned composition that may encode this Mod's progression domain.
 *
 * @param window active `textures/gui/advancements/window.png` pixels.
 * @param background active stone advancement-background tile.
 * @param obtained active obtained task-frame sprite.
 * @param unobtained active unobtained task-frame sprite.
 * @return one-shot definition for the verified Fabric and headless screen.
 */
internal fun createProgressScreenDefinition(
    window: DrawImage,
    background: DrawImage,
    obtained: DrawImage,
    unobtained: DrawImage,
): MinecraftScreenDefinition =
    createMinecraftScreenDefinition("Power milestones") {
        Box(
            modifier =
                Modifier.Empty
                    .size(320, 180)
                    .background(ArgbColor(0xFF000000.toInt()))
                    .menuBackground(),
        ) {
            Box(
                modifier = Modifier.Empty.size(320, 151),
                contentAlignment = Alignment.BottomCenter,
            ) {
                ExampleProgressGraph(background, obtained, unobtained)
            }
            Box(
                modifier = Modifier.Empty.size(320, 160),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Image(window, source = IntRect(0, 0, 252, 140))
            }
            Box(
                modifier = Modifier.Empty.size(236, 35).align(Alignment.TopCenter),
                contentAlignment = Alignment.BottomStart,
            ) {
                Text("Power milestones", style = MinecraftTextStyle.ContainerLabel)
            }
            Box(
                modifier = Modifier.Empty.size(320, 174),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Button("Done", width = 200, modifier = Modifier.Empty.onPress {})
            }
        }
    }

/**
 * Emits one application-owned progression graph by composing only public Strata primitives.
 *
 * This downstream component is deliberately not part of the standard runtime because its node meanings and progression domain belong to the application.
 * It retains no callback or scope after synchronous emission.
 *
 * @receiver active owner-thread UI scope.
 * @param background immutable background tile.
 * @param obtained immutable obtained frame sprite.
 * @param unobtained immutable unobtained frame sprite.
 * @param modifier active behavior surrounding the fixed 234 by 113 graph.
 * @param key optional stable sibling identity.
 * @throws IllegalStateException when used from another thread or outside its callback lifetime.
 */
internal fun UiScope.ExampleProgressGraph(
    background: DrawImage,
    obtained: DrawImage,
    unobtained: DrawImage,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    Box(
        modifier = modifier.size(234, 113).imageBackground(background, MinecraftImageScale.Tile),
        key = key,
    ) {
        Box(
            modifier = Modifier.Empty.size(134, 44),
            contentAlignment = Alignment.BottomEnd,
        ) {
            Box(modifier = Modifier.Empty.size(96, 2).background(connectionColor)) {}
        }
        Box(
            modifier = Modifier.Empty.size(135, 80),
            contentAlignment = Alignment.BottomEnd,
        ) {
            Box(modifier = Modifier.Empty.size(2, 38).background(connectionColor)) {}
        }
        progressNode(obtained, ArgbColor(0xFF22D3EE.toInt()), 25, 30, "Generator")
        progressNode(obtained, ArgbColor(0xFFFBBF24.toInt()), 121, 30, "Storage")
        progressNode(unobtained, ArgbColor(0xFFA78BFA.toInt()), 121, 68, "Automation")
    }
}

private fun BoxScope.progressNode(
    frame: DrawImage,
    color: ArgbColor,
    x: Int,
    y: Int,
    label: String,
) {
    Box(
        modifier = Modifier.Empty.size(x + 60, y + 36),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Column(
            modifier = Modifier.Empty.size(64, 36),
            spacing = 1,
        ) {
            Box(
                modifier = Modifier.Empty.padding(left = 4).size(26, 26),
                contentAlignment = Alignment.Center,
            ) {
                Image(frame)
                Box(modifier = Modifier.Empty.size(16, 16).background(color)) {}
            }
            Text(label)
        }
    }
}

private val connectionColor = ArgbColor(0xFF7A7A7A.toInt())
```

### Primitive boundary

`ExampleProgressGraph` deliberately stays in downstream example code because milestone names and graph meaning are specific to this Mod. Images, backgrounds, text, buttons, layout, and pointer actions remain reusable primitives.
