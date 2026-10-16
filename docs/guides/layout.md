# Layout

Choose a container by the relationship between its children, then use modifiers to control its space.
The [component overview](../reference/components.md) has compiled examples and images.
The [layout reference](../reference/layout.md) defines exact measurement and placement rules; [Dokka](https://gh.s7a.dev/strata/) lists declarations.

## Choose a container

| Container | Relationship |
| --- | --- |
| `Row` | Horizontal siblings on one line. |
| `FlowRow` | Horizontal siblings that wrap at the available width. |
| `Column` | Vertical siblings. |
| `Grid` | Row-major cells in fixed columns with shared track sizes. |
| `Stack` | Intentional overlap or alignment in one rectangle. |
| `Spacer` | Empty space, a separator, connector, or fill. |

Use spacing for repeated gaps and padding for a group inset.
Position a single child through its modifier or parent alignment; it rarely needs an extra Row or Column.
Large padding is a poor substitute for arrangement.

## Container modifiers

A container's `modifier` affects the whole group.
For example, padding on a Row insets the row once; padding on each child changes every child's box.
Container parameters describe layout relationships such as spacing and arrangement.
Direct-child `weight` and `align` are available only in the parent scope that consumes them.

## Constraints and natural size

Rows and columns sum child extents and gaps along their main axis, taking the largest child extent across it.
Stacks take the largest width and height; grids share the largest measured width per column and height per row.
Empty containers report a constrained zero size.
Use `fillMaxWidth`, `fillMaxHeight`, or `fillMaxSize` when natural size is insufficient.

Containers respect incoming constraints but do not clip overflowing paint or input.
Use a scrolling or clipping viewport when overflow must be hidden.
Invalid child sizes and arithmetic overflow fail rather than silently wrapping geometry.

## FlowRow wrapping

FlowRow measures children in declaration order against the full parent maximum width.
A child starts a new row when it and the horizontal gap would exceed the available width; an exact fit stays on the row.
An unbounded width produces one row.

Use `horizontalSpacing` between children and `verticalSpacing` between rows.
`horizontalArrangement` distributes each row within the final width, while `verticalAlignment` aligns children within their row.
`FlowRowScope.align` overrides that alignment for one child.
Use `fillMaxWidth()` to arrange against the available width instead of the widest row's natural width.

Reflow preserves logical child identity and focus.
FlowRow does not shrink fixed-size children, clip excess rows, or support weight or row limits.

## Weight allocation

In a bounded Row or Column, `weight` divides the space remaining after fixed children and gaps.
Weights must be positive and finite.
`fill = true` gives the child an exact slot; `fill = false` lets it use less without redistributing the unused space.
Integer rounding leaves the final weighted child the remainder.
Very small relative weights can receive zero pixels; weights do not guarantee a minimum size.
With an unbounded main axis, children use intrinsic size instead of proportional allocation.

## Arrangement and alignment

Arrangement distributes unused main-axis space after measurement; alignment positions children across that axis.
Rows and FlowRow use vertical alignment, columns use horizontal alignment, and stacks/grids use two-axis alignment.
The innermost matching child `align` overrides the container default.
Overflow starts at the leading edge.

Spacing and weight changes require measurement; arrangement and container alignment changes require placement.
Equal values remain clean.
Custom layouts must follow the detailed [Element SPI](../reference/element-spi.md) and [parent-data contract](../reference/modifier-spi.md#parent-data).

## Center a responsive panel

Use a viewport-filling `Stack(contentAlignment = Alignment.Center)` for placement and an application-owned sizing modifier for a breakpoint policy.
The [compiled API-only example](../../integration/api/src/main/kotlin/dev/s7a/strata/integration/consumer/ApiOnlyResponsivePanel.kt) contains a complete screen factory and the `ResponsivePanel` composition to copy into a local client application.
It uses the public [Modifier SPI](../reference/modifier-spi.md#extension-guide); no component registration or runtime implementation import is required.
The factory draws a solid panel that closes on activation; replace its Spacer with your content through the composition's content slot.

The example's `minimumViewport` defaults to `IntSize(480, 270)` in logical GUI units.
When both available dimensions reach those inclusive thresholds, the panel occupies 50% of the width and 50% of the height, centered on both axes.
If either dimension is below its threshold, both panel dimensions use 100% of the available space.
For example, a 640 by 360 viewport gives a 320 by 180 panel, while a 479 by 270 viewport gives a 479 by 270 panel.
At exactly 480 by 270, the panel returns to half size; odd extents and centering round down.
Here, “50%” means half of each axis, which is one quarter of the area.
Choose your application's threshold through the factory or composition argument.

The outer Stack fills the viewport and loosens its child's minimum constraints, allowing that child to be smaller than the screen.
The sizing modifier chooses exact child constraints during measurement, so changed viewport constraints select the policy again without reconstructing the screen or retaining a window-size snapshot in application state.
Keep this composition at the screen root; inside another container, the breakpoint uses that container's available size.
The example requires bounded dimensions and accepts zero-size viewports.
Its `ClipChildrenNode` clips descendant paint and pointer input to the same measured panel bounds.
Clipping does not relax a fixed-size component's measurement contract: a Text or control whose required size is outside the supplied constraints still fails measurement.
Choose a [text overflow policy](text.md#multiline-display) for bounded text, or give overflowing content an appropriate scrolling viewport; a vertical `ScrollArea` with a linked `Scrollbar` permits vertical overflow while retaining a bounded width.

Minecraft's Fabric screen adapter passes the current scaled Screen width and height to the host, which supplies fixed root constraints in those logical units.
Window pixels and framebuffer pixels are separate dimensions: do not multiply or divide the layout constraints by GUI scale again.
Changing GUI scale can cross a logical breakpoint even when the physical window is unchanged.
`scaleToFit` addresses a different task: [fitting a fixed design surface](modifiers.md#fit-a-design-surface) scales its subtree uniformly, while this panel policy changes the space available for ordinary child layout.

This example targets a local client screen.
Server-owned Paper or Velocity screens additionally need a [declaration projection and matching client extension](../reference/declaration-projection.md) for an application-owned modifier.

## Continue reading

[Modifiers](modifiers.md) explains sizing, padding, and scaling order.
