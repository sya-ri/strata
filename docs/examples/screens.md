<!-- Generated file. Do not edit. -->

# Complete screen examples

These examples combine reusable components into complete screens.
Use them to study layout, state ownership, and the boundary between Strata primitives and application-specific composition.
The [component catalog](../reference/components.md) compares the individual building blocks and links to their API details.

- [Confirmation screen](#confirmation-screen)
- [Social Interactions](#screen-social)
- [Synchronized inventory](#screen-inventory)
- [Industrial controller](#screen-industrial)
- [Power milestones](#screen-progress)

## Confirmation screen

Text, buttons, and centered layouts compose a familiar confirmation dialog.
The screen owns the message and actions; its components remain reusable in other screens.

![Confirmation screen](../components/overview.png)

<details><summary>Compiled screen</summary>

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
import dev.s7a.strata.modifier.onActivate
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
                        modifier = Modifier.Empty.onActivate {},
                    )
                    Button(
                        "No",
                        modifier = Modifier.Empty.onActivate {},
                    )
                }
            }
        }
    }
```

</details>

<details><summary>Component tree</summary>

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

<a id="screen-social"></a>

## Social Interactions

A Social Interactions reconstruction composes `Text`, `TextField`, `ScrollArea`, `Scrollbar`, `PlayerHead`, and ordinary layout primitives without introducing a purpose-specific SocialEntry component.

![Social Interactions screen showcase](../components/screen-social.png)

The player row remains example-owned because its mute/report relationship is Social-screen domain state. `PlayerHead`, scrolling, text input, text, buttons, and pointer actions remain reusable standard primitives.

<details><summary>Compiled screen</summary>

```kotlin
import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Image
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.NineSliceCenterMode
import dev.s7a.strata.component.PlayerHead
import dev.s7a.strata.component.PlayerHeadScale
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
import dev.s7a.strata.modifier.onActivate
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
 * @param playerName active local player name shown by the native screen.
 * @return one-shot screen definition reproducing the native screen geometry and draw order.
 */
internal fun createSocialScreenDefinition(
    panel: ImageSource = socialPanel,
    searchIcon: ImageSource = socialSearchIcon,
    playerSkin: PlayerSkinSource = PlayerSkinSource.Name("Player0"),
    playerName: String = "Player0",
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
                        Text("$playerName - New World - 1 player")
                    }
                    Row(modifier = Modifier.Empty.padding(left = 1, top = 1), spacing = 1) {
                        Tab("All", selected = true, width = 73, modifier = Modifier.Empty.onActivate {})
                        Tab("Hidden", selected = false, width = 73, modifier = Modifier.Empty.onActivate {})
                        Tab("Blocked", selected = false, width = 73, modifier = Modifier.Empty.onActivate {})
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
                        PlayerHead(source = playerSkin, scale = PlayerHeadScale(3), modifier = Modifier.Empty.padding(left = 4))
                        Text(playerName)
                    }
                }
                Button(
                    "Done",
                    width = 200,
                    modifier = Modifier.Empty.align(HorizontalAlignment.Center).onActivate {},
                )
            }
        }
    }
}

private val socialPanel = ImageSource.Resource(ResourceId("minecraft", "textures/gui/sprites/social_interactions/background.png"))
private val socialSearchIcon = ImageSource.Resource(ResourceId("minecraft", "textures/gui/sprites/icon/search.png"))
```

</details>

<details><summary>Image verification</summary>

Documentation generation freshly renders this frame on the CPU from explicit Minecraft assets without starting the game or creating a GPU context. An independent loaded Fabric GameTest requires exact ARGB equality between the native Minecraft screen, the Strata Fabric screen, and the headless frame; its [native parity receipt](../evidence/minecraft-26.2-parity.properties) is separate from the [headless generation receipt](../components/headless-render.properties).

</details>

<a id="screen-inventory"></a>

## Synchronized inventory

A loaded multiplayer container screen binds its lower grid to the real player inventory and can bind the upper grid to chest, ender-chest, furnace, or custom server-menu storage.

![Synchronized inventory screen showcase](../components/screen-inventory.png)

`Slot` and `SlotBinding` are reusable primitives. The chest-shaped grouping and server menu decide which player, container, ender-chest, furnace, or custom inventory indices each slot binds.

<details><summary>Compiled screen</summary>

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

</details>

<details><summary>Image verification</summary>

This image is explicit native evidence from a loaded Fabric client/server GameTest that performs authoritative inventory interaction. Generation verifies its Minecraft version, PNG hash, and current compiled-source hash against the [native inventory receipt](../evidence/minecraft-26.2-inventory.properties); it does not start a server or replace this bound screen with a portable-only substitute.

</details>

<a id="screen-industrial"></a>

## Industrial controller

A resource-pack-aware Mod controller composes a public custom image, Minecraft text, buttons, and layout primitives into an energy-machine interface.

![Industrial controller screen showcase](../components/screen-industrial.png)

The runtime supplies general image, background, text, button, slot, and input primitives. Energy capacity, charge state, machine recipes, and networking remain application-owned state and server protocol.

<details><summary>Compiled screen</summary>

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

</details>

<details><summary>Image verification</summary>

Documentation generation freshly renders this frame on the CPU from explicit Minecraft assets without starting the game or creating a GPU context. The independent loaded Fabric gate requires exact ARGB equality between the Strata Fabric screen and the headless frame using active resources; its [native parity receipt](../evidence/minecraft-26.2-parity.properties) remains separate from the [headless generation receipt](../components/headless-render.properties).

</details>

<a id="screen-progress"></a>

## Power milestones

An advancement-inspired Mod progression screen composes active vanilla advancement assets with an application-owned downstream graph component.

![Power milestones screen showcase](../components/screen-progress.png)

`ExampleProgressGraph` deliberately stays in downstream example code because milestone names and graph meaning are specific to this Mod. Images, backgrounds, text, buttons, layout, and pointer actions remain reusable primitives.

<details><summary>Compiled screen</summary>

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
import dev.s7a.strata.modifier.onActivate
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
                        .onActivate {},
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

</details>

<details><summary>Image verification</summary>

Documentation generation freshly renders this frame on the CPU from explicit Minecraft assets without starting the game or creating a GPU context. The independent loaded Fabric gate requires exact ARGB equality between the Strata Fabric screen and the headless frame using active resources; its [native parity receipt](../evidence/minecraft-26.2-parity.properties) remains separate from the [headless generation receipt](../components/headless-render.properties).

</details>

## Image verification

The confirmation and portable screen examples are freshly rendered by the headless runtime from explicit Minecraft assets.
The [headless render receipt](../components/headless-render.properties) identifies their inputs and output images.
Independent loaded-game comparisons are recorded in the [native parity receipt](../evidence/minecraft-26.2-parity.properties).
The synchronized inventory example uses a native capture because its slots require a running client and server; its section records that distinct evidence.
