# Rendering contracts

This document is for retained-engine and platform-presenter implementers.
It defines pipeline ownership, command interpretation, portable rasterization, and native presentation.
Use [headless rendering](../guides/headless.md) for application and testing entry points, and [performance](../development/performance.md) for cache keys, bounds, and release gates.

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
Clean paint reuses complete local display lists through the current accumulated transform.
Clean semantics reuses complete local payloads and combines them with current outward-projected bounds.
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
Focused handlers run from the current owner's innermost modifier through its component before the core interprets an ignored Tab press.
Traversal visits accepting visible logical owners cyclically in parent-before-child and declared sibling paint order, with Shift reversing direction and every other modifier bit leaving it unchanged.
Visibility requires a nonempty intersection with the fixed root viewport and every ancestor child clip, excluding clip-only VirtualList overscan while retaining a placed hidden current owner until explicit traversal.
Removing or unplacing the current owner clears focus; a later Tab starts at the first or last eligible owner without stable-key reacquisition.
`onActivate` supplies one accepting focus target and maps primary pointer presses plus each focused Enter or Space press or repeat to the same action, while its disabled overload contributes no retained node.

Modifiers are active retained nodes in the effective pipeline ancestry and do not become settings copied into component nodes.
Component scopes continue to expose logical children, while a modifier scope exposes its one virtual child.
Modifier-chain changes preserve the retained component and its logical subtree.
Removed modifier nodes finish cleanup during reconciliation, and newly created modifier nodes attach only after the complete incoming tree reconciles successfully.
Typed parent data is supplied by active modifier capabilities and queried only through measure or layout scopes.
Lookup uses a referential key, scans the requested direct child's modifier chain, selects the innermost match, and stops before the component node without measuring or placing the child.
The full modifier contract and external implementation guidance are defined in [Modifiers](../reference/modifier-spi.md).

## Headless rasterization

The headless facade validates positive logical width, height, and scale before description validation, node creation, or lifecycle hooks.
It checks physical width, height, and row-major area with checked integer arithmetic and reports arithmetic failure instead of wrapping or allocating an invalid image.
Low-level commands are snapshotted in list order, validated for balanced nested child clips, intersected with those clips and the positive logical viewport, and painted onto transparent black.
Clips use final physical pixel-center coverage, including fractional transformed viewport edges.
A partial physical block does not change the source coordinate used by an integer image command.
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
The exact built-in layout measurement, wrapping, weight, arrangement, alignment, and overflow contracts are defined in [Built-in layout components](../guides/layout.md).
The headless adapter's fixed-viewport, clipping, source-over, scaling, PNG, and immutable semantics contracts are exercised by its module tests.

`SampledImage` maps final physical pixel centers through its original fractional destination into the source image.
It multiplies normalized source and tint channels without intermediate eight-bit rounding, discards samples below the alpha cutoff, and applies normalized floating-point source-over before final half-up quantization.
The original source/destination mapping survives viewport clipping.
Custom backends must implement every emitted command or reject unsupported commands before partial output; see [source compatibility](../reference/element-spi.md#source-compatibility).

## Native Canvas presentation

The Canvas element implements ordinary measure, paint, frame-cutoff, and session-attachment capabilities and does not add concrete-component dispatch to core or ComponentRuntime.
The source remains externally owned, while each attached node owns a fresh binding identified by a scalar CanvasId that survives source replacement and session reattachment.
Bindings close before replacement or suspension, and all acquired resources must be released if opening a binding fails.
CPU observers only enqueue the newest revision on any thread; timed and untimed frames commit it through the global two-phase cutoff described in [UI sessions](../development/ui-sessions.md).

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
When that abstraction exposes one host-owned command encoder, allocation, capture, upload, and GUI-completion fences remain in Minecraft's current submission instead of submitting ordinary Canvas work independently.
For that shared-encoder family, only terminal device teardown may explicitly submit the encoder before waiting for completion.
Screen removal stops bindings and input immediately, but a screen-independent render-thread registry polls retired GPU work without waiting.
Source replacement, resize, reattachment, reload, failed capture, failed GUI work, unsubmitted cancellation, and device shutdown follow the same ownership protocol.
Only device teardown after GUI queues have been discarded may wait for GPU completion; it may also submit recorded work as required.
Allocation, capture, or GUI-fence uncertainty quarantines affected resources until that teardown; cleanup failures preserve the primary exception and never return a permit before successful physical destruction.
Deferred native destruction is acknowledged separately from `close()`: Vulkan texture and view retirement is counted until the backend actually destroys every owned attachment.
Terminal cleanup drains that native destruction queue only after GPU completion and GUI discard; it cannot release a target permit merely because retirement was requested.
Device shutdown rejects new screen attachments and source owners before invoking application cleanup callbacks, including reentrant attempts to install another screen.

Public provider, renderer, and capture obligations are in the [Canvas guide](../guides/canvas.md).
The [Canvas resource contract](../development/performance.md#canvas-source-and-target-retention) defines target permits, retained generations, and deterministic release checks.

## Direct sampled-image presentation

Portable correctness does not require a native cache, but repeatedly rasterizing a moved tile run into a viewport-sized CPU image would defeat the component's invalidation boundary.
Fabric presenters therefore directly draw eligible `SampledImage` commands from a bounded device-owned texture cache.

The native cache key is the physical device generation and `DrawImage` referential identity.
Source and destination rectangles, clip, GUI scale, overlay state, and frame revision are deliberately excluded.
Pan and zoom may issue new destination geometry without another pixel copy or upload, player-marker movement cannot invalidate tile textures, and one replacement image uploads only that identity.
Unsupported commands retain their exact semantics through a tightly bounded portable fallback layer.

Active, initializing, retired, and physically releasing native entries count against explicit entry and byte limits.
Entries used by an extracted GUI frame remain pinned through the actual GUI-consumption fence.
Eviction first removes an unpinned least-recently-used cache entry, then transfers its native storage to device-owned retirement; failed destruction remains charged until terminal acknowledgement.
Screen release drops screen-owned image references immediately, while device shutdown completes submitted work and independently drains every retained native resource.

## Native verification

The [loaded-client tasks](../development/build.md#loaded-client-verification) independently compare native scenes, Fabric output, portable output, and server-backed interactions.
The [font gate](font-verification.md#acceptance-evidence) defines the additional evidence required for classified GPU differences.
Documentation rendering and its native acceptance are separate workflows described in [documentation maintenance](documentation.md).

## Fractional viewport boundaries

Core keeps integer clip commands for exact integer edges and emits `PushFractionalClip` for fractional edges.
The headless renderer intersects their physical coverage before painting, retaining each primitive's original source mapping.
The Minecraft partitioner uses conservative integer bounds only for allocation and visibility envelopes.
A direct sampled image wholly contained by fractional clips keeps its existing immutable texture cache and native path.
An image crossing a fractional boundary uses the existing bounded portable fallback with the original source and destination, avoiding source-UV cropping or resampling drift.
Opaque platform payloads crossing such boundaries are rejected during partition preflight rather than drawn with an expanded clip.
No frame history or additional image cache is introduced; static frames reuse their prepared layers.

Verify coverage independently of backend equality: both backends can agree on the same incorrect rounded clip.
The shared loaded-client scenario compares a three-quarter-scaled VirtualList with an independently defined integer reference, then requires 100 stable host frames without new preparation, rasterization, or upload.
Headless tests additionally mask an unclipped reference by physical pixel-center predicates across output scales 1-4, all portable primitive types, mixed nested clips, and empty or offscreen bounds.
