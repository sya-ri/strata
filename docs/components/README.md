<!-- Generated file. Do not edit. -->

# Headless component showcase

This deterministic showcase is portable headless DrawCommand output, not a Minecraft screenshot or capture.

![Overview headless showcase](images/overview.png)

## Overview source

```kotlin
import dev.s7a.strata.dsl.Box
import dev.s7a.strata.dsl.Column
import dev.s7a.strata.dsl.Row
import dev.s7a.strata.dsl.Spacer
import dev.s7a.strata.dsl.buildUi
import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.HorizontalAlignment
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
 * Builds the overview tree shared by its metadata and renderer.
 *
 * @return the public element tree before rendering.
 */
internal fun overviewDescription(): Element =
    buildUi {
        Column(
            modifier =
                Modifier.Empty
                    .fillMaxSize()
                    .background(ArgbColor(0xFF111827.toInt()))
                    .padding(4),
            spacing = 4,
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = HorizontalAlignment.Center,
        ) {
            Row(
                modifier = Modifier.Empty.size(60, 12),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = VerticalAlignment.Center,
            ) {
                Spacer(
                    modifier =
                        Modifier.Empty
                            .size(10, 8)
                            .background(ArgbColor(0xFF22D3EE.toInt())),
                )
                Spacer(
                    modifier =
                        Modifier.Empty
                            .size(10, 10)
                            .background(ArgbColor(0xFFA78BFA.toInt())),
                )
                Spacer(
                    modifier =
                        Modifier.Empty
                            .size(10, 6)
                            .background(ArgbColor(0xFFFBBF24.toInt())),
                )
            }
            Box(
                modifier =
                    Modifier.Empty
                        .size(44, 16)
                        .background(ArgbColor(0xFF1F2937.toInt())),
                contentAlignment = Alignment.Center,
            ) {
                Spacer(
                    modifier =
                        Modifier.Empty
                            .size(24, 8)
                            .background(ArgbColor(0xFFFB7185.toInt()))
                            .align(Alignment.Center),
                )
            }
        }
    }

/**
 * Renders the overview scene into a deterministic headless frame.
 *
 * @return the rendered overview frame.
 */
internal fun overview(): HeadlessFrame = renderHeadless(overviewDescription(), IntSize(72, 44), scale = 3)
```

<details><summary>Overview component tree</summary>

```text
`- Column [FillMaxSize, Background(color=0xFF111827), Padding(all=4), Spacing(value=4), Arrangement(value=Center), ColumnDefaultAlignment(alignment=Center)]
  |- Row [Size(width=60, height=12), Arrangement(value=SpaceEvenly), RowDefaultAlignment(alignment=Center)]
  | |- Spacer [Size(width=10, height=8), Background(color=0xFF22D3EE)]
  | |- Spacer [Size(width=10, height=10), Background(color=0xFFA78BFA)]
  | `- Spacer [Size(width=10, height=6), Background(color=0xFFFBBF24)]
  `- Box [Size(width=44, height=16), Background(color=0xFF1F2937), BoxContentAlignment(alignment=Center)]
    `- Spacer [Size(width=24, height=8), Background(color=0xFFFB7185), BoxAlign(alignment=Center)]
```

</details>

## Components

- [Row](row.md)
- [Column](column.md)
- [Box](box.md)
- [Spacer](spacer.md)
