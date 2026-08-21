<!-- Generated file. Do not edit. -->

# TextField

TextField reproduces the 200 by 20 Minecraft EditBox sprites, text origin, glyph colors, owner-thread value state, focus, and bounded editing behavior.

This image is a 200 by 20 component crop from the exact native/Fabric/headless parity frame recorded in [the verification receipt](minecraft-26.2-parity.properties).

![TextField headless showcase](images/text-field.png)

## Compiled example

```kotlin
import dev.s7a.strata.dsl.Box
import dev.s7a.strata.dsl.buildUi
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.fillMaxSize
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.runtime.minecraft.MinecraftScreenDefinition
import dev.s7a.strata.runtime.minecraft.MinecraftTextStyle
import dev.s7a.strata.runtime.minecraft.createMinecraftScreenDefinition
import dev.s7a.strata.runtime.minecraft.createMinecraftTextFieldState

/**
 * Builds the Minecraft 26.2 Direct Connection screen used by native, Fabric, and headless parity paths.
 *
 * @return one-shot screen definition with the actual EditBox and 200-pixel Button geometry.
 */
internal fun createDirectJoinScreenDefinition(): MinecraftScreenDefinition {
    val address = createMinecraftTextFieldState("play.example.net", maxLength = 128)
    return createMinecraftScreenDefinition("Direct Connection") {
        buildUi {
            Box(modifier = Modifier.Empty.size(320, 240)) {
                MenuBackground(modifier = Modifier.Empty.fillMaxSize())
                Button(
                    "Join Server",
                    width = 200,
                    modifier = Modifier.Empty.padding(Insets(left = 60, top = 168)).onPress {},
                )
                Button(
                    "Cancel",
                    width = 200,
                    modifier = Modifier.Empty.padding(Insets(left = 60, top = 192)).onPress {},
                )
                Box(
                    modifier = Modifier.Empty.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Text("Direct Connection", modifier = Modifier.Empty.padding(Insets(top = 20)))
                }
                Text(
                    "Server Address",
                    style = MinecraftTextStyle.Inactive,
                    modifier = Modifier.Empty.padding(Insets(left = 61, top = 100)),
                )
                TextField(
                    address,
                    modifier = Modifier.Empty.padding(Insets(left = 60, top = 116)),
                )
            }
        }
    }
}
```

## Modifiers

Pointer, keyboard, committed-character, preedit, and focus modifiers run as active retained behavior around `TextField`; a consuming focused modifier overrides built-in editing.

## Parent scope

`TextField` is a member extension on the active `UiScope`. The implicit runtime context supplies assets, while caller-owned `MinecraftTextFieldState` owns the editable value.

<details><summary>Component tree</summary>

The tree shows the featured Minecraft component; platform-neutral layout scaffolding remains visible in the compiled source.

```text
`- TextField [Size(width=200, height=20)]
```

</details>
