<!-- Generated file. Do not edit. -->

# Headless component showcase

These deterministic headless crops use the active Minecraft 26.2 menu texture, button sprites, ASCII font, component geometry, and logical draw order.
One loaded Fabric GameTest requires exact ARGB equality between the native screen, the Fabric adapter, and the headless frame before producing these files.

[Open the machine-readable parity receipt](minecraft-26.2-parity.properties)

![Overview headless showcase](images/overview.png)

## Overview source

```kotlin
import dev.s7a.strata.dsl.Column
import dev.s7a.strata.dsl.RowScope
import dev.s7a.strata.dsl.Spacer
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.height
import dev.s7a.strata.modifier.size
import dev.s7a.strata.runtime.minecraft.MinecraftUiContext

/**
 * Builds the overview panel used by the native, Fabric, and headless parity paths.
 *
 * @param minecraft callback-lifetime Minecraft component context.
 */
internal fun RowScope.overviewPanel(minecraft: MinecraftUiContext) {
    Column(
        modifier = Modifier.Empty.size(320, 180),
        horizontalAlignment = HorizontalAlignment.Center,
    ) {
        Spacer(modifier = Modifier.Empty.height(20))
        with(minecraft) { Text("Overview") }
        Spacer(modifier = Modifier.Empty.height(11))
        with(minecraft) { Button("Continue") }
    }
}
```

<details><summary>Overview component tree</summary>

The tree shows layout components; Minecraft text and pointer-button leaves are omitted and remain visible in the compiled source.

```text
`- Column [Size(width=320, height=180), ColumnDefaultAlignment(alignment=Center)]
  |- Spacer [Height(value=20)]
  `- Spacer [Height(value=11)]
```

</details>

## Components

- [Row](row.md)
- [Column](column.md)
- [Box](box.md)
- [Spacer](spacer.md)
