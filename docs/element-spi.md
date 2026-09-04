# Element SPI

The public `api` module is enough to implement a Strata primitive description and node.
The retained engine lives in `runtime:core`.
A primitive contributes an immutable element description and a retained node, and does not register a component class or enter a central dispatcher.

## Two extension levels

Prefer a composition component when existing primitives already provide the required measurement, painting, input, and semantics.
Define a normal Kotlin function or `UiScope` extension, validate its typed domain arguments, and emit exactly one composed root.
This is the appropriate form for application-specific components such as an industrial Mod's `EnergyGauge` or one server's `SocialEntry`.
Purpose-specific downstream components are intentionally allowed and are not subject to Strata's standard-built-in generality review.

Implement a retained primitive when the component needs behavior that existing primitives cannot express.
Create an immutable `Element`, retain one singleton `ElementType` for that logical kind, and create a `Node` implementing only the measure, layout, paint, input, semantics, and lifecycle capabilities it owns.
Emit the description with `UiScope.element`; no registry, annotation, generated adapter, or core dispatcher change is required.
External primitive implementations receive the same retention and cleanup rules as built-ins and may be used inside a Minecraft screen definition.

The `integration:api` module compiles both forms outside the implementation packages.
Its purpose-specific `EnergyGauge` composes public layout and drawing modifiers, while `ExternalElement` and `ExternalNode` exercise custom retained measurement, painting, pointer input, semantics, invalidation, reconciliation, and lifecycle through the published SPI.

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

A session's transient detach retains its tree and does not call `LifecycleNode.detach`.
Implement `SessionAttachmentNode` when a retained node must suspend observations or native bindings while that session is detached.
New nodes acquire initial resources in `LifecycleNode.attach`, including entries inserted by ordinary reconciliation.
The session invokes `sessionAttached` on initial session attachment or reattachment, not after every reconciliation.
This callback resumes suspended resources and leaves an already active binding unchanged.
`sessionDetached` clears owned binding references before cleanup, and reattachment creates a new binding without replacing the node.
Externally supplied sources remain application-owned.
See [UI sessions](ui-sessions.md) for ordering, failure, and retained-tree behavior.

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

`FrameCutoffNode` supports external observations without performing retained work from source callbacks.
Any-thread callbacks enqueue only their newest pending revision.
The session captures all participating nodes and declared bindings before committing any captured observation or rebuilding content, so a callback arriving during commit belongs to the next frame.
Both timed and untimed frames perform this cutoff; it is independent of `FrameTimeNode` animation callbacks.
Commit may invalidate only the derived phases affected by the accepted immutable observation.
Canvas uses this capability for CPU frames and keeps its explicit destination size when the source image's dimensions change.

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

`ChildTransformNode.childTransform(index)` may supply a finite positive uniform `ChildTransform` for each placed direct child.
The runtime scales child-local coordinates, then adds the transform offset and child's ordinary integer placement, so the mapping is `placement + offset + childLocal * scale`.
`ChildTransform.Identity` retains ordinary placement behavior, and nested child transforms compose through the effective descendant subtree without transforming the providing node's own local paint.
When continuous transformed geometry crosses an `IntRect` boundary, each nonempty rectangle is projected outward by flooring its left and top edges and ceiling its right and bottom edges.
Accumulated node bounds, child clips, semantics and focus geometry, and root-overlay anchors use that enclosing projection rather than independently rounding an origin and extent.
Portable paint destinations retain fractional geometry where their draw-command contract supports it.

