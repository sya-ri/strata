# Architecture

Strata is a declarative UI framework for Minecraft.
Applications describe a tree of UI nodes and application-owned state.
Runtimes translate that description into platform operations while preserving deterministic retained behavior.
This document describes the verified current architecture.
A module joins the build only with working behavior and tests.

## Module boundaries

- `api` contains the complete platform-neutral authoring surface: one-shot `ScreenDefinition`, flat `dev.s7a.strata.component` entry points and configuration types, layouts, resource identifiers, slot and skin locators, active modifiers, and the custom `Element`/`Node` SPI.
  Application source needs no runtime module on its compile classpath.
- `runtime:core` is configured as a publishable, Minecraft-independent retained engine built on `api`.
  It includes reconciliation, layout, input dispatch, painting, unresolved semantics flattening, and internal screen-session orchestration.
  Its opt-in runtime adapter bridge is a narrow integration contract, not an application screen API.
  The bridge exposes synchronous owner-thread lifecycle, frame, and input operations and does not expose coroutines, internal session state, or internal retained-engine implementation types.
  It returns immutable defensive frame snapshots and delegates first-frame input gating, exact primary-failure propagation, and cleanup semantics to the retained session.
- `runtime:headless` is a publishable headless adapter built on `runtime:core`.
  It synchronously renders a fixed positive viewport, rasterizes core draw commands into deterministic ARGB pixels, and encodes metadata-free RGBA8 PNG output without a desktop graphics dependency.
- `runtime:minecraft` is a publishable Minecraft-independent adapter boundary built on `runtime:core`.
  Its opt-in host consumes the API-owned one-shot screen definition and a complete immutable profile, installs the component-runtime bridge only for the dynamic callback, then converts every non-negative logical viewport into exact fixed root constraints.
  Top-level menu, generic-container, and arbitrary-image background modifiers plus highlighted Slot; printable Text; an owner-thread TextField; profile-backed Button, Checkbox, CycleButton, Slider, and Tab controls; independently composed ScrollArea and Scrollbar components; visible-only VirtualList and SelectionList components; immutable whole-image or source-region Image; synchronous or asynchronous PlayerHead; LoadingIndicator; and ProgressBar therefore work without an extra root wrapper or public Minecraft context receiver.
  A version-backed bound Slot uses an opaque ordered platform draw command for the native `ItemStack` phase and delegates mutation to the active server menu; declarative locators cover player-inventory, logical non-player Container, and raw active-menu indices, core preserves ordering and clipping without depending on a Minecraft type, and portable-only rasterization rejects the unsupported payload before producing output.
  Button owns profile-backed appearance and enabled semantics, while platform-neutral active modifiers own raw pointer, keyboard, committed-character, preedit, focus, press, release, move, drag, scroll, and hover actions reusable by future Minecraft components.
  Hosts retain the core tree across transient detach and reattach, gate input until a successful frame, and expose no mapped game, Fabric, resource-manager, renderer, version, coroutine, state, or source-binding type.
  Common code may carry a structural `ResourceId` across client/server configuration or protocol boundaries, while the versioned client loader resolves that identifier through the active resource-manager stack and returns detached immutable pixels.
  Button does not install focus or keyboard activation implicitly; callers compose those policies from shared modifiers, and hover changes only in response to delivered pointer movement.
- `integration:api` compiles its application main source with only `api`, checks that compile classpath mechanically, and uses test-only runtime dependencies to verify both standard and external primitives.
- `runtime:minecraft-fabric-26.2` is the client-only boundary for the current latest Java release.
  It extracts the 26.2 vanilla profile and arbitrary Mod images from the active resource manager, maps the common host to a native Screen, rasterizes through the tested headless path, and forwards typed mouse, keyboard, committed-character, and preedit input.
  Its loaded client GameTest compares native screens using the actual menu background, generic container, Slot highlights, font, EditBox, Button, `ObjectSelectionList`, and `PlayerFaceExtractor` assets and widgets against both the Fabric adapter and the common headless compositor with exact ARGB equality, then compares the custom industrial and progression Mod screens through the same Fabric/headless pixels.
- `runtime:minecraft-fabric-26.1` is the client-only boundary for Minecraft 26.1.
  Both unobfuscated releases compile the complete cross-version shared and unobfuscated-release adapter source roots and the same neutral tests; only current-screen access is implemented per release, and the loaded 26.1 suite records the same fixed-scene ARGB hashes as 26.2.
