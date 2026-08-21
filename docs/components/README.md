<!-- Generated file. Do not edit. -->

# Minecraft component showcase

These deterministic crops come from real Minecraft 26.2 `ConfirmScreen`, `DirectJoinServerScreen`, and native `ObjectSelectionList` screens reconstructed with Strata's `MenuBackground`, `Text`, `TextField`, `Button`, and `Scroll` components.
One loaded Fabric GameTest requires exact ARGB equality among each native screen, the Fabric adapter, and the headless frame before it emits these component images.

[Open the machine-readable parity receipt](minecraft-26.2-parity.properties)

![Overview headless showcase](images/overview.png)

## Overview source

```kotlin
import dev.s7a.strata.dsl.Box
import dev.s7a.strata.dsl.Column
import dev.s7a.strata.dsl.Row
import dev.s7a.strata.dsl.buildUi
import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.fillMaxSize
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.runtime.minecraft.MinecraftUiContext

/**
 * Builds the deterministic Minecraft 26.2 ConfirmScreen content used by the Fabric and headless parity paths.
 *
 * @return callback-lifetime content reproducing the native title, message, and button-row geometry.
 */
internal fun confirmScreenContent(): MinecraftUiContext.() -> Element =
    {
        buildUi {
            Box(
                modifier = Modifier.Empty.size(320, 180),
                contentAlignment = Alignment.Center,
            ) {
                MenuBackground(modifier = Modifier.Empty.fillMaxSize())
                Column(
                    spacing = 8,
                    horizontalAlignment = HorizontalAlignment.Center,
                ) {
                    Text("Confirm action")
                    Text("Continue with this action?")
                    Row(spacing = 4) {
                        Button(
                            "Yes",
                            modifier =
                                Modifier.Empty
                                    .padding(Insets(top = 16))
                                    .onPress {},
                        )
                        Button(
                            "No",
                            modifier =
                                Modifier.Empty
                                    .padding(Insets(top = 16))
                                    .onPress {},
                        )
                    }
                }
            }
        }
    }
```

<details><summary>Overview component tree</summary>

The tree shows Minecraft components in logical draw order; platform-neutral layout scaffolding remains visible in the compiled source.

```text
`- MenuBackground [FillMaxSize]
  |- Text
  |- Text
  |- Button
  `- Button
```

</details>

## Components

- [MenuBackground](menu-background.md)
- [Text](text.md)
- [TextField](text-field.md)
- [Button](button.md)
- [Scroll](scroll.md)
