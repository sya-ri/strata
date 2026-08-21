<!-- Generated file. Do not edit. -->

# MenuBackground

MenuBackground paints the selected Minecraft menu texture with the same tiling, clipping, and draw order as the verified native screen.

This image is a 32 by 32 component crop from the exact native/Fabric/headless parity frame recorded in [the verification receipt](minecraft-26.2-parity.properties).

![MenuBackground headless showcase](images/menu-background.png)

## Compiled example

```kotlin
import dev.s7a.strata.dsl.Box
import dev.s7a.strata.dsl.buildUi
import dev.s7a.strata.element.Element
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.fillMaxSize
import dev.s7a.strata.modifier.size
import dev.s7a.strata.runtime.minecraft.MinecraftUiContext

/**
 * Builds a profile-backed Minecraft menu-background example.
 *
 * @return callback-lifetime content using the implicit Minecraft component context.
 */
internal fun menuBackgroundExample(): MinecraftUiContext.() -> Element =
    {
        buildUi {
            Box(modifier = Modifier.Empty.size(32, 32)) {
                MenuBackground(modifier = Modifier.Empty.fillMaxSize())
            }
        }
    }
```

## Modifiers

`Modifier.fillMaxSize()` supplies the finite viewport that `MenuBackground` fills with the active Minecraft menu texture.

## Parent scope

`MenuBackground` is a member extension on the active `UiScope`. The runtime supplies `MinecraftUiContext` implicitly for the screen-content callback; application code never names or retains it.

<details><summary>Component tree</summary>

The tree shows the featured Minecraft component; platform-neutral layout scaffolding remains visible in the compiled source.

```text
`- MenuBackground [FillMaxSize]
```

</details>
