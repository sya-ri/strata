# Modifier SPI

Modifiers are immutable descriptions attached to a component `Element`.
They are active retained nodes, rather than a settings bag copied into the component node.
The retained component node, logical parent relationship, logical children, and keyed identity remain stable when its modifier chain changes.
Only the effective pipeline ancestry changes.

This contract is for authors of custom modifier nodes and layouts.
For built-in modifier use, start with the [modifier guide](../guides/modifiers.md); exact declarations are in the [API reference](https://gh.s7a.dev/strata/).

## Composition and identity

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

## Parent data

Parent data lets an active modifier provide typed layout metadata to the logical parent that consumes it.
A provider implements `ParentDataModifierNode<D>` with one referential `ParentDataKey<D>` and returns an immutable value of that key's runtime type.
Keys with the same value class remain distinct when they are different key instances.
Changing a provider key or value invalidates measurement, while an equal update may leave the phase clean.

`MeasureScope` and `LayoutScope` expose `childParentData(index, key)` for a direct child.
The runtime scans only that child's consecutive modifier chain from outermost to innermost and stops before the component node.
The innermost provider with the exact key instance wins.
The selected provider is read only after the complete chain has been scanned, so a shadowed outer provider is never invoked.
The component node, its logical children, and their modifier chains are outside the lookup.

A lookup does not measure or place the child.
It follows the enclosing callback scope's owner-thread and lifetime restrictions.
An invalid child index fails before reading a provider.
A selected provider's failure escapes unchanged through the current measure or layout operation.
A value outside the key's runtime type fails with `IllegalArgumentException` at the erased runtime boundary.
Both failures follow the tree's pipeline failure rules.
The contract needs no resolved settings object, global map, or component-kind dispatch.

## Extension guide

An extension defines an immutable `ModifierElement` value and one stable `ModifierNodeType` token.
The token validates descriptions, creates detached `ModifierNode` instances, and updates retained nodes with a `DirtyMask`.
Each creation hook must return a fresh node that has never belonged to a runtime.
The node may implement the phase capability interfaces it owns.
The inherited measure and layout behavior exposes exactly one virtual child, measures it with unchanged constraints, and places it at the origin.
An override may intentionally omit measurement or placement, excluding the component subtree while retaining the modifier's own output.
A node that implements `ChildTransformNode` may additionally return one `ChildTransform` for each placed direct child.
The runtime scales child-local coordinates, then adds the transform offset and child's ordinary integer placement, mapping them as `placement + offset + local * scale`.
Its scale must be finite and positive and its `DoubleOffset` must be finite, while `ChildTransform.Identity` preserves ordinary placement behavior.
Nested child transforms compose through the effective subtree without transforming the providing node's own paint.

Modifier validation runs through the complete incoming `Element` tree before component or modifier mutation.
An invalid description is recoverable and leaves the previous tree available for a valid retry.
Creation, update, lifecycle, and pipeline failures poison the tree.
The original failure identity is preserved while cleanup continues in deterministic order.
Cleanup failures are suppressed on the primary failure and every owned node is attempted once.

Paint currently runs outer modifier, inner modifier, and component in parent-before-child order.
Pointer dispatch visits the component after its logical descendants and then bubbles through inner and outer modifiers.
Hover observation independently visits every placed capable node deepest and latest-painted first, so ordinary move consumption does not hide enter or exit state from another overlapping observer.
Keyboard and text input visit only the focused logical component and its effective modifier ancestry.
Semantics are emitted in effective parent-before-child order and remain unresolved until an adapter consumes them.
Future modifier-specific capabilities can add typed contracts without changing the component child scope.
Third-party component and modifier nodes opt into the same captured pointer protocol by implementing `PointerCaptureNode : PointerInputNode`, starting retained gesture state from `onPointerCaptureAcquired(button)`, and clearing it from `onPointerCaptureCancelled(button)`; the runtime does not recognize component kinds or require registration.
Captured input is owned by the retained input pipeline, so session detachment and platform input reset cancel it even though detachment retains the nodes and does not call their lifecycle detach hooks.
Cancellation clears the pipeline reference before user callbacks, and throwing cancellation cannot prevent remaining hover, focus, or lifecycle cleanup; the existing primary-failure and suppression order applies.

The active modifier SPI and built-in modifiers are exercised through a third-party integration TCK.

## Related contracts

[Element SPI](../reference/element-spi.md) defines the shared node and pipeline obligations.
[UI sessions](../development/ui-sessions.md) defines session attachment and input reset, which are distinct from terminal node lifecycle cleanup.
