# Built-in layout components

The platform-neutral API provides `UiScope.Row`, `UiScope.Column`, `UiScope.Box`, and `UiScope.Spacer`.
Each component emits one immutable description into its enclosing callback-lifetime scope.
Rows and columns share one retained linear `ElementType` and one axis-polymorphic node implementation.
A same-key row-to-column or column-to-row update preserves the retained node and reconciles its logical descendants under the changed orientation.
Container callbacks may emit zero or more direct children.
The row, column, and box scopes are owner-thread capabilities and are closed immediately after their callbacks return.

## Container modifiers

`Row`, `Column`, and `Box` accept an ordered `modifier` chain for the container itself.
The same active behaviors available to other components can therefore size the container, add padding around its complete child group, paint a background, emit semantics, accept focus, or handle input without adding a component-specific parameter for each behavior.
For example, `Row(modifier = Modifier.Empty.padding(Insets(top = 16)))` offsets the complete row once; applying the same padding separately to each child changes every child's measured box and is not equivalent.
The typed layout parameters remain limited to behavior owned by the container algorithm: spacing, main-axis arrangement, and default cross-axis alignment.
Direct-child `weight` and `align` remain parent-data modifiers available only from the corresponding callback scope.

## Constraints and natural size

Rows and columns measure fixed children with zero minimums and the incoming maximums.
Their natural main extent is the checked sum of measured child extents plus `spacing * (childCount - 1)`.
Their natural cross extent is the maximum measured cross extent.
The reported size is the natural size constrained by the incoming constraints.
Empty containers are valid and report the constrained zero size.

Boxes measure every child with zero minimums and unchanged incoming maximums.
Their natural width and height are the maxima of their measured children, then constrained by the incoming constraints.
Spacers measure `IntSize.Zero` through the incoming constraints.

Whenever a built-in container's measure or layout callback executes, it requests the corresponding operation exactly once for each direct child.
Clean retained passes may reuse cached geometry without invoking those callbacks.
No built-in layout clips paint or input overflow.

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

Rows use vertical cross-axis alignment and columns use horizontal cross-axis alignment.
Boxes use typed two-axis alignment.
A direct child's innermost matching parent-data modifier overrides its container default.

Spacing changes invalidate measurement.
Arrangement and container-default alignment changes invalidate layout.
Weight and child-alignment parent-data changes conservatively invalidate measurement.
Equal property updates remain clean.
