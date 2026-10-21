# Changelog

Each version below summarizes the release and links to its detailed changes and upgrade notes.
Keep all Strata artifacts on the same release version.

## Unreleased

The following fixes are relative to 0.3.0.

- Mapped `StateSource` subscriptions no longer deadlock when an observer closes its subscription while another thread is closing it.
  An already admitted callback may still finish after close.
- Minecraft `PlayerHead` and `Slot` release acquired bindings even when initialization or subscription cleanup fails, preserving the original failure when cleanup also throws.
- A remote client close rejected during UI evaluation preserves the session for later owner cleanup.
  Rejected owner withdrawal also preserves extension registrations so a later retry can retire dependent screens.
- Closing a Web host from an input callback completes the current input operation before releasing its subscriptions, DOM children and listeners.
- Web image rendering allocates pixels only within the destination, clip and viewport intersection, so highly zoomed images keep the screen open without exceeding the image pixel budget because of offscreen content.
  Source sampling, image orientation and the visible physical pixel limit are preserved.
- Web text editors reuse PNG frames for images currently displayed by the same host across content updates and resizing.
  Temporary frame canvases are released even when browser image conversion fails.

The Kotlin compiler and Fabric Language Kotlin dependency have been updated together.
When upgrading from 0.3.0, update all Strata artifacts together and install a Fabric Language Kotlin version satisfying the new Fabric runtime's metadata; the [version catalog](gradle/libs.versions.toml) owns the required versions.
No public API or remote-protocol migration is required from 0.3.0.

## 0.3.0 - 2026-10-21

- Fabric Mods can own screens and HUDs on dedicated or integrated servers, with server-owned state and callbacks and native rendering on matching clients.
  The public server API includes capabilities, extension registration and lifecycle events.
- Networking, ticking, connection lifetime and HUD registration use Fabric API.
  Leaving play closes remote UI even when the socket remains connected through configuration; a new play join negotiates a fresh generation.
- Paper, Folia, Velocity and Fabric server packages expose their platform entry point as `Strata`.
  Paper and Velocity execution ownership and operations retain their existing contracts.
- `Modifier` starts an empty chain directly and becomes an interface.
  `Modifier.Empty` remains a deprecated alias, scheduled for removal in 1.0.0.
- `TextStyle` becomes an immutable value with independent foreground, shadow and disabled-editor colors; existing preset expressions remain available.
- Browser definitions support retained TextField/TextArea editors, scrolling and virtualized content, images, fonts, player heads and tiled navigation.
  Compatible updates preserve editing state, and touch swipes and pinches use the shared navigation protocol.

Recompile consumers, replace the old Paper/Velocity imports, and replace enum-specific `TextStyle` operations.
Install Fabric API and Fabric Language Kotlin alongside the exact-version Fabric runtime on participating clients and Fabric servers.
Text remote schemas change, so both endpoints must use matching Strata versions.

[Detailed changes and upgrade notes](docs/releases/v0.3.0.md)

## 0.2.3 - 2026-10-17

- Populated bound Slots show vanilla item tooltips, including item-provided content, advanced-tooltip settings and updates while the pointer stays still.
- Widget profiles read resource-pack sprite scaling metadata, preserving high-contrast button and slider borders.
- Retained traversal, Grid tracks, VirtualList rows and immutable modifier chains avoid repeated work.
  TextArea lookup and text/image processing reduce temporary storage while preserving editing and pixel contracts.
- Exact sampled-image presentation supports reflected images on qualified adapters; unsupported combinations retain ordered CPU composition.
  Native resource retirement waits for the corresponding GPU work to complete.
- `createDrawImage(size, pixelAt)` constructs immutable pixels directly, while the existing array overload retains defensive copying.
  The optional `DiscardedModifier` authoring rule handles conditional modifier expressions more precisely.
- Production render-monitoring hooks and diagnostic entry points are removed.
  The optional performance testkit measures externally and reports unavailable work or upload metrics explicitly.

In the same-host JVM comparison with public 0.2.2, clean-frame allocation falls from 112 to 32 B/op and 1024×1024 PNG encoding allocation from 12.59 to 4.20 MB/op.
The detailed notes retain the measurement scope and latency regressions.
Application screen definitions and remote messages need no migration from 0.2.2; replace `RuntimeUiDiagnosticsOwner`, `UiRenderMonitor` and `startRenderMonitoring()` usage with external measurement.
Custom Minecraft 26.3 Vulkan bridges must follow the updated GPU-lifetime contract in the detailed notes.

