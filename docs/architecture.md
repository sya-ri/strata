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
  Top-level menu, generic-container, and arbitrary-image background modifiers plus highlighted Slot; single-line or structurally wrapped resource-font Text; owner-thread Unicode TextField and TextArea editors; profile-backed Button, Checkbox, CycleButton, Slider, and Tab controls; independently composed ScrollArea and Scrollbar components; visible-only VirtualList and SelectionList components; immutable whole-image or source-region Image; synchronous or asynchronous PlayerHead; LoadingIndicator; and ProgressBar therefore work without an extra root wrapper or public Minecraft context receiver.
  A version-backed bound Slot uses an opaque ordered platform draw command for the native `ItemStack` phase and delegates mutation to the active server menu; declarative locators cover player-inventory, logical non-player Container, and raw active-menu indices, core preserves ordering and clipping without depending on a Minecraft type, and portable-only rasterization rejects the unsupported payload before producing output.
  Button owns profile-backed appearance and enabled semantics, while platform-neutral active modifiers own raw pointer, keyboard, committed-character, preedit, focus, press, release, move, drag, scroll, and hover actions reusable by future Minecraft components.
  Hosts retain the core tree across transient detach and reattach, gate input until a successful frame, and expose no mapped game, Fabric, resource-manager, renderer, version, coroutine, state, or source-binding type.
  Common code may carry a structural `ResourceId` across client/server configuration or protocol boundaries, while the versioned client loader resolves that identifier through the active resource-manager stack and returns detached immutable pixels.
  Button does not install focus or keyboard activation implicitly; callers compose those policies from shared modifiers, and hover changes only in response to delivered pointer movement.
- `runtime:minecraft-fonts-lwjgl` is the optional CPU font backend built on the common Minecraft font contracts.
  Immutable snapshots retain only detached definitions and resources, while each host owns its own backend, bounded native faces, and raster cache.
  The backend uses the target release's supplied STB or FreeType and ICU libraries without Minecraft classes, Java2D, operating-system fonts, or a graphics context.
  Text and common labels share font selection and metrics with TextField and TextArea; multiline Text and TextArea also share line breaking and visible-glyph selection.
  Portable floating-point sampled-image commands are rasterized at the final GUI scale by headless and Fabric presenters.
  The existing finite ASCII profile format remains available, but a profile cannot mix it with a resource-font snapshot.
  See [Font resources](font-resources.md) for source precedence, capabilities, failure behavior, dependency isolation, and terminal ownership.
- `integration:api` compiles its application main source with only `api`, checks that compile classpath mechanically, and uses test-only runtime dependencies to verify both standard and external primitives.
- `runtime:minecraft-fabric-26.2` is the client-only boundary for the current latest Java release.
  It extracts the 26.2 vanilla profile and arbitrary Mod images from the active resource manager, maps the common host to a native Screen, rasterizes through the tested headless path, and forwards typed mouse, keyboard, committed-character, and preedit input.
  Its loaded client GameTest compares the existing fixed native screen scenes using the actual menu background, generic container, Slot highlights, font, EditBox, Button, `ObjectSelectionList`, and `PlayerFaceExtractor` assets and widgets against both the Fabric adapter and the common headless compositor with exact ARGB equality, then compares the custom industrial and progression Mod screens through the same Fabric/headless pixels.
  Separate resource-font scenes require exact native glyph metrics, texels, and layout and independently verified GPU evidence for any final native pixel differences; their Fabric and headless images remain exact.
- `runtime:minecraft-fabric-26.1` is the client-only boundary for Minecraft 26.1.
  Both unobfuscated releases compile the complete cross-version shared and unobfuscated-release adapter source roots and the same neutral tests; only current-screen access is implemented per release, and the loaded 26.1 suite records the same fixed-scene ARGB hashes as 26.2.
