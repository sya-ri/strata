<!-- Generated file. Do not edit. -->

# Slot

Slot reproduces the native 18 by 18 hit region and the 24 by 24 back-content-front highlight order used by an actual empty chest screen.

This image is a 24 by 24 component crop from the exact native/Fabric/headless parity frame recorded in [the verification receipt](minecraft-26.2-parity.properties).

![Slot headless showcase](images/slot.png)

## Compiled example

```kotlin
import dev.s7a.strata.dsl.Box
import dev.s7a.strata.geometry.Insets
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
        ) {
            Box(
                modifier =
                    Modifier.Empty
                        .padding(left = 72, top = 36)
                        .containerBackground(rows = 3),
            ) {
                Text(
                    "Chest",
                    style = MinecraftTextStyle.ContainerLabel,
                    modifier = Modifier.Empty.padding(Insets(left = 8, top = 6)),
                )
                Text(
                    "Inventory",
                    style = MinecraftTextStyle.ContainerLabel,
                    modifier = Modifier.Empty.padding(Insets(left = 8, top = 74)),
                )
                repeat(3) { row ->
                    repeat(9) { column ->
                        Slot(
                            modifier =
                                Modifier.Empty.padding(
                                    Insets(
                                        left = 7 + column * 18,
                                        top = 17 + row * 18,
                                    ),
                                ),
                        )
                    }
                }
                repeat(3) { row ->
                    repeat(9) { column ->
                        Slot(
                            modifier =
                                Modifier.Empty.padding(
                                    Insets(
                                        left = 7 + column * 18,
                                        top = 84 + row * 18,
                                    ),
                                ),
                        )
                    }
                }
                repeat(9) { column ->
                    Slot(
                        modifier = Modifier.Empty.padding(Insets(left = 7 + column * 18, top = 142)),
                    )
                }
            }
        }
    }
```

## Modifiers

Sizing is native-fixed at 18 by 18. Pointer action modifiers compose around `Slot`, while `highlightable` controls only its built-in back/front hover layers.

## Parent scope

`Slot` is a member extension on the active `UiScope`. Its optional callback emits at most one 16 by 16 content root between the native highlight-back and highlight-front phases.

<details><summary>Component tree</summary>

The tree shows the featured Minecraft component; platform-neutral layout scaffolding remains visible in the compiled source.

```text
`- Slot [SlotHighlightable(value=true), Size(width=18, height=18)]
```

</details>
