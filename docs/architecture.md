# Architecture

Strata is a declarative UI framework for Minecraft.
Applications describe a tree of UI nodes and application-owned state.
Runtimes translate that description into platform operations while preserving deterministic retained behavior.
This document describes the verified current architecture.
A module joins the build only with working behavior and tests.

## Module boundaries

- `api` contains the public, platform-neutral contracts and value types.
- `runtime:core` is configured as a publishable, Minecraft-independent retained engine built on `api`.
  It includes reconciliation, layout, input dispatch, painting, unresolved semantics flattening, and internal screen-session orchestration.
  It is not an externally published artifact yet.
- `integration:api` verifies an external primitive against the public `api` and `runtime:core` boundaries.
Future platform adapters may depend on these boundaries once their artifacts are published, but they are outside the current build.
Platform-independent code must not depend on a Minecraft runtime.
Minecraft and Fabric dependencies remain confined to future runtime and integration layers that require them.

The process and compatibility requirements for a new version adapter are defined in [Supporting a new Minecraft version](minecraft-versions.md).

The public API currently defines declarative tree building, element and modifier descriptions, typed layout parent data, retained node capabilities, lifecycle ownership, geometry, input, drawing, semantics, unresolved text, and revisioned external state sources.
`buildUi` invokes its callback synchronously and returns the exact caller-owned `Element` emitted as its single root.
Its scope is confined to the invoking thread and callback lifetime, and callback failures take precedence over root-cardinality validation.
The state-source contract is specified and exercised by concurrency tests described in [External state sources](state-sources.md).
It remains coroutine-free and does not include a platform lifecycle adapter.
The retained core's tested internal session contract is described in [UI sessions](ui-sessions.md).

## Retained operation contract

`UiTree` binds to its creating thread and rejects reentrant operational methods and close calls.
An empty tree measures to `IntSize.Zero`, performs no layout work, and returns empty paint, input, and semantics results.
Validation runs before mutation, so recursive structure checks, duplicate keyed siblings, and element-local validation failures leave the active tree unchanged.
Failures after validation begins reconciliation, lifecycle, or pipeline work poison the tree, clear retained ownership, attempt cleanup, preserve the primary `Throwable` instance, and suppress later distinct cleanup failures.
Lifecycle callback and pipeline callback failures poison through `UiTree` only when they escape the active callback.
`close` records `Closed` before callbacks, continues cleanup after failures, remains closed after a failure, and is a no-op when called again after completion.
Close cleanup failures do not poison the tree because the tree is already `Closed`.

Measure, layout, paint, input, and semantics enforce their phase preconditions.
A clean equal-constraint measurement can reuse its cached size.
Clean layout can reuse placements.
Clean paint and semantics reuse complete local payloads and combine them with current accumulated bounds.
Invalidation inside a callback remains pending because the current dirty bit is cleared before the callback runs.
Capability and scope failures after pipeline work begins poison the tree only when their exceptions escape the active callback.

Dirty phases are deliberately narrow.
Measure invalidation dirties local measure, layout, paint, and semantics and marks every ancestor for measurement.
Measure invalidation does not directly dirty descendants.
Layout invalidation dirties local layout, paint, and semantics.
Ancestor traversal reaches an invalidated node only while that node is currently placed.
Layout invalidation does not dirty descendants.
Paint and semantics invalidation affect only their respective local caches.

Scope objects are owner-thread and callback-lifetime capabilities.
An out-of-range child index throws `IllegalArgumentException`.
A second measurement or placement, placement of an unmeasured child, or access after callback completion throws `IllegalStateException`.
The same failures from another thread are rejected before state mutation.

The core returns non-premultiplied ARGB values with alpha in the high byte followed by red, green, and blue.
Draw commands and semantics entries preserve parent-before-child and local emission order.
The backend must execute draw commands in that order.
The core applies no implicit node or parent clipping; valid local paint overflow is retained, and a placed child can receive input outside its parent's bounds.
Pointer hit testing uses half-open bounds, visits deepest and latest-painted candidates first, and bubbles ignored events.

Modifiers are active retained nodes in the effective pipeline ancestry and do not become settings copied into component nodes.
Component scopes continue to expose logical children, while a modifier scope exposes its one virtual child.
Modifier-chain changes preserve the retained component and its logical subtree.
Removed modifier nodes finish cleanup during reconciliation, and newly created modifier nodes attach only after the complete incoming tree reconciles successfully.
Typed parent data is supplied by active modifier capabilities and queried only through measure or layout scopes.
Lookup uses a referential key, scans the requested direct child's modifier chain, selects the innermost match, and stops before the component node without measuring or placing the child.
The full modifier contract and external implementation guidance are defined in [Modifiers](modifiers.md).

## Testing strategy

The test suite exercises `api` and `runtime:core` with ordinary JVM tests.
Integration tests belong at the narrowest module boundary that needs them.
Fabric GameTests are reserved for behavior that genuinely requires Minecraft's loaded game environment.