- `runtime:minecraft-fabric-1.21.11` through `runtime:minecraft-fabric-1.20.5` are client-only Java 21 boundaries for the older remapped distributions, and `runtime:minecraft-fabric-1.20.4` through `runtime:minecraft-fabric-1.20` are the corresponding Java 17 boundaries.
  All nineteen compile against official Mojang mappings and reuse the resource, profile, font, text, lifecycle, native-neutral input, inventory, and current-screen behavior proven by every supported legacy compiler and loaded client.
  The 1.21.11 project consumes the shared `Identifier` aliases, while the 1.21.10 through 1.21 projects own their mapped `ResourceLocation` aliases locally.
  The 1.21.1 through 1.20.5 projects isolate ABGR native-image access and the earlier texture blit signature, while 1.20.6 and 1.20.5 additionally own their constructor-based `ResourceLocation` factories and parsers; the profile boundary selects typed legacy Slot, Tooltip, and horizontal-progress treatments from actual release capabilities rather than version strings.
  The 1.20.4 through 1.20.2 projects share the compiler-proven `GameProfile`-based skin resolution and key-binding bridges, select their legacy active-pack menu/list/separator GUI sprites by capability, and reproduce the black scrollbar track drawn directly by those clients; 1.20.2 retains its active header separator while later releases synthesize the absent asset.
  The 1.20 and 1.20.1 projects own separate exact Minecraft ABIs and artifacts while sharing the compiler-proven preceding atlas and Authlib 4 implementation boundary: they extract resource-pack-controlled button, slider, checkbox, and progress images from their exact atlas regions, preserve Vanilla nine-slice geometry and code-defined controls, and isolate the older skin normalization and standalone cleanup APIs without runtime version dispatch.
- `runtime:minecraft-fabric-1.21-legacy` is a neutral source root rather than a Gradle project or published artifact.
  It owns only behavior proven identical across the supported remapped legacy releases, including detached primitive input state and a version-neutral resolved-skin reference; each versioned project still owns metadata, dependencies, ABI, publication, and verification.
- `runtime:minecraft-fabric-1.21.9-legacy` and `runtime:minecraft-fabric-1.21.8-legacy` are complete release-family source roots for incompatible native Screen, key binding, and player-skin APIs.
  The newer root owns record-based callbacks and asset-based skins, while the older root owns primitive callbacks and resource-location skins shared by 1.21.8 through 1.21.4 without reflective dispatch.
- `runtime:minecraft-fabric-1.21.3-legacy` isolates the direct player-skin future used by 1.21.3 through 1.20.5 before the optional result boundary and owns the compatible primitive key-binding bridge without source filtering.
- `runtime:minecraft-fabric-1.21.5-legacy` and `runtime:minecraft-fabric-1.21.6-legacy` isolate the mapped `GuiGraphics` boundary without reflective dispatch.
  Minecraft 1.21.2 through 1.21.5 submit retained textures through `RenderType.guiTextured` and raise carried items with a scoped pose-stack translation, Minecraft 1.21 and 1.21.1 use the older direct blit signature, and 1.21.6 and later supported remapped releases use `RenderPipelines.GUI_TEXTURED` and `GuiGraphics.nextStratum()`.
  Their frame presenter delegates dynamic-texture and native-pixel access to compile-time bridges because 1.21 and 1.21.1 expose ABGR accessors, 1.21.2 through 1.21.4 expose ARGB accessors with only an unnamed `NativeImage`, and 1.21.5 and later supported remapped releases accept the named supplier form.
- `runtime/minecraft-fabric-shared` is the neutral source root for complete files proven compatible across every supported version; it is not a Gradle project or published artifact.
  Its source directories are linked as whole roots so Gradle, IDEs, and static analyzers agree on file ownership without relying on file-tree filters.
- `runtime/minecraft-fabric-identifier` is the neutral source root for internal mapped-name aliases used by releases whose official mappings expose native resource keys as `Identifier`; it is not a Gradle project or published artifact.
  Releases that still expose `ResourceLocation`, including 1.21.10 and 1.21.9, keep equivalent aliases in their versioned project instead of weakening the shared adapter contract.
- `runtime/minecraft-fabric-unobfuscated` is the neutral source root for files shared only by the unobfuscated releases; it is not a Gradle project or published artifact.
  Each versioned project owns its metadata, dependency graph, version-specific bridges, ABI, publication, and verification task.
