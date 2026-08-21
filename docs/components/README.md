<!-- Generated file. Do not edit. -->

# Minecraft component showcase

These deterministic crops come from real Minecraft 26.2 `ConfirmScreen`, `DirectJoinServerScreen`, `ContainerScreen`, and native `ObjectSelectionList` screens reconstructed with Strata's `Text`, `TextField`, `Button`, `Scroll`, and `Slot` components.
The menu and generic-container images are active background modifiers on layout components rather than logical component entries.
One loaded Fabric GameTest requires exact ARGB equality among each native screen, the Fabric adapter, and the headless frame before it emits these component images.

[Open the machine-readable parity receipt](minecraft-26.2-parity.properties)

![Overview headless showcase](images/overview.png)

## Overview source

```kotlin
import dev.s7a.strata.dsl.Box
import dev.s7a.strata.dsl.Column
import dev.s7a.strata.dsl.Row
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.padding
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
                spacing = 8,
                horizontalAlignment = HorizontalAlignment.Center,
            ) {
                Text("Confirm action")
                Text("Continue with this action?")
                Row(
                    modifier = Modifier.Empty.padding(Insets(top = 16)),
                    spacing = 4,
                ) {
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

- [Text](text.md)
- [TextField](text-field.md)
- [Button](button.md)
- [Scroll](scroll.md)
- [Slot](slot.md)
