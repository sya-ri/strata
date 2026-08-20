<!-- Generated file. Do not edit. -->

# Box

Box overlays direct children and positions each with its default alignment or a direct-child override.

![Box headless showcase](images/box.png)

## Compiled example

```kotlin
import dev.s7a.strata.dsl.Box
import dev.s7a.strata.dsl.Spacer
import dev.s7a.strata.dsl.buildUi
import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.fillMaxSize
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.headless.HeadlessFrame
import dev.s7a.strata.runtime.headless.renderHeadless

/**
 * Builds the Box element description used for topology validation and rendering.
 *
 * @return the public Box element tree.
 */
internal fun boxDescription(): Element =
    buildUi {
        Box(
            modifier =
                Modifier.Empty
                    .fillMaxSize()
                    .background(ArgbColor(0xFF111827.toInt()))
                    .padding(4),
            contentAlignment = Alignment.Center,
        ) {
            Spacer(
                modifier =
                    Modifier.Empty
                        .size(28, 16)
                        .background(ArgbColor(0xFF22D3EE.toInt()))
                        .align(Alignment.TopStart),
            )
            Spacer(
                modifier =
                    Modifier.Empty
                        .size(36, 20)
                        .background(ArgbColor(0xFFA78BFA.toInt())),
            )
            Spacer(
                modifier =
                    Modifier.Empty
                        .size(20, 12)
                        .background(ArgbColor(0xFFFBBF24.toInt()))
                        .align(Alignment.BottomEnd),
            )
        }
    }

/**
 * Renders the Box showcase scene into a deterministic headless frame.
 *
 * @return the rendered Box frame.
 */
internal fun box(): HeadlessFrame = renderHeadless(boxDescription(), IntSize(64, 36), scale = 3)
```

## Modifiers

Generic modifiers used: FillMaxSize, Background(color=0xFF111827), Padding(all=4).
Component parameters shown: BoxContentAlignment(alignment=Center).
Direct-child parent data used: BoxAlign(alignment=TopStart), BoxAlign(alignment=BottomEnd).

## Parent scope

Box's content callback runs with BoxScope; align applies only to direct children while that scope is active.

<details><summary>Component tree</summary>

```text
`- Box [FillMaxSize, Background(color=0xFF111827), Padding(all=4), BoxContentAlignment(alignment=Center)]
  |- Spacer [Size(width=28, height=16), Background(color=0xFF22D3EE), BoxAlign(alignment=TopStart)]
  |- Spacer [Size(width=36, height=20), Background(color=0xFFA78BFA)]
  `- Spacer [Size(width=20, height=12), Background(color=0xFFFBBF24), BoxAlign(alignment=BottomEnd)]
```

</details>