- `integration:minecraft-fabric-1.21.11` through `integration:minecraft-fabric-1.20` compile the complete runner-independent `integration/minecraft-fabric-1.21-legacy` suite against their exact remapped Java 21 or Java 17 boundaries; none is published.
  Each runs the same loaded-client behavior from development outputs and from the remapped production integration and runtime jars, while the complete 1.21.9-record or 1.21.8-primitive test-input root follows the target callback generation and the 1.21.5 version-name root follows the mapped accessor.
  The `integration/minecraft-fabric-client-gametest` source root adapts Fabric Client GameTest for 1.21.4 and later, while `integration/minecraft-fabric-1.21.3-legacy` supplies an ordinary client entrypoint, tick synchronization, disposable world ownership, and failure propagation for 1.21.3 through 1.20.5 before that Fabric module existed; 1.20.4 through 1.20.2 share the equivalent older dirt-message and level-cleanup runner boundary, and 1.20 plus 1.20.1 share the compiler-proven preceding readiness and level-clear variant while retaining exact owning projects.
- `integration:minecraft-fabric-26.1` compiles and runs the neutral loaded-client scenarios against the 26.1 runtime and integrated server; it is not published.
- `integration:minecraft-fabric-26.2` owns the loaded client vanilla parity scenes, integrated-server player/custom/ender-chest Slot scenarios, resource-pack-aware industrial and advancement-inspired progression screens, compiled Minecraft-component examples, and build-only verification evidence; it is not published.
- `integration:docs` discovers public top-level component extensions mechanically from compiled API classes, extracts dedicated minimal component `ScreenDefinition` and complete-screen sources, verifies the Minecraft parity receipt, full-frame viewports, and PNG hashes, and owns the combined generated component document; it is not published.
The root build's typed Minecraft target matrix describes these exact compile, verification, publication, toolchain, and source-link boundaries once.
It is build metadata only: runtime code still selects behavior through compiled capabilities and never reads a version string or dispatches through the matrix.
Configuration on demand keeps unrelated JVM work from configuring every Loom project, while aggregate quality and publication commands continue to traverse the complete matrix.
Platform-independent code must not depend on a Minecraft runtime.
Minecraft and Fabric dependencies remain confined to the versioned runtime boundary that requires them.

## Supported Fabric runtimes

The supported Minecraft range begins at 1.20; Minecraft 1.19 and older releases are outside the project scope.
Select exactly one versioned Fabric runtime at execution time.
The version artifacts intentionally expose the same Strata-owned entry points and class names, while their inherited Minecraft `Screen` methods differ with the native release, so depending on more than one creates duplicate classes.

Each versioned Fabric Mod packages the common API, core, headless, Minecraft runtime, and CPU font backend jars; native libraries come from the game.

