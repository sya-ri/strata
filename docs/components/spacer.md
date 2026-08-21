<!-- Generated file. Do not edit. -->

# Spacer

Spacer has no intrinsic size or paint; height modifiers make the native vertical gaps visible around Minecraft text and buttons.

This image is a 320 by 180 crop from the exact native/Fabric/headless parity frame recorded in [the verification receipt](minecraft-26.2-parity.properties).

![Spacer headless showcase](images/spacer.png)

## Compiled example

```kotlin
import dev.s7a.strata.dsl.Column
import dev.s7a.strata.dsl.RowScope
import dev.s7a.strata.dsl.Spacer
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.height
import dev.s7a.strata.modifier.size
import dev.s7a.strata.runtime.minecraft.MinecraftUiContext
import dev.s7a.strata.text.UiText

/**
 * Builds the Spacer panel used by the native, Fabric, and headless parity paths.
 *
 * @param minecraft callback-lifetime Minecraft component context.
 */
internal fun RowScope.spacerPanel(minecraft: MinecraftUiContext) {
    Column(
        modifier = Modifier.Empty.size(320, 180),
        horizontalAlignment = HorizontalAlignment.Center,
    ) {
        Spacer(modifier = Modifier.Empty.height(20))
        element(minecraft.text(UiText.Literal("Spacer")))
        Spacer(modifier = Modifier.Empty.height(51))
        element(minecraft.pointerButton(UiText.Literal("Continue")) {})
    }
}
```

## Modifiers

The compiled panel uses Spacer height modifiers to reproduce native vertical gaps. Spacer remains an intrinsic-zero, non-painting leaf.

## Parent scope

Spacer has no content callback or Spacer-specific parent-data API. In this example it is a direct Column child, while its ordinary height modifier is not Column parent data.

<details><summary>Component tree</summary>

The tree shows layout components; Minecraft text and pointer-button leaves are omitted and remain visible in the compiled source.

```text
`- Column [Size(width=320, height=180), ColumnDefaultAlignment(alignment=Center)]
  |- Spacer [Height(value=20)]
  `- Spacer [Height(value=51)]
```

</details>
