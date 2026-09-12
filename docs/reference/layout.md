# Layout contract

This reference defines built-in measurement, wrapping, and integer placement for custom-layout authors.
For container choice and ordinary composition, use the [layout guide](../guides/layout.md).

## Constraints and natural size

Rows and columns measure fixed children with zero minimums and the incoming maximums.
Their natural main extent is the checked sum of measured child extents plus `spacing * (childCount - 1)`.
Their natural cross extent is the maximum measured cross extent.
The reported size is the natural size constrained by the incoming constraints.
Empty containers are valid and report the constrained zero size.

Stacks measure every child with zero minimums and unchanged incoming maximums.
Their natural width and height are the maxima of their measured children, then constrained by the incoming constraints.
Grids assign children to fixed columns in row-major order, use only columns occupied by at least one child, and allow an incomplete final row without synthetic placeholders.
Each grid column takes the greatest measured width assigned to that column, each row takes its greatest measured height, and the natural extent is the checked sum of those tracks and their configured gaps.
Spacers measure `IntSize.Zero` through the incoming constraints.

Whenever a built-in container's measure or layout callback executes, it requests the corresponding operation exactly once for each direct child.
Clean retained passes may reuse cached geometry without invoking those callbacks.
No built-in layout clips paint or input overflow.

## FlowRow wrapping

FlowRow measures direct children once in declaration order with zero minimums and the unchanged incoming maximums.
Every child receives the full parent maximum width, not the space remaining on its current row.
The measured width of the next child and `horizontalSpacing` determine whether it fits on that row; an exact fit stays on the row, while an excess starts the next row.
There is no spacing before the first or after the last child of a row, and wrapping never creates an empty row.
An unbounded maximum width produces one row.

Each row's height is its greatest measured child height.
The natural width is the widest row, and the natural height is the checked sum of row heights and `verticalSpacing` between rows.
The reported size is that natural size constrained by the parent, with empty content reporting the constrained zero size.
Use `fillMaxWidth()` when the container should occupy the available width rather than its natural width.

`horizontalArrangement` places each row's children within the final container width using the same integer rounding as Row.
Rows stack from the top without vertical distribution.
`verticalAlignment` aligns children inside their own row; `FlowRowScope.align(VerticalAlignment)` overrides it for one direct child through dedicated parent data.
Changing the available width does not add logical Row parents or move children between logical parents, so reflow preserves the retained children and their focus.

FlowRow does not shrink a fixed-size child to make it fit, and a child that violates its incoming constraints fails through the existing measurement contract.
It does not clip or truncate rows that extend below the reported height.
Row membership is temporary geometry computed during measurement and placement, not an additional retained cache.
FlowRow does not expose weight, per-row item limits, or maximum-row and overflow controls.

## Weight allocation

When the main-axis maximum is bounded and positive finite weights are present, fixed children are measured first.
The available weighted extent is the checked maximum of zero and the parent maximum minus fixed extents and all fixed gaps.
Each weighted child receives its proportional share of that extent.
Every weighted child except the last receives the floor of its exact share, and the last weighted child receives the checked integer residue.
`fill = true` measures a child at its exact slot, while `fill = false` supplies zero minimum and the slot maximum.
Unused space from a non-filling child is not redistributed.

When the main-axis maximum is unbounded, weighted children are measured intrinsically with loosened minimums and an unbounded main-axis maximum.
Proportional allocation and filling are ignored in that case.

## Arrangement and alignment

Arrangement is applied after measurement to non-negative slack remaining after actual child extents and fixed spacing.
Centering floors toward the start or top edge.
Distributed arrangements compute each absolute offset from the full slack using checked integer intermediates.
For child index `i` and child count `n`, `SpaceBetween` uses `floor(slack * i / (n - 1))` when `1 < n`, `SpaceAround` uses `floor(slack * (2 * i + 1) / (2 * n))`, and `SpaceEvenly` uses `floor(slack * (i + 1) / (n + 1))`.
Overflow uses start arrangement and extends toward the end or bottom edge.
Every sum, product, cursor, and offset conversion is checked and fails instead of wrapping or saturating.

Rows and individual FlowRow rows use vertical cross-axis alignment, and columns use horizontal cross-axis alignment.
Stacks use typed two-axis alignment within the overlay extent.
Grids use typed two-axis alignment within each measured cell.
A direct child's innermost matching parent-data modifier overrides its container default.

Spacing changes invalidate measurement.
Both FlowRow spacing values follow this rule.
Arrangement and container-default alignment changes invalidate layout.
Weight and child-alignment parent-data changes conservatively invalidate measurement.
Grid column-count or track-spacing changes invalidate measurement, while default or per-child cell alignment changes invalidate layout.
Equal property updates remain clean.

Custom layouts also follow the [Element SPI](element-spi.md) and [parent-data contract](modifier-spi.md#parent-data).