| Minecraft | Fabric runtime artifact | Required Java | Loaded verification |
| --- | --- | --- | --- |
| 26.2 | `strata-runtime-minecraft-fabric-26.2` | 25 | Exact fixed-screen native/Fabric/headless parity, independent resource-font GPU proofs, Mod-screen parity, and synchronized inventory GameTests |
| 26.1 | `strata-runtime-minecraft-fabric-26.1` | 25 | The same loaded suite; every recorded ARGB hash matches 26.2 for the fixed verified scenes |
| 1.21.11 | `strata-runtime-minecraft-fabric-1.21.11` | 21 | Development and production-jar loaded-client verification for the shared remapped legacy boundary |
| 1.21.10 | `strata-runtime-minecraft-fabric-1.21.10` | 21 | Development and production-jar loaded-client verification for the shared remapped legacy boundary |
| 1.21.9 | `strata-runtime-minecraft-fabric-1.21.9` | 21 | Development and production-jar loaded-client verification for the shared remapped legacy boundary |
| 1.21.8 | `strata-runtime-minecraft-fabric-1.21.8` | 21 | Development and production-jar loaded-client verification for primitive screen input and resource-location player skins |
| 1.21.7 | `strata-runtime-minecraft-fabric-1.21.7` | 21 | Development and production-jar loaded-client verification for the shared primitive-input release family |
| 1.21.6 | `strata-runtime-minecraft-fabric-1.21.6` | 21 | Development and production-jar loaded-client verification for the shared primitive-input release family |
| 1.21.5 | `strata-runtime-minecraft-fabric-1.21.5` | 21 | Development and production-jar loaded-client verification for the pose-stack and render-type compatibility boundary |
| 1.21.4 | `strata-runtime-minecraft-fabric-1.21.4` | 21 | Development and production-jar loaded-client verification for the unnamed dynamic-texture compatibility boundary |
| 1.21.3 | `strata-runtime-minecraft-fabric-1.21.3` | 21 | Development and production-jar loaded-client verification for the direct player-skin future and pre-Client-GameTest boundary |
| 1.21.2 | `strata-runtime-minecraft-fabric-1.21.2` | 21 | Development and production-jar loaded-client verification for the shared direct-skin standalone-runner family |
| 1.21.1 | `strata-runtime-minecraft-fabric-1.21.1` | 21 | Development and production-jar loaded-client verification for legacy pixel, blit, Slot, Tooltip, and progress treatments |
| 1.21 | `strata-runtime-minecraft-fabric-1.21` | 21 | Development and production-jar loaded-client verification for the same legacy pixel, blit, Slot, Tooltip, and progress treatments |
| 1.20.6 | `strata-runtime-minecraft-fabric-1.20.6` | 21 | Development and production-jar loaded-client verification for the legacy resource-construction boundary and complete standalone suite |
| 1.20.5 | `strata-runtime-minecraft-fabric-1.20.5` | 21 | Development and production-jar loaded-client verification for the same legacy resource-construction boundary and complete standalone suite |
| 1.20.4 | `strata-runtime-minecraft-fabric-1.20.4` | 17 | Development and production-jar loaded-client verification for the legacy Java, GUI-asset, player-profile, and standalone-runner boundaries |
| 1.20.3 | `strata-runtime-minecraft-fabric-1.20.3` | 17 | Development and production-jar loaded-client verification for the same legacy Java, GUI-asset, player-profile, and standalone-runner boundaries |
| 1.20.2 | `strata-runtime-minecraft-fabric-1.20.2` | 17 | Development and production-jar loaded-client verification for the same legacy Java, GUI-asset, player-profile, and standalone-runner boundaries with the active header separator |
| 1.20.1 | `strata-runtime-minecraft-fabric-1.20.1` | 17 | Development and production-jar loaded-client verification for the pre-GUI-sprite atlas, legacy player-skin, and release-local standalone-runner boundaries |
| 1.20 | `strata-runtime-minecraft-fabric-1.20` | 17 | Development and production-jar loaded-client verification for the same pre-GUI-sprite atlas, Authlib 4 player-skin, and release-local standalone-runner boundaries against the exact 1.20 artifact |

## Component extension policy

Strata standardizes only focused primitives that serve at least two natural independent uses and do not encode one screen or application-domain model.
Every proposal for a standard built-in is reviewed for excessive specialization and for whether an ordinary composition of existing primitives is sufficient.
Minecraft-specific primitives remain eligible when their responsibility is broadly reusable across Minecraft UI; a player-head renderer can serve social lists, player lists, profiles, teams, and ownership displays, while a social-entry row or an advancement graph remains application composition.

`TextArea` passes this gate as one multiline text-editing primitive: note editing and message drafting are independent natural uses.
It owns no note, chat, book, file, or server-domain model.
A column of `TextField` instances cannot preserve one logical value and cursor across hard breaks, soft wrapping, and inline IME composition; those coupled editing rules justify the primitive.
Toolbars, submit buttons, validation messages, and the optional external `Scrollbar` remain ordinary composition.
The caller owns `TextAreaState` and its `scrollState`; a fixed 9-pixel logical line box and typed `TextAreaViewport` define geometry, while `SemanticsRole.TextArea` exposes committed `Semantics.value` without promising accessibility edit actions.
Display-only wrapping and truncation remain a policy of the existing `Text` component through `TextLayout.Multiline`, not another standard component.
See [Text layout and editing](text.md) for newline, scalar, IME, and non-goal contracts.

This standard-library gate does not constrain downstream code.
An application or Mod may define a purpose-specific component such as an energy gauge or social entry as an ordinary `UiScope` composition function, or implement new retained behavior with a custom immutable `Element`, stable singleton `ElementType`, and capability-bearing `Node`.
`UiScope.element` inserts that description without registration, and the retained core never dispatches on the concrete component class.
External component implementations receive the same reconciliation, key, modifier, cache, input, semantics, lifecycle, and failure contracts as Strata's built-ins.
The complete external implementation contract is documented in [Element SPI](element-spi.md).

The process and compatibility requirements for a new version adapter are defined in [Supporting a new Minecraft version](minecraft-versions.md).

