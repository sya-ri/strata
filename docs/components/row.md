<!-- Generated file. Do not edit. -->

# Row

Row lays out direct children horizontally with typed spacing, arrangement, and vertical alignment.

![Row headless showcase](images/row.png)

## Compiled example

```kotlin
import dev.s7a.strata.dsl.Row
import dev.s7a.strata.dsl.Spacer
import dev.s7a.strata.dsl.buildUi
import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.fillMaxSize
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.headless.HeadlessFrame
import dev.s7a.strata.runtime.headless.renderHeadless

/**
 * Builds the Row element description used for topology validation and rendering.
 *
 * @return the public Row element tree.
 */
internal fun rowDescription(): Element =
    buildUi {
        Row(
            modifier =
                Modifier.Empty
                    .fillMaxSize()
                    .background(ArgbColor(0xFF111827.toInt()))
                    .padding(4),
            spacing = 2,
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = VerticalAlignment.Center,
        ) {
            Spacer(
                modifier =
                    Modifier.Empty
                        .size(12, 12)
                        .background(ArgbColor(0xFF22D3EE.toInt())),
            )
            Spacer(
                modifier =
                    Modifier.Empty
                        .size(14, 16)
                        .background(ArgbColor(0xFFA78BFA.toInt()))
                        .weight(1f, fill = false),
            )
            Spacer(
                modifier =
                    Modifier.Empty
                        .size(12, 8)
                        .background(ArgbColor(0xFFFBBF24.toInt()))
                        .align(VerticalAlignment.Bottom),
            )
        }
    }

/**
 * Renders the Row showcase scene into a deterministic headless frame.
 *
 * @return the rendered Row frame.
 */
internal fun row(): HeadlessFrame = renderHeadless(rowDescription(), IntSize(72, 28), scale = 3)
```

## Modifiers

Generic modifiers used: FillMaxSize, Background(color=0xFF111827), Padding(all=4).
Component parameters shown: Spacing(value=2), Arrangement(value=SpaceEvenly), RowDefaultAlignment(alignment=Center).
Direct-child parent data used: Weight(weight=1.0, fill=false), RowAlign(alignment=Bottom).

## Parent scope

Row's content callback runs with RowScope; weight and vertical align modifiers apply only to direct children while that scope is active.

<details><summary>Component tree</summary>

```text
`- Row [FillMaxSize, Background(color=0xFF111827), Padding(all=4), Spacing(value=2), Arrangement(value=SpaceEvenly), RowDefaultAlignment(alignment=Center)]
  |- Spacer [Size(width=12, height=12), Background(color=0xFF22D3EE)]
  |- Spacer [Size(width=14, height=16), Background(color=0xFFA78BFA), Weight(weight=1.0, fill=false)]
  `- Spacer [Size(width=12, height=8), Background(color=0xFFFBBF24), RowAlign(alignment=Bottom)]
```

</details>
