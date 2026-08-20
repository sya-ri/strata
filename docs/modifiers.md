# Modifiers

Modifiers are immutable descriptions attached to a component `Element`.
They are active retained nodes, rather than a settings bag copied into the component node.
The retained component node, logical parent relationship, logical children, and keyed identity remain stable when its modifier chain changes.
Only the effective pipeline ancestry changes.

## Composition and identity

```kotlin
val modifier = Modifier.Empty
    .then(firstModifier)
    .then(secondModifier)
```

The first description is the outermost retained node.
The last description is nearest the component.
Modifier positions reconcile by their referential `ModifierNodeType` token.
An equal position and token updates and reuses its modifier node.
Adding, removing, or reordering descriptions may replace modifier nodes, but never recreates the component node or changes its logical parent and subtree.
Component keys therefore continue to identify the component across modifier edits.

Modifier lifecycle is independent from component lifecycle.
Initial attachment visits modifiers outermost to innermost and then the component.
Live reconciliation cleans removed modifier nodes from innermost to outermost.
Only after the complete incoming tree reconciles successfully does attachment visit new modifier nodes from outermost to innermost.
Removed and replacement lifecycle resources therefore do not overlap.
Final cleanup visits the component subtree and component first, then modifiers from innermost to outermost.

## Built-in modifiers

Every built-in function appends one active node inside the existing modifier chain.
Earlier descriptions therefore remain outermost and affect the constraints or bounds seen by later descriptions.

`size`, `width`, and `height` request exact non-negative extents that are clamped into the parent constraints.
`sizeIn`, `widthIn`, and `heightIn` apply inclusive non-negative ranges independently to each selected axis.
A requested range that does not overlap its parent range is pinned to the nearest parent boundary.
`fillMaxSize`, `fillMaxWidth`, and `fillMaxHeight` fill selected bounded axes while preserving selected unbounded axes.

`padding` reduces child constraints by checked `Insets`, preserves an unbounded maximum, and floors each smaller finite endpoint at zero.
It places the measured child at the left and top inset and reports the child extent plus both inset totals, constrained by the parent.
An extent addition that cannot be represented as an `Int` fails instead of wrapping or saturating.

`background` emits one fill over its complete local bounds before content is painted.
`semantics` emits one separate unresolved entry before content semantics and does not merge descendant values.
Changing size or padding invalidates measurement, changing a background invalidates paint, and changing semantics invalidates only semantics.
An equal value does not invalidate a phase.

## Extension guide

An extension defines an immutable `ModifierElement` value and one stable `ModifierNodeType` token.
The token validates descriptions, creates detached `ModifierNode` instances, and updates retained nodes with a `DirtyMask`.
Each creation hook must return a fresh node that has never belonged to a runtime.
The node may implement the phase capability interfaces it owns.
The inherited measure and layout behavior exposes exactly one virtual child, measures it with unchanged constraints, and places it at the origin.
An override may intentionally omit measurement or placement, excluding the component subtree while retaining the modifier's own output.

Modifier validation runs through the complete incoming `Element` tree before component or modifier mutation.
An invalid description is recoverable and leaves the previous tree available for a valid retry.
Creation, update, lifecycle, and pipeline failures poison the tree.
The original failure identity is preserved while cleanup continues in deterministic order.
Cleanup failures are suppressed on the primary failure and every owned node is attempted once.

Paint currently runs outer modifier, inner modifier, and component in parent-before-child order.
Pointer dispatch visits the component after its logical descendants and then bubbles through inner and outer modifiers.
Semantics are emitted in effective parent-before-child order and remain unresolved until an adapter consumes them.
Future modifier-specific capabilities can add typed contracts without changing the component child scope.

The active modifier SPI and built-in modifiers are exercised through a third-party integration TCK.
