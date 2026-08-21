<!-- Generated file. Do not edit. -->

# Column

Column lays out direct children vertically with typed spacing, arrangement, and horizontal alignment.

This image is a 320 by 180 crop from the exact native/Fabric/headless parity frame recorded in [the verification receipt](minecraft-26.2-parity.properties).

![Column headless showcase](images/column.png)

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
 * Builds the Column panel used by the native, Fabric, and headless parity paths.
 *
 * @param minecraft callback-lifetime Minecraft component context.
 */
internal fun RowScope.columnPanel(minecraft: MinecraftUiContext) {
    Column(
        modifier = Modifier.Empty.size(320, 180),
        horizontalAlignment = HorizontalAlignment.Center,
    ) {
        Spacer(modifier = Modifier.Empty.height(20))
        element(minecraft.text(UiText.Literal("Column")))
        Spacer(modifier = Modifier.Empty.height(11))
        element(minecraft.pointerButton(UiText.Literal("Enabled")) {})
        Spacer(modifier = Modifier.Empty.height(4))
        element(minecraft.pointerButton(UiText.Literal("Disabled"), enabled = false) {})
    }
}
```

## Modifiers

The compiled panel fixes Column to 320 by 180, centers children horizontally, and uses Spacer height modifiers for exact native vertical placement.

## Parent scope

Column's content callback runs with ColumnScope; weight and horizontal align modifiers apply only to direct children while that scope is active.

<details><summary>Component tree</summary>

The tree shows layout components; Minecraft text and pointer-button leaves are omitted and remain visible in the compiled source.

```text
`- Column [Size(width=320, height=180), ColumnDefaultAlignment(alignment=Center)]
  |- Spacer [Height(value=20)]
  |- Spacer [Height(value=11)]
  `- Spacer [Height(value=4)]
```

</details>
