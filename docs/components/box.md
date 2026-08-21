<!-- Generated file. Do not edit. -->

# Box

Box overlays direct children and positions each with its default alignment or a direct-child override.

This image is a 320 by 180 crop from the exact native/Fabric/headless parity frame recorded in [the verification receipt](minecraft-26.2-parity.properties).

![Box headless showcase](images/box.png)

## Compiled example

```kotlin
import dev.s7a.strata.dsl.Box
import dev.s7a.strata.dsl.RowScope
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.size
import dev.s7a.strata.runtime.minecraft.MinecraftUiContext
import dev.s7a.strata.text.UiText

/**
 * Builds the Box panel used by the native, Fabric, and headless parity paths.
 *
 * @param minecraft callback-lifetime Minecraft component context.
 */
internal fun RowScope.boxPanel(minecraft: MinecraftUiContext) {
    Box(
        modifier = Modifier.Empty.size(320, 180),
        contentAlignment = Alignment.Center,
    ) {
        element(minecraft.text(UiText.Literal("Box")))
        element(
            minecraft.pointerButton(
                UiText.Literal("Top start"),
                modifier = Modifier.Empty.align(Alignment.TopStart),
            ) {},
        )
        element(
            minecraft.pointerButton(
                UiText.Literal("Bottom end"),
                modifier = Modifier.Empty.align(Alignment.BottomEnd),
            ) {},
        )
    }
}
```

## Modifiers

The compiled panel fixes Box to 320 by 180 with Center as its default. Its two pointer-button children use BoxScope.align with TopStart and BottomEnd overrides.

## Parent scope

Box's content callback runs with BoxScope; align applies only to direct children while that scope is active.

<details><summary>Component tree</summary>

The tree shows layout components; Minecraft text and pointer-button leaves are omitted and remain visible in the compiled source.

```text
`- Box [Size(width=320, height=180), BoxContentAlignment(alignment=Center)]
```

</details>
