<!-- Generated file. Do not edit. -->

# Column

Column lays out direct children vertically with typed spacing, arrangement, and horizontal alignment.

![Column headless showcase](images/column.png)

## Compiled example

```kotlin
import dev.s7a.strata.dsl.Column
import dev.s7a.strata.dsl.Spacer
import dev.s7a.strata.dsl.buildUi
import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.fillMaxSize
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.headless.HeadlessFrame
import dev.s7a.strata.runtime.headless.renderHeadless

/**
 * Builds the Column element description used for topology validation and rendering.
 *
 * @return the public Column element tree.
 */
internal fun columnDescription(): Element =
    buildUi {
        Column(
            modifier =
                Modifier.Empty
                    .fillMaxSize()
                    .background(ArgbColor(0xFF111827.toInt()))
                    .padding(4),
            spacing = 2,
            verticalArrangement = Arrangement.SpaceAround,
            horizontalAlignment = HorizontalAlignment.Center,
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
                        .align(HorizontalAlignment.End),
            )
        }
    }

/**
 * Renders the Column showcase scene into a deterministic headless frame.
 *
 * @return the rendered Column frame.
 */
internal fun column(): HeadlessFrame = renderHeadless(columnDescription(), IntSize(36, 68), scale = 3)
```

## Modifiers

Generic modifiers used: FillMaxSize, Background(color=0xFF111827), Padding(all=4).
Component parameters shown: Spacing(value=2), Arrangement(value=SpaceAround), ColumnDefaultAlignment(alignment=Center).
Direct-child parent data used: Weight(weight=1.0, fill=false), ColumnAlign(alignment=End).

## Parent scope

Column's content callback runs with ColumnScope; weight and horizontal align modifiers apply only to direct children while that scope is active.

<details><summary>Component tree</summary>

```text
`- Column [FillMaxSize, Background(color=0xFF111827), Padding(all=4), Spacing(value=2), Arrangement(value=SpaceAround), ColumnDefaultAlignment(alignment=Center)]
  |- Spacer [Size(width=12, height=12), Background(color=0xFF22D3EE)]
  |- Spacer [Size(width=14, height=16), Background(color=0xFFA78BFA), Weight(weight=1.0, fill=false)]
  `- Spacer [Size(width=12, height=8), Background(color=0xFFFBBF24), ColumnAlign(alignment=End)]
```

</details>
