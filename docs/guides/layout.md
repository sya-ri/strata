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
With an unbounded main axis, children use intrinsic size instead of proportional allocation.

## Arrangement and alignment

Arrangement distributes unused main-axis space after measurement; alignment positions children across that axis.
Rows and FlowRow use vertical alignment, columns use horizontal alignment, and stacks/grids use two-axis alignment.
The innermost matching child `align` overrides the container default.
Overflow starts at the leading edge.

Spacing and weight changes require measurement; arrangement and container alignment changes require placement.
Equal values remain clean.
Custom layouts must follow the detailed [Element SPI](../reference/element-spi.md) and [parent-data contract](../reference/modifier-spi.md#parent-data).

## Continue reading

[Modifiers](modifiers.md) explains sizing, padding, and scaling order.