- `runtime:minecraft-fabric-1.21.11`, `runtime:minecraft-fabric-1.21.10`, `runtime:minecraft-fabric-1.21.9`, `runtime:minecraft-fabric-1.21.8`, `runtime:minecraft-fabric-1.21.7`, `runtime:minecraft-fabric-1.21.6`, `runtime:minecraft-fabric-1.21.5`, and `runtime:minecraft-fabric-1.21.4` are client-only Java 21 boundaries for the older remapped distributions.
  All eight compile against official Mojang mappings and reuse the resource, profile, font, text, lifecycle, native-neutral input, inventory, and current-screen behavior proven by every supported legacy compiler and loaded client.
  The 1.21.11 project consumes the shared `Identifier` aliases, while the 1.21.10 through 1.21.4 projects own their mapped `ResourceLocation` aliases locally.
- `runtime:minecraft-fabric-1.21-legacy` is a neutral source root rather than a Gradle project or published artifact.
  It owns only behavior proven identical across the supported remapped 1.21 releases, including detached primitive input state and a version-neutral resolved-skin reference; each versioned project still owns metadata, dependencies, ABI, publication, and verification.
- `runtime:minecraft-fabric-1.21.9-legacy` and `runtime:minecraft-fabric-1.21.8-legacy` are complete release-family source roots for incompatible native Screen, key binding, and player-skin APIs.
  The newer root owns record-based callbacks and asset-based skins, while the older root owns primitive callbacks and resource-location skins shared by 1.21.8 through 1.21.4 without reflective dispatch.
- `runtime:minecraft-fabric-1.21.5-legacy` and `runtime:minecraft-fabric-1.21.6-legacy` isolate the mapped `GuiGraphics` boundary without reflective dispatch.
  Minecraft 1.21.4 and 1.21.5 submit retained textures through `RenderType.guiTextured` and raise carried items with a scoped pose-stack translation, while 1.21.6 and later supported remapped releases use `RenderPipelines.GUI_TEXTURED` and `GuiGraphics.nextStratum()`.
  Their frame presenter delegates dynamic-texture construction to a compile-time bridge because 1.21.4 accepts only an unnamed `NativeImage`, while 1.21.5 and later supported remapped releases accept the named supplier form.
- `runtime/minecraft-fabric-shared` is the neutral source root for complete files proven compatible across every supported version; it is not a Gradle project or published artifact.
  Its source directories are linked as whole roots so Gradle, IDEs, and static analyzers agree on file ownership without relying on file-tree filters.
- `runtime/minecraft-fabric-identifier` is the neutral source root for internal mapped-name aliases used by releases whose official mappings expose native resource keys as `Identifier`; it is not a Gradle project or published artifact.
  Releases that still expose `ResourceLocation`, including 1.21.10 and 1.21.9, keep equivalent aliases in their versioned project instead of weakening the shared adapter contract.
- `runtime/minecraft-fabric-unobfuscated` is the neutral source root for files shared only by the unobfuscated releases; it is not a Gradle project or published artifact.
  Each versioned project owns its metadata, dependency graph, version-specific bridges, ABI, publication, and verification task.
- `integration:minecraft-fabric-1.21.11`, `integration:minecraft-fabric-1.21.10`, `integration:minecraft-fabric-1.21.9`, `integration:minecraft-fabric-1.21.8`, `integration:minecraft-fabric-1.21.7`, `integration:minecraft-fabric-1.21.6`, `integration:minecraft-fabric-1.21.5`, and `integration:minecraft-fabric-1.21.4` compile the complete `integration/minecraft-fabric-1.21-legacy` GameTest source root against their exact remapped Java 21 boundaries; none is published.
  Each runs the same loaded-client behavior from development outputs and from the remapped production integration and runtime jars, while the complete 1.21.9-record or 1.21.8-primitive test-input root follows the target callback generation and the 1.21.5 or 1.21.6 world-version root follows the mapped version-name accessor.
