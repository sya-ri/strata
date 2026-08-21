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
  Its opt-in runtime adapter bridge is a narrow integration contract, not an application screen API.
  The bridge exposes synchronous owner-thread lifecycle, frame, and input operations and does not expose coroutines, internal session state, or internal retained-engine implementation types.
  It returns immutable defensive frame snapshots and delegates first-frame input gating, exact primary-failure propagation, and cleanup semantics to the retained session.
- `runtime:headless` is a publishable headless adapter built on `runtime:core`.
  It synchronously renders a fixed positive viewport, rasterizes core draw commands into deterministic ARGB pixels, and encodes metadata-free RGBA8 PNG output without a desktop graphics dependency.
- `runtime:minecraft` is a publishable Minecraft-independent adapter boundary built on `runtime:core`.
  Its opt-in host consumes a one-shot screen definition and a complete immutable profile, then converts every non-negative logical viewport into exact fixed root constraints.
  Its callback-scoped context provides menu, printable text, and a fixed profile-backed pointer button with event-driven hover and primary-press callbacks.
  Hosts retain the core tree across transient detach and reattach, gate input until a successful frame, and expose no mapped game, Fabric, resource, renderer, version, coroutine, state, or source-binding type.
  The common button contract does not claim focus, keyboard, sound, or a platform-native widget system; hover changes only in response to delivered pointer movement.
- `integration:api` verifies an external primitive against the public `api`, `runtime:core`, and `runtime:minecraft` boundaries.
- `integration:docs` compiles the typed showcase scenarios against the shipped APIs and owns generated component documentation; it is not published.
Further environment-specific and versioned Minecraft adapters are outside the current build.
Platform-independent code must not depend on a Minecraft runtime.
Minecraft and Fabric dependencies remain confined to future runtime and integration layers that require them.

The process and compatibility requirements for a new version adapter are defined in [Supporting a new Minecraft version](minecraft-versions.md).

The public API currently defines declarative tree building, Row, Column, Box, and Spacer components, element and modifier descriptions, typed layout parent data, retained node capabilities, lifecycle ownership, geometry, input, drawing, semantics, unresolved text, and revisioned external state sources.
`buildUi` invokes its callback synchronously and returns the exact caller-owned `Element` emitted as its single root.
Its scope is confined to the invoking thread and callback lifetime, and callback failures take precedence over root-cardinality validation.
The state-source contract is specified and exercised by concurrency tests described in [External state sources](state-sources.md).
It remains coroutine-free and does not include a platform lifecycle adapter.
The retained core's tested internal session contract is described in [UI sessions](ui-sessions.md).

## Headless rendering

The headless facade validates positive logical width, height, and scale before description validation, node creation, or lifecycle hooks.
It checks physical width, height, and row-major area with checked integer arithmetic and reports arithmetic failure instead of wrapping or allocating an invalid image.
Low-level commands are snapshotted in list order, clipped to the positive logical viewport before exact scale replication, and painted onto transparent black.
Coordinates are top-left origin, x-right, y-down, and half-open.

Painting uses straight ARGB Porter-Duff source-over with Long intermediates.
For source alpha `sa`, destination alpha `da`, and channel values `sc` and `dc`, `alphaN = sa * 255 + da * (255 - sa)`, `oa = floor((alphaN + 127) / 255)`, and when `alphaN != 0` each channel is `floor((sc * sa * 255 + dc * da * (255 - sa) + floor(alphaN / 2)) / alphaN)`.
When `alphaN == 0`, the result is exactly `0x00000000`.
Transparent sources are no-ops, opaque sources replace, and there is no interpolation, gamma conversion, saturation, or implicit clipping beyond the viewport.

Images expose only immutable reads, fresh pixel copies, and deterministic PNG encoding.
PNG output contains exactly one IHDR, one IDAT, and one IEND in that order, uses noninterlaced RGBA8 filter-zero rows, deterministic stored DEFLATE blocks no larger than 65,535 bytes, and checked CRC32 and Adler32 values.
Frames retain no description, tree, or draw-command list; semantics are defensive, logical, unscaled, unclipped, and in core emission order.
The exact built-in layout measurement, weight, arrangement, alignment, and overflow contracts are defined in [Built-in layout components](layout.md).
The headless adapter's fixed-viewport, clipping, source-over, scaling, PNG, and immutable semantics contracts are exercised by its module tests.
The showcase generator renders the compiled scenario descriptions through the headless facade before staging Markdown and PNG output.
The checker compares that staging output with `docs/components` and the anchored root README region without writing source files.

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

The test suite exercises `api`, `runtime:core`, `runtime:headless`, `runtime:minecraft`, and the showcase compiler with ordinary JVM tests.
Integration tests belong at the narrowest module boundary that needs them.
Fabric GameTests are reserved for behavior that genuinely requires Minecraft's loaded game environment.