The public API currently defines `ScreenDefinition`; Row, FlowRow, Column, Stack, Grid, Spacer, Text, TextField, TextArea, Button, Checkbox, CycleButton, Slider, Tab, ScrollArea, Scrollbar, VirtualList, SelectionList, Image, Canvas, Slot, PlayerHead, LoadingIndicator, and ProgressBar; element and modifier descriptions; typed actions and external state; typed layout parent data; retained node capabilities; frame time and overlay painting; lifecycle ownership; geometry; pointer and focused input; drawing; semantics; unresolved text; resources and bindings; and revisioned external state sources.
`ScreenDefinition` retains its callback without evaluating it, then transfers that callback exactly once to a runtime that implicitly builds its single component root under the installed profile.
Each `UiScope` is confined to the invoking thread and callback lifetime, and callback failures take precedence over root-cardinality validation.
The privileged `evaluateComponentTree` bridge exists only for runtime adapters and structural SPI tests that already own raw elements; application screens do not need or expose a standalone root builder.
The state-source contract is specified and exercised by concurrency tests described in [External state sources](state-sources.md).
It remains coroutine-free and does not include a platform lifecycle adapter.
The retained core's tested internal session contract is described in [UI sessions](ui-sessions.md).

## Canvas ownership and rendering

Canvas has one responsibility: embed external drawing output in a positive, explicitly sized logical rectangle.
Decoded video output and independently produced camera, filter, or custom-renderer output are separate natural uses.
The built-in is not an application player or camera model.
Image already displays immutable CPU frames, but composition with it does not provide native texture leases, isolated offscreen rendering, or GPU completion tracking.
Canvas shares the existing portable image path and state cutoff while adding one attachment lifetime contract for CPU and native producers.
Video decoding, audio, world or camera rendering, filter algorithms, browser engines, and drawing into the current GUI or framebuffer remain application concerns.

`UiScope.Canvas(source, size, modifier, key)` stretches the complete source image with nearest sampling.
`canvasSource(DrawImage)` and `canvasSource(StateSource<DrawImage>)` need only the platform-neutral API.
DrawImage owns immutable straight-ARGB pixels independent of the caller's array; replacing its pixel extent updates paint without changing the declared destination.
The Canvas element implements ordinary measure, paint, frame-cutoff, and session-attachment capabilities and does not add concrete-component dispatch to core or ComponentRuntime.
The source remains externally owned, while each attached node owns a fresh binding identified by a scalar CanvasId that survives source replacement and session reattachment.
Bindings close before replacement or suspension, and all acquired resources must be released if opening a binding fails.
CPU observers only enqueue the newest revision on any thread; timed and untimed frames commit it through the global two-phase cutoff described in [UI sessions](ui-sessions.md).