- `integration:minecraft-fabric-26.1` compiles and runs the neutral loaded-client scenarios against the 26.1 runtime and integrated server; it is not published.
- `integration:minecraft-fabric-26.2` owns the loaded client vanilla parity scenes, integrated-server player/custom/ender-chest Slot scenarios, resource-pack-aware industrial and advancement-inspired progression screens, compiled Minecraft-component examples, and build-only verification evidence; it is not published.
- `integration:docs` discovers public top-level component extensions mechanically from compiled API classes, extracts dedicated minimal component `ScreenDefinition` and complete-screen sources, verifies the Minecraft parity receipt, full-frame viewports, and PNG hashes, and owns the combined generated component document; it is not published.
Platform-independent code must not depend on a Minecraft runtime.
Minecraft and Fabric dependencies remain confined to the versioned runtime boundary that requires them.

## Component extension policy

Strata standardizes only focused primitives that serve at least two natural independent uses and do not encode one screen or application-domain model.
Every proposal for a standard built-in is reviewed for excessive specialization and for whether an ordinary composition of existing primitives is sufficient.
Minecraft-specific primitives remain eligible when their responsibility is broadly reusable across Minecraft UI; a player-head renderer can serve social lists, player lists, profiles, teams, and ownership displays, while a social-entry row or an advancement graph remains application composition.

This standard-library gate does not constrain downstream code.
An application or Mod may define a purpose-specific component such as an energy gauge or social entry as an ordinary `UiScope` composition function, or implement new retained behavior with a custom immutable `Element`, stable singleton `ElementType`, and capability-bearing `Node`.
`UiScope.element` inserts that description without registration, and the retained core never dispatches on the concrete component class.
External component implementations receive the same reconciliation, key, modifier, cache, input, semantics, lifecycle, and failure contracts as Strata's built-ins.
The complete external implementation contract is documented in [Element SPI](element-spi.md).

The process and compatibility requirements for a new version adapter are defined in [Supporting a new Minecraft version](minecraft-versions.md).

The public API currently defines `ScreenDefinition`; Row, Column, Stack, Grid, Spacer, Text, TextField, Button, Checkbox, CycleButton, Slider, Tab, ScrollArea, Scrollbar, VirtualList, SelectionList, Image, Slot, PlayerHead, LoadingIndicator, and ProgressBar; element and modifier descriptions; typed actions and external state; typed layout parent data; retained node capabilities; frame time and overlay painting; lifecycle ownership; geometry; pointer and focused input; drawing; semantics; unresolved text; resources and bindings; and revisioned external state sources.
`ScreenDefinition` retains its callback without evaluating it, then transfers that callback exactly once to a runtime that implicitly builds its single component root under the installed profile.
Each `UiScope` is confined to the invoking thread and callback lifetime, and callback failures take precedence over root-cardinality validation.
The privileged `evaluateComponentTree` bridge exists only for runtime adapters and structural SPI tests that already own raw elements; application screens do not need or expose a standalone root builder.
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
The loaded 26.2 client GameTest requires exact ARGB equality among deterministic native screens, their Fabric-adapter reconstructions, and common headless frames at each locked 320 by 180, 320 by 240, or 64 by 64 acceptance viewport.
It covers `ConfirmScreen`, `DirectJoinServerScreen`, `ContainerScreen`, an actual `ObjectSelectionList`, `SocialInteractionsScreen`, native `PlayerFaceExtractor`, an integrated-server synchronized inventory, and custom industrial and progression Mod screens, then keeps those full-screen acceptance frames separate from the component showcase evidence.
For every standard component, the loaded GameTest also evaluates a dedicated minimal `ScreenDefinition` independently through the Fabric and headless runtimes, requires exact full-frame ARGB equality, and writes that entire frame with its viewport and hashes below the build directory.
The Social comparison composes the public primitives with the active social panel and search assets, a compact profile-colored TextField, and PlayerHead, then requires the complete 320 by 240 native, Fabric, and headless images to match exactly.
The progression example keeps its purpose-specific graph downstream while composing active advancement textures through the general source-region Image API; the industrial screen similarly uses a replaceable Mod resource rather than a domain-specific standard component.
The showcase generator accepts only receipt-matched dedicated component frames whose recorded viewports equal their catalog metadata, complete screens, and the corresponding compiled GameTest scenario sources before staging Markdown and PNG output.
The checker reruns the parity prerequisite and compares that staging output with the combined `docs/components.md` document, the `docs/components` asset tree, and the anchored root README region without writing source files.

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