[Detailed changes and upgrade notes](docs/releases/v0.2.3.md)

## 0.2.2 - 2026-10-06

- Clean timed frames reuse retained callback capabilities without walking every passive node.
  Structural changes, invalidation and terminal cleanup keep their existing behavior.
- Bounded pixel templates accelerate repeated image patterns, and CPU composition reuses equal sampled rows and avoids repeated coordinate calculations.
  Inputs outside the admitted cases retain the original drawing path.
- Minecraft presenters reuse unchanged frame inputs and shifted layers, omit invisible work and bound raster preparation to visible regions.
  Qualified modern adapters reproduce CPU sampled-image pixels through GPU presentation, with CPU fallback for unsupported combinations.
- Long TextField measurement and cursor positioning avoid repeatedly copying and measuring prefixes while preserving Unicode, IME, selection and font metrics.
- Optional JVM and multiplatform performance-testkit artifacts provide shared collection, validation and comparison contracts for library and application fixtures.
- The optional `strata-detekt-rules` artifact supplies nine type-aware authoring checks, backed by compiled incorrect and corrected examples.

Same-host JVM comparisons with public 0.2.1 measured clean Full HD timed frames at 0.578 → 0.062 µs/op and patterned Full HD CPU composition at 38.264 → 1.191 ms/op.
Other cases include latency regressions; these fixture results do not establish a universal rendering speedup.
Existing screen definitions and remote communication contracts require no migration from 0.2.1, and adopting the testkit is optional.

[Detailed changes and upgrade notes](docs/releases/v0.2.2.md)

## 0.2.1 - 2026-10-01

- Tiled nine-slice images combine repetitions along source axes containing exactly one texel.
  A one-by-one center emits one drawing command rather than one per repeated tile.
- Sources containing multiple texels keep their repeated pattern and partial final tiles.
  The default `Tiled` mode retains its appearance under integer and fractional scaling.
- CPU rasterization writes opaque fills directly by row and avoids redundant blending and color operations for eligible opaque sampled images.
  One-texel images can reuse the fill path without introducing a cache.

In a tiled-panel fixture from a consuming project, initial drawing commands fell from 387,223 to 467.
The reduction depends on the source texture and screen contents; patterned sources still require tiling.
The optimized paths preserve clipping, transparent and translucent pixels, semantics, input, scroll position and invalidation.

Existing screen definitions require no API migration from 0.2.0, and remote communication contracts remain unchanged.
Update dependencies and the installed exact-version Fabric runtime together; the supported Minecraft matrix is unchanged.

[Detailed changes and upgrade notes](docs/releases/v0.2.1.md)

## 0.2.0 - 2026-09-26

- `UiDefinition` and `UiSession` unify screens and HUDs, including presentation switching, lifecycle status, visibility rules and independent game-input policies.
  Compatible switches retain content, editing values and scrolling.
- `mutableStateOf`, `MutableState` and read-only `State` track reads during content evaluation.
  Changes schedule reevaluation, while equal assignments skip invalidation; external `StateSource` and `Observe` remain available.
- Paper, Folia and Velocity plugins can own UI definitions and handlers, rendered by matching Fabric clients.
  Paper uses the primary thread, Folia follows the player scheduler, and Velocity uses a dedicated UI thread.
- Separate platform API artifacts expose `PaperUi`, `VelocityUi` and typed client/UI lifecycle events.
  Plugin disable and disconnection release associated ownership.
- JVM and JavaScript share the API and retained core.
  The Web runtime adds themed DOM rendering, generated initial HTML and adoption of matching initial content; unsupported capabilities fail explicitly.
- Typed declaration projections support remote components and registered extensions without sending application objects or executable code.
  Minecraft 26.3 gains an exact-version Fabric runtime.

Rebuild consumers for session-receiver event callbacks, adapting stored handlers where necessary.
Create mutable state outside reevaluated content and access live sessions through their execution owner.
`ScreenDefinition` and `Screens.open(...)` remain deprecated delegates to the common path.
Server-owned UIs require the platform plugin and matching client runtime.

[Detailed changes and upgrade notes](docs/releases/v0.2.0.md)

## 0.1.6 - 2026-09-09