Each versioned Fabric runtime adds typed `canvasSource` factories for a MinecraftCanvasTextureProvider and a MinecraftCanvasRenderer factory.
No Minecraft type enters the API or core modules.
Native input is an ordinary two-dimensional RGBA8 straight-alpha color image; the adapter validates its extent and capabilities and normalizes texel rows to a top-left origin.
Native source and physical target axes are limited to 32768 pixels so integer pixel-center sampling cannot overflow; the actual device may impose a lower limit.
Sampling uses the explicit target extent and exact integer pixel-center ratios, including odd-sized destinations and source-row reversal, so supplied snapshots describe the same nearest-sampled texels.
Sampling into a Strata-owned target also accepts sampleable inputs that do not provide copy-source usage; unsupported formats and inputs fail explicitly.
A custom renderer borrows only its offscreen target, logical and physical sizes, and frame time for one callback, with optional depth selected at source construction.
Instances belong to attachments and are not shared merely because two canvases share a source description.
The native binding initially owns only a description; a custom renderer factory runs lazily inside its first reserved target capture so initialization work is covered by that capture fence.
The callback context expires before returning to the presenter, and application sources are never closed by Strata.
The [compiled native scene](https://github.com/sya-ri/strata/blob/master/integration/minecraft-fabric-canvas-shared/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftCanvasNativeExample.kt) composes independently produced textures and custom-renderer output with clipping and portable overlays.
Its [source fixture](https://github.com/sya-ri/strata/blob/master/integration/minecraft-fabric-canvas-shared/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftCanvasTestFixture.kt) demonstrates both typed factories, shared external ownership, per-attachment renderers, and optional matching snapshots; the loaded suite first checks actual native pixels without any snapshots.

The Fabric presenter prepares each attachment at most once after final layout and hover convergence for the actual native presentation.
Declaration evaluation, measurement, cached painting, and extra host frames never execute a native producer.
Native Canvas payloads in core draw commands and RuntimeUiFrame contain only immutable device and attachment identifiers; the separate prepared presentation adds generation tokens.
Portable commands may retain immutable image pixels, but neither kind of command retains native handles, renderers, nodes, or hosts.
NativeCanvasDevice resolves them into a separate immutable presentation and rejects foreign or expired identities before any partial GUI output.
The extensible MinecraftPlatformCommandRenderer boundary validates the complete mixed command list before rendering and preserves portable/native order, clipping, GUI scale, and Stack overlays, including Slot's native item phase.

Target allocation has its own completion fence because a backend may enqueue initialization work before any producer returns a capture.
The external image lease survives its capture-completion fence, while the owned target survives a distinct fence issued after actual GUI consumption.
Older GUI families flush queued draws before fencing; queued GUI-renderer families fence at the version-owned render-consumption boundary, not during extraction.
The newest GPU family uses the backend-neutral GPU abstraction for OpenGL and Vulkan instead of exposing raw OpenGL through common contracts.
Screen removal stops bindings and input immediately, but a screen-independent render-thread registry polls retired GPU work without waiting.
Source replacement, resize, reattachment, reload, failed capture, failed GUI work, unsubmitted cancellation, and device shutdown follow the same ownership protocol.
Only device teardown after GUI queues have been discarded may wait for submitted GPU work.
Allocation, capture, or GUI-fence uncertainty quarantines affected resources until that teardown; cleanup failures preserve the primary exception and never return a permit before successful physical destruction.
Deferred native destruction is acknowledged separately from `close()`: Vulkan texture and view retirement is counted until the backend actually destroys every owned attachment.
Terminal cleanup drains that native destruction queue only after GPU completion and GUI discard; it cannot release a target permit merely because retirement was requested.
Device shutdown rejects new screen attachments and source owners before invoking application cleanup callbacks, including reentrant attempts to install another screen.

`FabricMinecraftScreen.captureCanvasFrame()` converts the last successfully submitted presentation to portable commands using its immutable CPU receipts only.
Every native token must have an exact same-generation, same-extent, normalized snapshot; missing, mismatched, or unsupported native commands fail before partial output.
An initially unavailable Canvas remains transparent on screen, but its presentation cannot be captured until every requested Canvas has a committed generation and matching snapshot.
Native images become BlitImagePixels commands with unchanged logical destinations; rasterize the capture at the presentation's GUI scale to reproduce every physical texel.
This call never resolves a live GPU token, reads pixels back implicitly, or substitutes placeholder pixels for native evidence.
Canvas itself has no focus, pointer, or keyboard handlers: applications compose the ordinary modifiers, including the generic PointerCaptureNode capability, without a source-specific input-event hierarchy.
The resource bounds, cache keys, invalidation, terminal release, and independent pixel/retention tests are specified in [Rendering performance](performance.md#canvas-source-and-target-retention).

## Headless rendering

The headless facade validates positive logical width, height, and scale before description validation, node creation, or lifecycle hooks.
It checks physical width, height, and row-major area with checked integer arithmetic and reports arithmetic failure instead of wrapping or allocating an invalid image.
Low-level commands are snapshotted in list order, validated for balanced nested child clips, intersected with those clips and the positive logical viewport, and painted onto transparent black.
BlitImage preserves its original rule: select a nearest source texel at each logical pixel center and replicate it at the requested integer scale.
BlitImagePixels instead samples each physical output pixel center, preserving the full resolution of a native Canvas capture.
Subsequent logical fills and image overlays blend separately against each physical destination pixel, without erasing existing subpixel detail.
Coordinates are top-left origin, x-right, y-down, and half-open.

Painting uses straight ARGB Porter-Duff source-over with Long intermediates.
For source alpha `sa`, destination alpha `da`, and channel values `sc` and `dc`, `alphaN = sa * 255 + da * (255 - sa)`, `oa = floor((alphaN + 127) / 255)`, and when `alphaN != 0` each channel is `floor((sc * sa * 255 + dc * da * (255 - sa) + floor(alphaN / 2)) / alphaN)`.
When `alphaN == 0`, the result is exactly `0x00000000`.
Transparent sources are no-ops, opaque sources replace, and there is no interpolation, gamma conversion, saturation, or clipping beyond the viewport and explicit retained child clips.

Images expose only immutable reads, fresh pixel copies, and deterministic PNG encoding.
PNG output contains exactly one IHDR, one IDAT, and one IEND in that order, uses noninterlaced RGBA8 filter-zero rows, deterministic stored DEFLATE blocks no larger than 65,535 bytes, and checked CRC32 and Adler32 values.
Frames retain no description, tree, or draw-command list; semantics are defensive, logical, unscaled, unclipped, and in core emission order.
The exact built-in layout measurement, wrapping, weight, arrangement, alignment, and overflow contracts are defined in [Built-in layout components](layout.md).
The headless adapter's fixed-viewport, clipping, source-over, scaling, PNG, and immutable semantics contracts are exercised by its module tests.
The loaded 26.2 client GameTest requires exact ARGB equality among deterministic native screens, their Fabric-adapter reconstructions, and common headless frames at each locked 320 by 180, 320 by 240, or 64 by 64 acceptance viewport.
It covers `ConfirmScreen`, `DirectJoinServerScreen`, `ContainerScreen`, an actual `ObjectSelectionList`, `SocialInteractionsScreen`, native `PlayerFaceExtractor`, an integrated-server synchronized inventory, and custom industrial and progression Mod screens, then keeps those full-screen acceptance frames separate from the component showcase evidence.
For every standard component, the loaded GameTest also evaluates a dedicated minimal `ScreenDefinition` independently through the Fabric and headless runtimes, requires exact full-frame ARGB equality with a matching animation phase when the component animates, and writes the canonical headless frame at time zero with its logical viewport, GUI scale, and hashes below the build directory.
The Social comparison composes the public primitives with the active social panel and search assets, a compact profile-colored TextField, and PlayerHead, then requires the complete 320 by 240 native, Fabric, and headless images to match exactly.
The progression example keeps its purpose-specific graph downstream while composing active advancement textures through the general source-region Image API; the industrial screen similarly uses a replaceable Mod resource rather than a domain-specific standard component.
The showcase generator separately renders the compiled API-only component and screen examples through the common headless runtime using four explicit read-only asset inputs: the client archive, asset index, indexed objects directory, and version manifest.
Default Gradle provisioning supplies those raw resources without launching Minecraft; the [showcase build properties](build.md) also allow callers to supply all four paths without an integration-project provisioning dependency.
Generation needs no game process or GPU context and does not read loaded-game parity output implicitly.
Its only native image input is the explicitly supplied inventory PNG and receipt under `docs/evidence`, checked against the current compiled example source and image hashes because that screen requires a live server-backed binding.
The generator records deterministic source and asset hashes, logical viewports, GUI scales, physical PNG dimensions, and image origins in `docs/components/headless-render.properties` before staging Markdown and PNG output.
`Text`, `TextField`, and `TextArea` render at GUI scale 2 with unchanged logical viewports; the other components, overview, and complete screens remain at scale 1, and no image is enlarged after rendering.
The headless freshness checker compares that staging output with the combined `docs/components.md` document, the `docs/components` asset tree, and the anchored root README region without writing source files.
The separate native parity checker compares fresh loaded-game frames and their viewport and density metadata with fresh headless staging; its evidence belongs under `docs/evidence`, outside the generator-owned component assets.

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

The test suite exercises `api`, `runtime:core`, `runtime:headless`, `runtime:minecraft`, `runtime:minecraft-fonts-lwjgl`, and the showcase compiler with ordinary JVM tests.
Integration tests belong at the narrowest module boundary that needs them.
Fabric GameTests are reserved for behavior that genuinely requires Minecraft's loaded game environment.
The 26.2 client GameTest is the independent release and documentation acceptance gate for native assets, widgets, placement, logical draw order, and exact final pixels in the existing fixed showcase scenes.
The module check requires this native gate, while the targeted headless showcase generation and freshness tasks run without it.
Separate resource-font gates compare Minecraft 1.20, 1.20.5, and 26.2 with independent CPU renders at GUI scales 1, 2, and 3.
Those gates require exact native metrics, glyph texels, and layout; final native image differences require the independent GPU evidence described in [Font acceptance evidence](font-resources.md#acceptance-evidence).
