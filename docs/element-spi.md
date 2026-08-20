# Element SPI

The public `api` module is enough to implement a Strata primitive description and node.
The retained engine lives in `runtime:core`.
A primitive contributes an immutable element description and a retained node, and does not register a component class or enter a central dispatcher.

## Ownership and lifetime

An element is a short-lived immutable description.
Copy every mutable child collection in the constructor and keep the values needed to create or update a node.
The tree claims a fresh node immediately after the `ElementType` creation hook returns, while the subtree is still detached.
A node remains bound to that owner through detach and dispose, and disposal retires it permanently.
A creation hook must never return an active or retired node.
Constructors should only initialize ordinary data.
Acquire external resources in `LifecycleNode.attach`, release the attachment in `detach`, and permanently release owned resources in `dispose`.

Cleanup marks the entire subtree before the first callback, so invalidation from detach or dispose is rejected.
Attachment is parent-first.
Cleanup is descendant-first, visits later siblings before earlier siblings, attempts detach at most once after an attach attempt, and disposes every claimed node even when attachment was never reached.
Cleanup continues after failures and preserves the first failure while suppressing later distinct failures.

## Stable element tokens

Create one `ElementType` instance per logical primitive kind and retain that instance as a singleton.
The token is referential identity: another `ElementType` instance is a different kind even when it names the same element and node classes.
The token owns typed local validation, creation, and previous/current property update hooks; it checks both class tokens at the runtime boundary.

## Description and dirty phases

The element description must validate its own properties through its `ElementType`.
The tree validates every description and rejects duplicate keyed direct siblings before changing retained nodes.
A property update receives the previous and current descriptions and returns a `DirtyMask` containing only affected phases.
Node-local state changes use the protected invalidation method.
Measurement invalidation reaches ancestors, but does not directly dirty descendants.
Structural changes dirty the affected ancestor pipeline.

Measure invalidation expands to local layout, paint, and semantics.
Layout invalidation dirties the node's local layout, paint, and semantics.
Ancestor traversal reaches that node only while it is currently placed.
Layout invalidation does not dirty descendants.
Paint and semantics invalidation affect only their respective local caches.

New nodes begin dirty in every phase.
A clean measurement with equal constraints reuses its size.
A clean paint reuses node-local commands and combines them with the node's current accumulated bounds.
The parent paints before children in declared order.

## Measure and layout

`MeasureNode.measure` must return a non-negative `IntSize` inside the supplied `Constraints`.
A clean equal-constraint pass may reuse the retained size without invoking the callback.
A `MeasureScope` exposes only direct children, and each direct child may be measured once per parent pass.
If a node measures a child it must also implement `LayoutNode`.

Measure, layout, paint, and semantics scopes are callback-lifetime objects.
They may be used only on the owning `UiTree` thread and only until their callback returns, including when the callback throws.
Later or wrong-thread access fails and cannot mutate retained state.

`LayoutNode.layout` receives the node's measured size and the measured size of each direct child.
A clean pass may reuse retained placements without invoking the callback.
Each measured child may be placed once using `placeChild`.
Children that were not measured or were not placed are excluded from layout, paint, input, and semantics for that pass.
Placement offsets and accumulated bounds use checked integer arithmetic.

## Paint, input, and semantics

`PaintNode.paint` emits a complete local display list through `PaintScope`.
A clean paint pass reuses that immutable list and translates it to current accumulated tree coordinates.
The runtime returns `DrawCommand` values in parent-before-child and local emission order.
The core applies no implicit node or parent clipping, so valid local paint overflow is preserved.
Use `UiText` in semantics without resolving it.
`SemanticsNode` emits a complete unresolved payload through `SemanticsScope`.
The runtime-owned `SemanticsEntry` values combine cached local payloads with current accumulated bounds in parent-before-child and local emission order.
A clean semantics pass reuses each retained node's immutable local payload snapshot and combines it with current accumulated bounds.
A dirty bit is cleared before each callback, so node-local invalidation during the callback remains pending for the next pass.

Pointer dispatch happens after layout.
The tree tests half-open node bounds and visits reverse paint order, so the deepest and latest-painted node receives the event first.
A child can receive an event outside its parent's bounds because the core applies no implicit parent clipping.
An ignored result continues dispatch and a consumed result stops it.
Positive scroll `deltaX` requests motion toward increasing logical x, and positive `deltaY` requests motion toward increasing logical y.
Adapters normalize native signs and units into this finite logical displacement.
The initial protocol has no pointer capture or focus reservation.

## Keys and reconciliation

Keys are scoped to one direct-sibling list.
Two keyed direct siblings with equal values are invalid even when their element types differ.
A keyed description matches only a retained child with the same parent, equal key value, and the same `ElementType` token.
It may move to another index under that parent.
Positional descriptions match only the same absolute sibling index and token.
A keyed/positional change, token change, or parent change replaces the node.

A same-parent keyed move and a property-only update reuse the node and do not attach or detach it again.

The root follows the same identity and token rules.
Keyed movement is not a general node move across parents; a node crossing a parent boundary is replaced.

## Modifiers

An element may carry an immutable ordered `Modifier` chain.
Each description creates an active `ModifierNode`, and no modifier property is flattened into the component node.
Modifiers form virtual pipeline ancestry without changing logical component children or keyed component identity.
The default modifier node measures its one virtual child with unchanged constraints and places that child at its origin.
See [Modifiers](modifiers.md) for reconciliation, lifecycle, failure behavior, and the third-party extension contract.

## Exceptions and threads

The complete new description is validated before reconciliation.
A validation, duplicate-key, or local validation-hook failure leaves an active tree so a corrected description can be retried.
An exception escaping creation, update, lifecycle, measurement, layout, paint, input, or semantics after mutation or pipeline work begins poisons the tree, clears retained ownership, attempts cleanup, and rethrows the primary failure without a last-good fallback.
An exception caught inside a callback does not reach `UiTree` and does not change its state.
Operational methods reject poisoned and closed trees.
`close` becomes `Closed` before callbacks, remains closed after cleanup failure, attempts all descendant-first cleanup, and rethrows the first cleanup failure with later distinct failures suppressed.

Every public `UiTree` operation and every node invalidation runs on the owning `UiTree` thread.
A wrong-thread call fails on the calling thread before it mutates the tree.
It poisons the tree only when an active callback propagates that failure to `UiTree`.
Calls re-entering an operation from an element hook, pipeline callback, or lifecycle callback are rejected; the outer operation then follows its normal failure state transition.

## Minimal external primitive

The compiling external primitive in [`integration:api`](../integration/api/src/test/kotlin/dev/s7a/strata/integration/external/ExternalElement.kt) implements the current public SPI.
It includes a stable typed `ElementType`, a measured and placed child, painting, pointer consumption, unresolved semantics, and lifecycle events.
Its behavior is exercised by the [external integration test](../integration/api/src/test/kotlin/dev/s7a/strata/integration/external/ExternalPrimitiveIntegrationTest.kt).

Use that fixture as the executable example.
Pass the external element through `buildUi { element(external) }` to declare the exact single root.
The builder callback runs synchronously, and its scope cannot be used for later or cross-thread emission.
Zero or multiple roots fail without reaching `UiTree`; a callback failure is propagated unchanged before cardinality validation.
Install the returned element in a `UiTree`.
Call `measure` with constraints.
Call `layout`.
Consume `paint`, `dispatchPointer`, or `semantics` output at the platform boundary.