- TextField and TextArea accept `TextInputAppearance.Custom` with separate normal, focused and disabled nine-slice frames, caret color and IME composition-underline color.
  Text style and font retain their existing controls.
- Transparent frame pixels reveal the underlying background, while stretched nine-slice centers preserve editor padding and text metrics.
  Omitting the appearance or selecting `Default` retains the previous profile frame and decorations.
- Appearance changes repaint retained editors without remeasurement, node replacement or loss of focus, composition and scrolling.
  Immutable images and appearances should remain outside observed content.
- Fractional `scaleToFit` viewports preserve precise scroll and paint clips until physical pixel coverage is determined.
  Images crossing a fractional edge use bounded fallback with unchanged source mapping; wholly contained images keep the direct native path.

Existing appearance overloads and ordinary screen layouts remain available.
Custom exhaustive display-list backends must handle `DrawCommand.PushFractionalClip`, balanced by `PopClip`.
Opaque platform payloads crossing fractional clips are rejected before submission; wholly contained payloads remain supported.

[Detailed changes and upgrade notes](docs/releases/v0.1.6.md)

## 0.1.5 - 2026-09-08

- Components accept direct `StateSource` inputs, including source-backed Text and mixtures of literal and source values for controls, media and lists.
  Existing literal overloads remain available.
- `Observe` declares a changing region with one through 22 source arguments, a modifier and an optional stable key.
  Wrap multiple emitted components in a layout because each region emits zero or one root.
- Retained trees share observations by source identity and commit pending revisions at the frame cutoff.
  Unchanged regions reuse retained descriptions, and compatible keyed descendants preserve focus, selection, IME preedit and viewport state.
- Lazy `source.map` projections support nullable values and chains.
  UI projections compute from committed frame inputs and invalidate dependents only when the result changes.
- Optional `RuntimeUiDiagnosticsOwner` monitoring records content, node, subscription and pipeline work on the owner thread, with bounded diagnostic retention.

Existing `ScreenDefinition` opening APIs remain available; screens need not reopen for each source update.
Keep editing, scrolling and other navigation state outside observed callbacks, retain reusable projections, and keep transforms pure.
Ordinary captured Kotlin reads are not implicitly reactive in this release.
Use one immutable source model when multiple fields need an atomic application update.

[Detailed changes and upgrade notes](docs/releases/v0.1.5.md)

## 0.1.4 - 2026-09-06

- `Modifier.scaleToFit` measures a subtree at a fixed positive design size and uniformly fits that surface inside the outer bounds without cropping or distortion.
  Alignment positions unused space, and upscaling is opt-in.
- The retained runtime carries the same child transform through descendant geometry, portable painting, clips, pointer input, focus visibility, semantics and overlay anchors.
  Zero-sized outer bounds leave the design subtree unplaced for that pass.
- Pointer local positions and drag deltas follow the inverse transform, while scroll units remain unchanged.
  Root overlays retain root-space painting with transformed anchors.
- Deferred Fabric renderers preserve committed frame-layer order when fractional transforms create overlaps that native integer bounds cannot fully represent.
  Immediate renderers retain the shared sequence without an extra ordering operation.

Existing modifiers remain available.
Place viewport modifiers before `scaleToFit` and design-space modifiers after it.
Custom child-transform nodes must invalidate layout when their reported transform changes.
Opaque platform draws, including native items, still require unit scale and exact integer translation; unsupported transforms fail before adapter output.

[Detailed changes and upgrade notes](docs/releases/v0.1.4.md)

## 0.1.3 - 2026-09-01

- Ignored Tab presses cycle focus through placed, focusable components in declared order; Shift+Tab reverses direction and traversal wraps at both ends.
  Focused handlers keep first refusal by consuming the event.
- Traversal skips targets outside the viewport or ancestor child clip, including clipped VirtualList overscan rows.
- `Modifier.onActivate` combines primary pointer presses with focused Enter and Space presses, including key repeats.
  The disabled overload preserves the incoming modifier and retains no action or input target.
- Minecraft UI hosts reuse successfully resolved resource-backed images by structural `ResourceId`, preserving immutable image identity across equivalent content rebuilds and enabling native texture reuse.
- Retention is bounded to 512 identifiers and 128 MiB of pixel storage per host, with no eviction.
  Failed or over-limit resolutions remain retryable, and host close releases retained entries.
  A new host resolves against the current resource-pack stack.

