<!-- Generated file. Do not edit. -->

# Spacer

Spacer has no intrinsic size or paint; size and background modifiers make this example visible.

![Spacer headless showcase](images/spacer.png)

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
 * Builds the Spacer element description used for topology validation and rendering.
 *
 * @return the public Box and Spacer element tree.
 */
internal fun spacerDescription(): Element =
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
                        .size(36, 12)
                        .background(ArgbColor(0xFFFB7185.toInt()))
                        .align(Alignment.Center),
            )
        }
    }

/**
 * Renders the Spacer showcase scene into a deterministic headless frame.
 *
 * @return the rendered Spacer frame.
 */
internal fun spacer(): HeadlessFrame = renderHeadless(spacerDescription(), IntSize(64, 28), scale = 3)
```

## Modifiers

Generic modifiers used: Size(width=36, height=12), Background(color=0xFFFB7185).
Component parameters shown: none.
Direct-child parent data used: BoxAlign(alignment=Center).

## Parent scope

Spacer has no content callback. In this example, its direct Box parent provides BoxScope.align while the parent callback is active.

<details><summary>Component tree</summary>

```text
`- Box [FillMaxSize, Background(color=0xFF111827), Padding(all=4), BoxContentAlignment(alignment=Center)]
  `- Spacer [Size(width=36, height=12), Background(color=0xFFFB7185), BoxAlign(alignment=Center)]
```

</details>
