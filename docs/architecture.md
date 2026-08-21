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
  Its callback-scoped implicit receiver provides menu background, printable text, and a fixed profile-backed pointer button inside ordinary `buildUi` scopes.
  Button owns profile-backed appearance and enabled semantics, while platform-neutral active modifiers own raw pointer, press, release, move, drag, scroll, and hover actions reusable by future Minecraft components.
  Hosts retain the core tree across transient detach and reattach, gate input until a successful frame, and expose no mapped game, Fabric, resource, renderer, version, coroutine, state, or source-binding type.
  The common button contract does not claim focus, keyboard, sound, or a platform-native widget system; hover changes only in response to delivered pointer movement.
- `integration:api` verifies an external primitive against the public `api`, `runtime:core`, and `runtime:minecraft` boundaries.
- `runtime:minecraft-fabric-26.2` is the client-only boundary for the current latest Java release.
  It extracts the 26.2 vanilla profile from the active resource manager, maps the common host to a native Screen, rasterizes through the tested headless path, and forwards typed mouse input.
  Its loaded client GameTest compares one native Screen using the actual menu background, font, and Button widgets against both the Fabric adapter and the common headless compositor with exact ARGB equality.
- `integration:minecraft-fabric-26.2` owns that loaded client `ConfirmScreen` parity scene, its compiled Minecraft-component examples, and build-only verification evidence; it is not published.
- `integration:docs` discovers the public Minecraft components from `MinecraftUiContext`, extracts those compiled scenario sources, verifies the Minecraft parity receipt and PNG hashes, and owns generated component documentation; it is not published.
Platform-independent code must not depend on a Minecraft runtime.
Minecraft and Fabric dependencies remain confined to the versioned runtime boundary that requires them.

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
Low-level commands are snapshotted in list order, validated for balanced nested child clips, intersected with those clips and the positive logical viewport before exact scale replication, and painted onto transparent black.
Coordinates are top-left origin, x-right, y-down, and half-open.

Painting uses straight ARGB Porter-Duff source-over with Long intermediates.
For source alpha `sa`, destination alpha `da`, and channel values `sc` and `dc`, `alphaN = sa * 255 + da * (255 - sa)`, `oa = floor((alphaN + 127) / 255)`, and when `alphaN != 0` each channel is `floor((sc * sa * 255 + dc * da * (255 - sa) + floor(alphaN / 2)) / alphaN)`.
When `alphaN == 0`, the result is exactly `0x00000000`.
Transparent sources are no-ops, opaque sources replace, and there is no interpolation, gamma conversion, saturation, or clipping beyond the viewport and explicit retained child clips.

Images expose only immutable reads, fresh pixel copies, and deterministic PNG encoding.
PNG output contains exactly one IHDR, one IDAT, and one IEND in that order, uses noninterlaced RGBA8 filter-zero rows, deterministic stored DEFLATE blocks no larger than 65,535 bytes, and checked CRC32 and Adler32 values.
Frames retain no description, tree, or draw-command list; semantics are defensive, logical, unscaled, unclipped, and in core emission order.
The exact built-in layout measurement, weight, arrangement, alignment, and overflow contracts are defined in [Built-in layout components](layout.md).
The headless adapter's fixed-viewport, clipping, source-over, scaling, PNG, and immutable semantics contracts are exercised by its module tests.
The loaded 26.2 client GameTest first requires exact ARGB equality among a deterministic native `ConfirmScreen`, its Fabric-adapter reconstruction, and the common headless frame at 320 by 180, then writes the full screen and typed MenuBackground, Text, and Button crops with their hashes below its build directory.
The showcase generator accepts only those receipt-matched crops and the compiled GameTest scenario sources before staging Markdown and PNG output.
The checker reruns the parity prerequisite and compares that staging output with `docs/components` and the anchored root README region without writing source files.

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
The core preserves local and descendant overflow by default.
A node that implements `ClipChildrenNode` emits a balanced clip around effective descendant painting and gates descendant pointer hit testing to its measured half-open bounds, while its own regular and post-child overlay paint remain unclipped by that marker.
Pointer hit testing uses half-open bounds, visits deepest and latest-painted candidates first, and bubbles ignored events.
Pointer hover is a separate typed node capability evaluated for every placed node before move dispatch, producing distinct enter and exit transitions without changing ordinary consumption.

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
The 26.2 client GameTest is the release and documentation gate for native asset, font, widget, placement, logical draw-order, and final-pixel parity.