The activation overloads are additive; existing component and modifier signatures remain available.
Use the same enabled state for the component and `onActivate`, or keep `onPress` for pointer-specific behavior.
Custom Minecraft platform adapters must allow successful resource-image resolutions to be reused rather than expect a callback for every declarative use.

[Detailed changes and upgrade notes](docs/releases/v0.1.3.md)

## 0.1.2 - 2026-08-30

- Standard Minecraft `Image` declarations emit `DrawCommand.SampledImage` for their selected source region and logical destination.
  Qualified Fabric presenters can retain immutable images as native textures and avoid rebuilding the portable viewport for placement-only changes.
- `PlayerHead` uses the sampled-image path for both the face and optional hat, preserving face-before-hat composition and transparent hat pixels.
- `PlayerHeadScale` keeps equal skin-texel sizes and the normalized skin's exact face and hat regions.
  Accepted legacy sizes not divisible by eight retain the bounded premultiplied bilinear image introduced in 0.1.1, now presented through the same retained path.
- Headless rendering continues to consume the portable command with its existing final-density sampling contract.

No public API members are added or removed from 0.1.1.
The standard components now produce sampled-image commands instead of integer `BlitImage` commands, so custom backends must support or explicitly reject the variant introduced in 0.1.1.
The supported Minecraft matrix is unchanged; keep common artifacts and the exact-version runtime aligned.

[Detailed changes and upgrade notes](docs/releases/v0.1.2.md)

## 0.1.1 - 2026-08-30

- Minecraft 1.20 gains an exact-version Fabric runtime, lowering the supported release floor from 1.20.1.
- Text, labels, tooltips and editors can use resource-pack fonts.
  TextArea adds multiline editing, visual-line navigation and vertical scrolling; editors preserve Unicode code-point boundaries and separate IME composition from committed values.
- Explicit multiline Text layout supports wrapping, line limits, clipping, ellipsis and line spacing.
  An optional LWJGL font backend renders supplied resources on the CPU without launching Minecraft.
- FlowRow wraps children with checked gaps and alignment.
  Canvas embeds immutable CPU images, leased native textures or attachment-owned offscreen renderers, and captured pointer input preserves drag/release delivery outside component bounds.
- TiledImage and PanZoomState add bounded multiresolution tile presentation, anchored zoom, fit modes, clamping and coordinate transforms.
- `PlayerHeadScale` expresses positive integer skin-texel scaling.
  The older arbitrary-size overload remains available but is deprecated; retained sampled-image commands enable qualified native texture reuse.

Custom exhaustive visitors must handle the new `UiText.WithFont` and `DrawCommand.SampledImage` cases.
Existing JVM signatures remain available, but old exhaustive visitors can fail when receiving a new variant.
Font coverage follows the selected Minecraft resources; system fonts and a separate color-emoji engine are not added.

[Detailed changes and upgrade notes](docs/releases/v0.1.1.md)

## 0.1.0 - 2026-08-25

- The initial public API defines screens independently of the runtime implementation.
  Applications compile against `strata-api` and open a one-shot `ScreenDefinition` through `open()` or Java's `Screens.open(...)`.
- Row, Column, Grid and intentional-overlap Stack provide layout, arrangement, alignment, spacing, weight and checked constraints through active modifier chains.
- Built-in controls cover text and single-line editing, buttons, checkboxes, cycling choices, sliders, tabs, scrolling and selection lists, images, inventory Slots, player heads, loading and progress.
- VirtualList supports leading/trailing requests and index or stable-key jumps.
  Resource-backed media, profile-driven heads and synchronized inventory bindings integrate with the installed client runtime.
- Public Element and Node contracts let consumers define retained primitives, while application-specific components can remain ordinary compositions.
- The retained core and headless renderer provide deterministic PNG and semantics output.
  Exact-version Fabric adapters connect the shared definitions to Minecraft, with loaded-client and inventory-interaction verification.

Install one runtime matching the client together with Fabric Language Kotlin.
The initial matrix starts at Minecraft 1.20.1 and includes the supported 1.21 releases, 26.1 and 26.2.
The verified bitmap font path supports printable ASCII; forced Unicode and multi-resource font stacks are unavailable.
Resource-pack image replacements preserve the declared logical dimensions.

[Detailed changes and upgrade notes](docs/releases/v0.1.0.md)