Both scopes expose typed parent data from a direct child's active modifier chain.
Define a stable `ParentDataKey<D>` and implement `ParentDataModifierNode<D>` on the providing modifier node.
`childParentData(index, key)` scans only the requested direct child's consecutive modifiers, selects the innermost provider with the same key instance, and stops before the component node.
The lookup does not measure or place the child.
The selected provider runs on the tree owner thread inside the current callback lifetime and must return an immutable value of the key's runtime type.
Changing the key or value requires measure invalidation.
See [Modifiers](modifiers.md#parent-data) for ordering and failure behavior.

## Paint, input, and semantics

`PaintNode.paint` emits a complete local display list through `PaintScope`.
A clean paint pass reuses that immutable list and maps it through the current accumulated tree transform.
`OverlayPaintNode.paintOverlay` emits a separately cached local display list after all effective descendants.
`ClipChildrenNode` inserts balanced outward-projected tree-coordinate clip commands around effective descendant drawing without clipping the node's own regular or overlay commands.
The runtime returns `DrawCommand` values in regular-paint, clipped-descendant, and overlay-paint order.
Custom backends with exhaustive `DrawCommand` visitors need an explicit `SampledImage` sampling implementation or an unsupported-command preflight.
Its fractional geometry, final-density sampling, tint multiplication, and alpha cutoff are distinct from the unchanged integer `BlitImage` contract.
A backend compiled against a smaller variant set can fail on this command; ordinary Text calls can emit it through the resource-font profile.
See [Source compatibility](text.md#source-compatibility) for the corresponding `UiText.WithFont` visitor contract.
Portable primitive nodes emit only platform-neutral fill and image commands.
An opt-in version adapter may instead pass an immutable opaque `PlatformDrawCommand` through `PaintScope.drawPlatform`; core maps its declared bounds and preserves its opaque payload, clip, and draw order for execution by the matching adapter.
Current frame painting accepts that command only when its accumulated transform is an exact integer translation.
A non-unit scale or fractional translation throws `UnsupportedOperationException` during frame paint before any adapter output, because core cannot generically transform the opaque payload or safely produce a partial frame.
`RootOverlayPaintNode` receives the node's outward-projected root-coordinate `anchorBounds`, but every command it emits is already in root coordinates and is not scaled or translated again by the node's accumulated child transform.
Nodes without `ClipChildrenNode` preserve valid local and descendant paint overflow.
Use `UiText` in semantics without resolving it.
`SemanticsNode` emits a complete unresolved payload through `SemanticsScope`.
The runtime-owned `SemanticsEntry` values combine cached local payloads with current accumulated bounds in parent-before-child and local emission order.
A clean semantics pass reuses each retained node's immutable local payload snapshot and combines it with current accumulated bounds.
A dirty bit is cleared before each callback, so node-local invalidation during the callback remains pending for the next pass.

Pointer dispatch happens after layout.
The tree tests exact transformed half-open node bounds and visits reverse paint order, so the deepest and latest-painted node receives the event first.
A delivered pointer position is inverse-mapped through the node's latest accumulated transform and floored on each axis to produce its local logical `IntOffset`.
A delivered drag keeps its tree-coordinate position but inverse-scales its displacement into the receiving node's local logical units; scroll displacement remains in adapter-normalized wheel units.
A child can receive an event outside its parent's bounds unless that parent implements `ClipChildrenNode`.
The marker skips the clipped effective descendant subtree for pointer hit testing and hover while retaining ordinary hit testing for the clipping node itself.
An ignored result continues dispatch and a consumed result stops it.
Positive scroll `deltaX` requests motion toward increasing logical x, and positive `deltaY` requests motion toward increasing logical y.
Adapters normalize native signs and units into this finite logical displacement.
`PointerCaptureNode` optionally extends ordinary pointer input without changing PointerEvent or InputResult.
Consuming a Press acquires the tree's single owner and starting button only when capture has no owner, then calls `onPointerCaptureAcquired(button)`; another handler or button cannot take an existing capture, so gesture state begins only in that confirmation callback.
Subsequent Move and matching Drag/Release events go exclusively to that owner, including outside its bounds and ancestor clips.
Coordinates use the latest committed local logical layout without clamping, and an ignored captured callback does not fall through to another control.
Hover, other buttons, and scroll still follow actual hit testing.
Matching Release clears capture before the callback; removal, replacement, unplacement, session detach, input reset, close, or failure clears it before one cancellation callback and before disposal.
If acquisition confirmation fails, terminal tree cleanup follows the same cancellation-before-disposal order exactly once.
Cleanup continues if cancellation throws, preserving the primary failure and suppressing independent cleanup failures.
Applications and third-party primitives can use the same capability directly or compose `onCapturedPointerEvent`; see [Modifiers](modifiers.md#built-in-modifiers).

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
Typed parent-data providers remain active modifier capabilities and are read only by the parent scope that consumes them.
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

The compiling external primitive in [`integration:api`](https://github.com/sya-ri/strata/blob/v0.1.0/integration/api/src/test/kotlin/dev/s7a/strata/integration/external/ExternalElement.kt) implements the v0.1.0 public SPI.
It includes a stable typed `ElementType`, a measured and placed child, painting, pointer consumption, unresolved semantics, and lifecycle events.
Its behavior is exercised by the [external integration test](https://github.com/sya-ri/strata/blob/v0.1.0/integration/api/src/test/kotlin/dev/s7a/strata/integration/external/ExternalPrimitiveIntegrationTest.kt).

Use that fixture as the executable example.
Application code emits the external element directly inside `ScreenDefinition { element(external) }`, alongside any standard component composition.
The runtime evaluates that callback synchronously under its installed profile, and the callback scope cannot be used for later or cross-thread emission.
Zero or multiple roots fail before retained-tree creation; a callback failure is propagated unchanged before cardinality validation.
Low-level SPI tests may opt into `evaluateComponentTree` and install its returned element in a `UiTree`, but this privileged bridge is not an application screen builder.
Call `measure` with constraints.
Call `layout`.
Consume `paint`, `dispatchPointer`, or `semantics` output at the platform boundary.
