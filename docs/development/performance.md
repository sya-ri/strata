# Rendering performance

## Purpose and acceptance

The rendering benchmarks provide repeatable local evidence about retained-frame cost, rasterization cost, and allocation behavior.
Their timing results are diagnostic measurements rather than a release promise or a hard continuous-integration threshold.
Performance changes are accepted through deterministic cache-identity, bounded-retention, lifecycle-release, and rendering-parity tests, with JMH used to confirm the practical effect.

## Cache review contract

Every runtime cache must declare its exact key, the events that invalidate it, the maximum retained state, its owning lifecycle and thread, and the path that releases it after replacement, detachment, failure, and close.
Only derived immutable or owner-confined presentation data may be cached.
Bindings, inventory contents, server state, and other authoritative inputs remain outside the cache and publish an invalidation when their observable snapshot changes.
A cache whose key cannot represent every rendering input is rejected rather than repaired with periodic refresh, and a cache without a fixed current-state or explicit size bound is rejected rather than relying on expected usage.

Deterministic tests must prove identity reuse on a clean request, replacement after each relevant input changes, bounded retention across unrelated history, terminal release, and unchanged pixels, command order, input behavior, and semantics.
Benchmarks then measure whether the cache removes meaningful work and whether its transfer, hashing, synchronization, or allocation cost outweighs reuse.
The same distinction applies to the build: dependency and tool-derived intermediates may use content- or model-addressed caches, while loaded worlds, screenshots, parity receipts, generated documentation, and quality reports are current-revision evidence and are always recreated.

## Benchmark methodology

Run `./gradlew :quality:benchmarks:jmh` for average time and normalized allocation using the `gc` profiler.
The [benchmark build](../../quality/benchmarks/build.gradle.kts) owns iteration, fork, and output settings; [benchmark sources](../../quality/benchmarks/src/jmh/kotlin) own scenes and viewports.
Use the [performance testkit](performance-testkit.md) for shared collection, native host boundaries, work assertions, and evidence contracts instead of adding application-specific meters.

Compare these costs separately:

| Case | Measurement |
| --- | --- |
| Clean session frame | A primed retained session with no invalidation. |
| Clean timed frame | The same path with an explicit unchanged host timestamp. |
| Dirty session frame | Representative leaves invalidated across all retained phases. |
| Headless rasterization | Fresh pixel storage from detached commands after closing the source session. |
| Reactive scenarios | Publication, projection, shared/independent consumers, and list changes, with monitoring off and on. |

Reactive setup excludes initial subscription/layout but includes publication and snapshot allocation.
Enabled monitoring checkpoints every 64 invocations; checkpoint cost is included.
Lists alternate fixed immutable ranges while preserving their anchor.
Use [render monitoring](render-monitoring.md) counters alongside timing and allocation.
Reports under `quality/benchmarks/build/` are temporary and untracked.

## Why wall-clock time is not a hard CI gate

Microbenchmark time changes with processor model, power management, thermal state, operating-system scheduling, background load, JVM compilation decisions, and virtualized CI contention.
The single-fork configuration keeps a local run practical but does not make a fixed microsecond threshold portable across machines.
CI must therefore not fail solely because an average-time score crosses a fixed wall-clock boundary.
Reviewers should compare runs made on the same controlled host and investigate sustained regressions together with allocation and deterministic structural evidence.

## Deterministic structural gates

### Repeated sampled rows

Large vertically magnified sampled images reuse the immediately preceding output row when the nearest-sampled source row is unchanged.
An opaque source row with opaque tint is independent of destination pixels; every other row additionally requires exact equality of the original destination span before copying.
Changed destination pixels, source rows, clips, density and orientation retain the ordinary scalar composition path.
One input span is allocated lazily for destination-dependent reuse and is bounded by the current clipped row width; opaque reuse needs no span.
This scratch state belongs to one paint invocation, is never shared between commands or frames, and owns no image or native resource.
Constant translucent images use the same exact destination comparison, while opaque constant images retain their existing fill path.
The independent pixel reference covers transparent, translucent and opaque patterned rows, fractional sampling, flips, cutoffs and destination changes at both ends of a row.
The sampled and dense sampled JMH corpora measure this CPU fallback separately from native texture upload.

### Current-tree frame callbacks

State-cutoff capture, commit and explicit time delivery use capability lists in effective parent-first order.
The tree owner keys these lists by the current root identity and structural revision; ordinary phase invalidation preserves membership.
Reconciliation of children or modifiers, including keyed reordering and dynamic child materialization, changes the structural token.
Every cutoff is captured before the separate commit pass, and time-aware nodes still receive every supplied timestamp, including equal values.
Cleanup clears the borrowed root and both lists before lifecycle callbacks, and terminal failure or close releases their backing storage.
Only the current tree is retained; passive trees have empty lists and clean frames do not walk their nodes for these capabilities.
Capable lists use indexed access, avoiding per-frame iterator allocation without changing callback order.
Deterministic tests cover effective modifier order, identity reuse, replacement, reordering, invalidation and callback failure cleanup.
The shared stress workload measures idle fan-out separately from updates that actually change all consumers.

### Single-texel nine-slice tiling

Minecraft nine-slice painting retains the default tiled center mode and the public API.
A repeated source axis of exactly one texel spans the entire destination axis in one nearest-sampled command; a 1×1 center therefore emits one command.
Source axes wider than one texel retain their repeating pattern and clipped final tile.
This is command coalescing without an additional cache or a change to input, semantics, or scroll state.
The regression suite compares pixels across integer, reduced and fractional viewport scales, GUI densities, transparent and translucent texels and incomplete tiles, and checks retained clean-frame identity.
Downstream benchmarks must preserve their fixed input workload and loaded class/JAR provenance, compare repeated runs on the same Java and host, and distinguish headless CPU/raster costs from native extraction and GPU completion.

### Bounded repeating blit templates

Dense image-only local paint retains its original immutable commands and lazily compacts tile-aligned patterns for exact integer translations.
At most 1,048,576 input blits from one immutable source image are admitted, with at most 32 source-rectangle groups.
Every group must form a complete row-major rectangular grid; groups must also be disjoint and cover their common bounds before command order can change.
Grid validation uses sequential edges without per-tile division or a coverage array.
Clips, mixed images or primitives, overlaps, gaps, and invalid grids preserve the original commands.

Each admitted unstretched pattern uses one immutable template of at most 64 by 64 pixels, repeated with tile-aligned offsets and cropped final chunks.
Template admission also bounds its two pixel arrays against the removed command allocation; larger or stretched source groups retain their original blits.
Templates preserve unblended source ARGB, including transparent RGB, and use no source-sized snapshot or global cache.
The original commands remain the sampling oracle for all fractional, scaled, or reflected tree transforms.

The current local display list owns its templates and lazily creates them on the tree owner thread.
Repaint replaces the list, and terminal disposal releases it; clean frames reuse the same list without reevaluation or new template allocation.
Tests cover tile phase and cropped chunks at full HD, source replacement, clean-frame reuse, integer and fractional viewports, density, clips, semantics, input, and scroll position.

### Opaque portable paint

An opaque source-over rectangle overwrites its covered physical pixel rows without per-texel alpha blending.
Opaque integer blit samples also use the exact source color without the general alpha arithmetic.
A nonzero-alpha source over a transparent destination is its exact straight-ARGB value, and a zero-alpha source preserves a nonzero-alpha destination; zero-alpha output remains transparent black.
Unscaled integer blits with matching source and destination extents use direct source offsets and physical row indices after clipping, without nearest-coordinate division.
When the first command is an unscaled, unclipped whole-viewport image blit, its detached pixel copy becomes the output storage directly.
Zero-alpha pixels normalize to transparent black as in source-over onto an empty output; source ownership and pixels remain unchanged, and no second image-sized buffer is retained.
Straight-ARGB blend numerators are bounded below 16,613,888, so integer arithmetic preserves the exact equations without Long division; coordinate calculations retain their overflow checks.
An integer or physical-pixel blit whose source rectangle is 1×1 delegates to the existing fill path, reading its immutable source color once instead of resampling it for every covered pixel.
This equivalence retains source-over blending for transparent and translucent texels and uses the same clipped physical coverage.
Fractional sampled images also overwrite with the source color when both the sampled pixel and tint are opaque and the tint leaves RGB unchanged.
An opaque white source pixel with an opaque tint similarly overwrites with the exact tint color, including colored bitmap glyphs.
Fractional clipping, nearest coordinate mapping, alpha cutoff, and non-identity tint continue through their existing paths; this does not broaden direct native eligibility or remove portable fallback uploads.
The headless rasterizer resolves the viewport and nested physical clip before each row overwrite, including fractional clips that cut through scaled logical texels.
Translucent fills use the same clipped physical row coverage and preserve straight-ARGB half-up rounding for every command.
Within one fill, a destination matching its first covered pixel reuses that pixel's exact blend result; other destination colors still blend independently.
For covered areas from 4,096 to 262,143 physical pixels, a command-local 64-entry direct-mapped table additionally reuses exact destination ARGB values.
Its constant source is fixed by the invocation, collisions check the complete destination value before reuse, and a collision recomputes the exact blend.
Two 64-entry Int arrays and a validity mask expire with the command and retain no image or session history; smaller and larger scalar fills keep only the two local scalar values.
An exact equality check on every covered palette result can restore the invocation's uniform state only after complete physical-viewport coverage.
Partial coverage never establishes this state; subsequent ordered fills retain each intermediate rounding and materialize before reading primitives.
This removes repeated arithmetic over uniform surfaces without combining layers or changing intermediate rounding, including transparent colors and fractional clips.
When the entire output remains uniform, full-viewport fills apply each ordered blend to one scalar and materialize the pixel array once.
Every partial fill or image materializes the pending color before reading or modifying pixels; a full opaque fill can restore the uniform state.
Clip changes alone do not read pixels, and only a clip covering the complete physical viewport permits a deferred fill.
This state belongs to one rasterization invocation, introduces no retained cache, and preserves intermediate per-layer rounding.
For a nonuniform output of at least 262,144 physical pixels, two or more consecutive full-viewport translucent fills use an exact channel lookup table keyed by the initial alpha and channel value.
At 1,048,576 physical pixels or more, a single full-area translucent fill uses the same bounded lookup instead of repeating its divisions at every pixel.
The table applies each original blend in order, retains 196,864 bytes only for that invocation, and maps each physical destination once; its source array is bounded by the current fill run.
During table construction, two scalar values reuse the remaining blends only when adjacent inputs produce exactly equal ARGB after the most opaque prefix.
Unequal prefix results retain every original rounded blend; this adds no retained state or lookup storage.
If every alpha and channel lookup entry is equal, the result is identical for every possible initial ARGB value and a row fill replaces per-pixel lookup.
Smaller single fills, partial clips, opaque fills and intervening primitives preserve the scalar or bounded palette paths.
These invocation-local paths create no retained cache and preserve command order, pixels, physical density, and the native portable-generation lifetime.
Its regression compares independent physical pixel-center coverage across density, empty/offscreen extents, nested integer/fractional clips, and translucent destination pixels.
Native measurements must continue to report actual rasterizations and uploads; reducing raster CPU work does not eliminate those operations.

### Fractional portable sampling

A sampled command whose entire immutable image is one texel reads its source once, preserving final-density coverage, orientation, alpha cutoff and continuous tint multiplication.
An exactly opaque multiplied source overwrites clipped rows with one calculated color.
A translucent source reuses its exact blend only for adjacent equal destination ARGB values; other pixels keep their original ordered Float composition.
This path does not round a translucent tint before blending or assume that a one-texel subrectangle within a larger image is constant.

For spans covering at least 4,096 physical pixels and four rows, nearest source X coordinates are calculated once in an invocation-local Int array bounded by the clipped physical width.
Each entry uses the original pixel-center Float expressions; there is no incremental coordinate recurrence or accumulated rounding error.
Smaller spans keep direct sampling, and source Y remains independently calculated for every row.
An opaque sampled source with an opaque RGB tint computes its exact normalized channel products without reading destination channels.
Within each command, scalar values remember the previous source ARGB, destination ARGB and exact result.
An opaque tint result depends only on source; a translucent result additionally requires complete destination equality, including RGB in transparent pixels.
Tint and discard cutoff stay fixed on this exclusively owned invocation, unequal keys recompute the original Float equations, and these values expire when the command returns.
The pixel traversal reuses an exactly matching source/destination pair before calling color composition.
This reuses repeated magnified texels without retaining any image reference.
For the same large-span admission, an opaque nonwhite RGB tint may use 768 exact normalized channel products, bounded to three 256-entry primitive tables.
Translucent composition lazily records rounded channel results by source alpha and source channel only while every admitted destination has the same complete ARGB value.
The first unequal destination permanently disables and releases these blend tables for the command; subsequent pixels use the original Float equations, so a heterogeneous destination never triggers repeated table clearing or assumes uniformity.
Each lazy alpha row contains 768 entries, with at most 16 rows (48 KiB of primitive values plus the 256 row references); other source alpha values use the original Float equations without allocating or replacing a row.
The common fixed-alpha image requires only one row, and alternating uncommon alpha values cannot churn table allocations.
The tables belong to one invocation, retain no source or destination image, preserve the original multiplication/division/rounding order, and expire before return.
Whole one-texel images and smaller spans keep their existing paths.
A zero-alpha tint preserves the destination without traversing its covered pixels.
These changes retain no images, frame history or mapping after the command and do not change native sampling eligibility or raster/upload counts.
Independent per-pixel regression covers both paths, nonuniform destination alpha, fractional and reduced extents, negative coordinates, flips, density, clips, tint and discard boundaries.

`DenseSampledRasterBenchmark` separately measures opaque and translucent patterned sources at 64, 256 and 1024 texels per axis over an opaque destination, with fixed 1920 by 1080 physical output, fractional nearest sampling and an opaque nonwhite tint.
Compiled JMH include filters must select exactly the registered method names before forks start; a similarly named supplemental benchmark cannot extend an existing corpus implicitly.
Run it through `:quality:benchmarks:jmhHistorical -Pstrata.performance.denseSampledRaster=true` with the shared default execution settings; it does not change the historical or existing sampled-raster matrices.
Source construction is outside measurement, while fresh raster ownership and complete ordered composition are inside each operation.
This corpus exposes sampling and tint/blending costs when adjacent source colors change frequently; it does not establish native GPU completion time.

The following gates encode the intended ownership and reuse behavior without depending on machine speed.
Existing exact headless-to-Fabric rendering parity tests remain required so caching cannot change pixels, command order, or native presentation.

### Clean frame reuse

An unchanged session with equal constraints and an unchanged whole-tree revision must return the same immutable core frame instance.
The public runtime bridge returns the session's read-only frame directly, including the same draw-command and semantics lists for that clean frame.
A content rebuild, changed constraints, retained invalidation, or invalidation raised during a frame must prevent stale reuse and produce a fresh snapshot before the next clean frame can be retained.
Failure and close paths must clear cached references so a session cannot keep a released tree or content graph alive.
The time-aware clean path must preserve the same complete frame snapshot when no time-aware node changes observable state.
Loading indicators and delayed tooltips additionally verify that timestamps inside one discrete animation or delay cell reuse the complete snapshot and that crossing the boundary creates exactly one fresh snapshot.

### Bounded raster texture cache

The Fabric presenter reuses the complete partitioned frame when the read-only draw-command list has referential identity, the logical viewport is equal, and the actual GUI scale is unchanged.
When a mixed portable-and-platform display list changes, each matched portable texture is reused when its immutable commands, logical extent, sampling origin, and GUI scale are equal; platform layers are still extracted natively every time.
Integer-only runs use localized commands and omit placement from this derived-pixel key.
Runs containing sampled images preserve their original absolute commands and raster origin, because translating Float pixel-center arithmetic can select a different texel, particularly at non-power-of-two GUI densities.
The internal region rasterizer allocates only the tight visible run extent while evaluating the original global pixel centers and destinations; no full-viewport scratch image or extra cache is introduced.
A changed layer count does not invalidate an unchanged prefix or suffix whose index shifts.
There is no historical content lookup or new application-facing cache.
Cached foreground paint callbacks do not make overlapping composition free: changing a lower command can invalidate the portable run containing the foreground, requiring its rasterization and upload again.
The full ordered commands and clips are replayed, so translucent overlays blend against the updated background and erased lower pixels do not persist.
See [render monitoring](render-monitoring.md#overlapping-content-and-overlays) for the distinction between callback counts and composition work and the corresponding pixel regressions.
Sampled glyph geometry is rasterized at physical resolution, so a scale change requires a new raster and texture even when the logical display list is identical.
Changed portable inputs reserve a complete replacement generation before any GUI output, sharing immutable resources for equal layers and allocating only changed layers, rather than modifying a texture that unconsumed GUI work may still reference.
Unchanged prefix and suffix layers retain their textures when insertion or removal shifts their indices; remaining equal layers may reuse the same previous index.
Matching scans the two lists linearly, does not hash source pixels, and retains no historical image cache beyond the current generation.
The screen retains only its current portable generation, and equivalent replacement commands replace old CPU input references without uploading identical pixels again.
Prepared display-list inputs also retain their sampled-image list and portable descriptions for the same command identity, viewport and GUI scale, avoiding reconstruction during static extraction.
Native texture availability is resolved inside every pinned borrow; resource reload or capacity exhaustion still selects the current portable fallback without retaining native handles in prepared CPU state.
Both adapter families use the same borrow-scoped fallback classification and counts; unavailable supported images count as capacity fallback, while unsupported images count as ineligible fallback.
Detachment, a zero-sized viewport, and terminal screen cleanup immediately clear every screen-owned texture, prepared-layer, and capture-receipt reference.
Already queued native resources move to the screen-independent device owner and release only after their initialization and actual GUI-consumption fences complete.
The complete prepared texture list is pinned across ordered submission, including intermediate legacy GUI flushes; reentrant screen close cannot free a later overlay or repopulate a closed screen's cache afterward.
The separate portable pool reserves at most three generation sets per stable presenter and 64 per device before allocation, with each set bounded by the exact layer extents of one prepared portable list.
Each generation owns independent initialization and GUI-consumption fences and extraction pins, including for shared layers.
After these settle, retirement drops that generation's resource references; shared storage remains owned by the remaining generations without retaining the old generation or its CPU inputs.
The last retired reference retains its permit through physical destruction, including partial allocation and Vulkan's deferred destruction queue.
These permits remain independent of Canvas's native target-set budget; sharing does not increase the reserved extent or generation limits.
Portable pool exhaustion fails before GUI output; it never attaches stale pixels to new portable commands or needs another permit to close an existing generation.
Terminal cleanup submits as required and completes recorded work, closes both native Canvas and portable resources, drains native destruction, and checks physical acknowledgements in that order.
Pointer dispatch and inventory or skin refresh coalescing must still invalidate the frame path when observable presentation state changes.
Loaded-client GameTests read render-work counters from the real Fabric screen and require an unchanged display list to perform no repartition, portable rasterization, or texture upload.
They also require equivalent replacement portable layers to reuse their raster textures, inspect detached presenters for absent current texture generations and prepared-frame references, and wait for actual retirement before checking native destruction.
The common portable-lifetime tests exercise incomplete initialization, pinned close, repeated queue consumption, arbitrarily delayed fences, both capacity bounds, and physical destruction acknowledgement.
Layer-sharing regressions additionally exercise hundreds of partial replacements, both retirement orders, changed extents, owner isolation, partial failure, quarantined initialization, and terminal cleanup.
The loaded common suite changes one opaque region across a native Canvas barrier at GUI scales one through four, requires exactly one rasterization and upload, preserves the unchanged texture identity, and checks retained opaque, translucent, and transparent pattern texels against literal native screenshot pixels.
It also inserts and removes a leading native/portable pair, preserves the shifted suffix texture, and checks the exact number of new uploads.
Legacy OpenGL correctness windows temporarily remove their decorations to preserve full-monitor framebuffer extents on Windows and restore the previous size, GUI scale, and decorations during cleanup.

### Direct sampled-image texture cache

Fabric presenters accelerate eligible portable SampledImage commands without changing the core or Headless command representation.
The cache key is physical device generation plus DrawImage referential identity; source and destination rectangles, clip, GUI scale, overlay state, and RuntimeUiFrame identity are not keys.
Changing destinations may therefore move every draw without image-pixel rasterization or upload, and changes to independently positioned overlays cannot invalidate cached image textures.
A different immutable DrawImage uploads only that identity even when it replaces one region inside a larger logical image.

Each screen owner retains at most 256 identities and 64 MiB of RGBA8 payload.
Active, initializing, retired, quarantined, and physically destroying entries share a device-wide bound of 512 identities and 128 MiB.
Both bounds are reserved before native allocation.
The owner evicts only unpinned least-recently-used identities that are not requested by the current display list; device-capacity exhaustion uses the ordinary tight portable fallback without stale reuse or unbounded allocation.

Every selected entry is pinned before ordered GUI submission and marked pending immediately before its draw command is queued.
Screen release removes the source-image reference from its owner cache, while the device retains native storage through initialization and actual GUI-consumption fences.
Resource reload invalidates every derived entry.
After GUI queues are consumed or discarded, terminal shutdown stops acquisition, submits recorded work as required, completes it once, closes Canvas, portable-layer, and direct sampled-image resources, drains deferred native destruction, and requires physical acknowledgement before releasing entry and byte accounting.

The accelerated subset is normal orientation, white tint, zero alpha cutoff, a contained source rectangle, nearest sampling, and ordinary straight-alpha source-over pixels within the native texture limit.
Ordinary native quads require both sample axes to select the headless texel with a rounding margin; this check includes integer source rectangles, because normalized UV interpolation can also misselect an integer-source boundary.
Floating-UV adapters admit stable fractional sources with integer-aligned physical destination edges.
Preparation checks at most 4,096 samples per axis without reading source pixels; ambiguous, oversized, or unrepresentable native quad samples use exact lookup presentation when available, otherwise CPU region sampling.

Texture-view, sampler, and bind-group adapters can generate exact image pixels in one GPU offscreen pass when the device supports RGBA extents of at least 4,096 and each checked enclosing physical destination axis fits that bound.
The RenderPearl adapter samples the original source and exact index texture directly in one native GUI quad, preserving the host's GUI transform, scissor, vertex format and source-over blend through its textured GUI pipeline snippet.
It allocates no destination image or offscreen pass; the existing source and portable-generation fences retain both queued inputs.
Its packaged access widener exposes only the active extractor queue, scissor stack and textured GUI snippet; the artifact check requires that contract, and loaded parity validates submission using the production adapter.
The CPU constructs only two axis-index rows and one output-extent row, using the same original-coordinate Float operation order and half-open physical coverage as Headless.
Each little-endian RGBA integer encodes a source index plus one, with zero for an uncovered physical pixel; the shader decodes it and uses `texelFetch` without normalized source interpolation or GPU source-coordinate arithmetic.
The index texture is at most 4,096 by three texels, including padding, and no source pixels are read while deriving it.
Offscreen passes write straight RGBA without blending, while direct GUI lookup applies the same ordered source-over composition as ordinary portable images.
Legacy OpenGL and direct-texture adapters retain exact CPU region sampling for commands outside their proven native quad subset.

Exact GPU output and its axis texture, or the direct GUI index texture alone, belong to the existing current portable generation, with no additional history, identity lookup, or cache.
One prepared frame admits at most 256 exact outputs and 64 MiB of output-plus-lookup RGBA storage before deriving metadata or allocating native resources; exhaustion selects exact CPU fallback and is counted as capacity fallback.
Their key includes commands, original sampling origin, logical extent, GUI density, and presentation mode.
The generation reserves a conservative rectangle covering both allocations before acquiring either; direct GUI lookup keeps this upper bound even though it allocates only metadata.
Unchanged entries use the same prefix/suffix sharing rules as ordinary portable uploads.
Both native texture/view pairs, any fullscreen vertex buffer, and partial initialization stay owned until the existing initialization and GUI-consumption fences complete and physical destruction is acknowledged.
The source-image cache remains pinned through preparation and submission, and is marked queued before an offscreen pass or direct GUI quad reads its texture.
Static frames reuse output and lookup storage; changed geometry may upload bounded axis metadata while preserving the immutable source-image upload.
Direct GUI lookup never adds a resampling draw; offscreen adapters additionally regenerate their destination pixels on the GPU.
Moved exact outputs reuse native storage only when the same immutable source, physical extent, and every encoded axis selection and coverage value remain equal.
This comparison reads bounded current-frame index metadata, never source pixels; translations with different original-coordinate Float sampling still invalidate the output.
Moved CPU fallback runs may also reuse storage after comparing every ordered primitive's relative integer geometry and half-open physical coverage, immutable source identity, tint, cutoff and orientation.
Nonconstant sampled images additionally compare their original-coordinate texel selection, with at most 4,096 physical pixels per axis and an 8,192-index proof budget for the complete run.
The budget charges only the covered destination rows and columns that the proof actually scans, so many small glyphs do not each consume the entire enclosing image extent.
Integer clip and fill edges are compared after exact clamping to the current image extent, including unchanged-origin runs whose invisible outer edges differ.
Blit destinations retain their original integer sampling anchor; clipping never changes source selection or ordering.
Preparation omits fills and integer blits proven invisible under the current viewport and integer clip bounds, including commands in mixed original-coordinate runs.
Their color or source updates cannot invalidate a visible footer or neighboring text; nested fractional clips stay intact, and visible sampling anchors and direct-image barriers remain unchanged.
Constant one-pixel sources need only equal coverage because every contained source coordinate selects their sole texel; no source pixel is read by the proof.
Exhausted proofs retain the exact CPU raster path, and rasterization never translates the original Float coordinates.
The loaded parity scene covers integer, quarter, eighth and decimal crops on both axes at GUI scales one through four, including exact texel boundaries and the original-coordinate translation regression.
Fractional clips intersecting that subset are also submitted directly when their half-open physical pixel-center coverage can be expressed by an integer GUI scissor at the current final density.
The presenter intersects the active clips, resolves each edge with `ceil(edge * density - 0.5)`, and admits the resulting range only when every physical edge is aligned to an integer GUI coordinate.
At density one this admits arbitrary fractional clip edges; at higher densities partial logical cells retain the portable fallback unless an inner clip or the image's visible extent makes the fractional boundary irrelevant.
Destination edges coincident with a physical pixel center use exact lookup or CPU region sampling because ordinary native quad-edge ownership can omit a headless sample.
No vertex bias conceals the difference.
The loaded scene compares full-frame patterned and translucent pixels and strictly checks both ordered GUI image draws and the additional GPU resampling passes.
Source identity, the original floating destination, display-list ordering, source-cache limits, and GPU retirement remain unchanged.
Unsupported sampled images wholly outside the logical viewport or the intersected active clip envelopes are omitted from portable runs' derived-pixel inputs, so changing an invisible image cannot invalidate an otherwise equal visible run.
Direct-image barriers retain their exact display-list positions even when the image is clipped away; adjacent portable runs are not merged into a larger raster/upload extent.
Fractional clip envelopes are conservative; partially covered images retain their original destinations and the existing physical pixel-center checks.
Other command shapes retain exact output through a portable layer bounded to their visible command run rather than the complete viewport.
Presentation counters distinguish direct hit, miss, upload, draw, eviction, ineligible and capacity fallback, retained entries and bytes, and ordinary portable rasterization and upload.
Sampled-image draws include GPU offscreen resampling passes; ordinary texture uploads include axis metadata writes, while sampled-image uploads count original immutable source pixels.
CPU rasterization counters never count a GPU resample as CPU work, and measurements do not infer GPU completion time from render-thread extraction time.
Both portable and direct-image uploads copy immutable ARGB source pixels into newly allocated NativeImage storage in one checked sequential write, preserving the native packed ABGR representation without an intermediate pixel array.
The allocation address is borrowed only for the synchronous render-thread copy; the existing generation or sampled-image owner retains the NativeImage and GPU storage through upload and consumption fences.
Copy preflight rejects a closed allocation, mismatched extents, non-RGBA format and overflowing address arithmetic before writing.
After warm-up, stable image identities under destination or clip changes must report zero image uploads and zero sampled-image portable rasterizations.

### Minecraft resource-image resolution identity

Each Minecraft UI host owns one resource-image resolver shared by its initial component context and every retained evaluator used for deferred content such as `VirtualList` rows.
The first `ImageSource.Resource` request for a structural `ResourceId` calls the host platform, while every equal admitted identifier used by `Image`, stretched or tiled image backgrounds, and nine-slice image backgrounds in that host receives the exact same immutable `DrawImage` identity.
`ImageSource.Pixels` bypasses this lookup, different identifiers remain independent, and a failed platform resolution publishes no entry so a later request retries normally.

The cache contains only derived immutable presentation snapshots and is keyed solely by `ResourceId`.
It admits at most 512 identifiers and 128 MiB of straight-RGBA8 payload, charging each result as width multiplied by height multiplied by four bytes.
Admission is append-only: entries are never evicted, while a successful result that would exceed either limit is returned without retention and may therefore resolve again on a later request.
Admitted entries remain until terminal release so deferred row churn cannot change their identity.
Resolution, cache access, and release are confined to the host owner thread.
Each callback-lifetime context drops its resolver reference when evaluation completes or fails, while retained evaluators share the host resolver without owning an independent cache.
Terminal close or failure first releases the retained tree, then invalidates the resolver and clears every entry before closing version services.
Resolution is lazy: the first use of an identifier observes the resource-pack stack active at that call, including after a reload, while an identifier admitted earlier stays pinned to its existing pixels for the host lifetime.
A new host always performs its own platform resolution and can therefore observe replacement pixels, although a platform is allowed to return the same immutable image identity from that new resolution.

Common JVM tests require identity reuse across initial and far-jumped retained evaluation, mixed image components and backgrounds, independent identifiers, both admission limits, pixel bypass, failure retry without failed-entry retention, owner-thread rejection, terminal invalidation after close and construction failure, and a new platform resolution per host with replacement pixels.
The loaded Fabric gate additionally materializes repeated identifiers through deferred `VirtualList` rows, inspects the real display list and native direct sampled-image counters, and requires one miss and upload per host.
The concrete Fabric bridge decodes a detached snapshot on every platform resolution, so its second-host check also requires a fresh image identity without changing rendered pixels.

### Tiled-image working-set cache

Each retained TiledImage attachment keys its derived presentation state by source identity and TiledImageTileId.
The source identity fixes bounds, level geometry, and revision meaning; replacing it invalidates the complete working set before any replacement subscription opens.
Pan, zoom, viewport, fit, and cache-policy changes replace only tile placement and the required identifier set, while a stable identifier retains its observation and committed immutable DrawImage.
Independently positioned overlays do not participate in this key and cannot invalidate tile observations or images.

The attachment reserves both one entry and the level's full straight-RGBA8 byte cost before subscribing.
Its current set contains the selected level's visible tiles plus its configured overscan margin and only visible tiles from every coarser fallback level.
The configured entry and byte maxima include empty, initializing, committed, pending, and frame-captured tile states.
If the preferred set does not fit, planning tries coarser selected levels without mutating the current subscriptions; failure of the coarsest set occurs before partial installation.
Panning replaces tiles beyond the current margin instead of retaining visited map regions.

Planning, subscription establishment, reconciliation, and release run on the retained tree's owner thread.
StateSource callbacks may enqueue from any thread, retain only the newest pending revision for that tile, and commit through the session's shared frame cutoff.
Leaving the working set, source replacement, detachment, close, and failed subscription establishment clear attachment references and close every observation without closing the externally owned source or state history.
Cleanup attempts all removed observations and preserves later failures as suppressed exceptions.

Deterministic tests cover clean identity reuse, movement within and across the overscan boundary, marker-only updates, preferred and fallback LOD budgeting, source replacement, callback and subscribe races, invalid tile rejection, detach and terminal release, and exact range planning above the double integer precision boundary for positive, negative, and non-power-of-two grids.
Headless and loaded Fabric parity require the same pixels and row-major coarse-to-fine command order, while native sampled-image counters require stable tile images to avoid rerasterization and upload during pan, zoom, and marker movement.

### Canvas source and target retention

The CPU Canvas binding is keyed by source identity and its attachment, accepts only monotonically newer StateRevision values, and retains one committed immutable image plus the newest pending image.
The global frame transaction temporarily holds one captured observation between capture and commit; callbacks after that capture remain pending until the next frame.
Equal source-image identity reuses a clean frame even when the revision advances; a different immutable image object replaces the cached paint input even when its pixels compare equal, so obsolete storage is not retained.
Source replacement, session detachment, disposal, and failure first sever binding references and then close observation handles; the external StateSource remains application-owned.
Tests cover caller-array independence, subscribe/initial races, stale revisions, cross-binding cutoff, untimed updates, image-size replacement, bounded pending state, clean frame identity, and exact Headless pixels.

NativeCanvasDevice is owned by one physical device's render thread, independently of every screen.
Its attachment index is keyed by immutable device and attachment identities, while reusable targets require the stable CanvasId, producer generation, exact physical extent, and completed allocation, capture, and GUI use.
Native Canvas requests in core commands and retained RuntimeUiFrame instances contain only scalar device and attachment identifiers; separate prepared tokens also identify the committed generation.
Portable commands and explicit capture snapshots may retain immutable CPU images, but no command retains a target, renderer, source callback, or host.
The current batch is bounded to one outstanding native presentation; the next presentation cannot overtake an unconsumed or uncancelled batch.
Resource reload discards committed generations and retires old producers; new instances open lazily when a target permit is available.

At most three target sets may exist for one stable CanvasId across source replacement and detach/reattach, and at most 64 active, retired, partially allocated, or quarantined sets may exist on one device.
A permit is reserved before allocation and is held until physical destruction succeeds; incomplete allocation rollback transfers its partial target with NativeCanvasAllocationFailure instead of returning the permit.
Asynchronous native retirement is still incomplete rollback even when its `close()` request returns successfully.
No close path allocates a replacement target or requires a free slot.
When capacity is unavailable, preparation skips the producer and reuses the exact last committed token and snapshot without assigning a newer generation to older pixels; a never-committed canvas remains transparent.
Retired targets remain counted for arbitrarily many frames while their fences are unsignalled or their physical destruction is unacknowledged, and render-thread polling never waits for queued but unsubmitted GUI work.

Source leases and temporary sampling resources release after capture completion, while targets and producer-owned resources survive their last GUI completion.
Every target allocation is fenced separately, including presentations whose provider returns no capture, so backend initialization commands cannot outlive their resources.
The frame, screen, and capture receipt do not own these native lifetimes.
Custom renderer factories are evaluated only inside a reserved target's capture callback, so even their initialization uploads are protected by the capture fence; attachment creation alone performs no owned GPU work.
GPU-fence creation or GUI-consumption failure quarantines affected targets, and device shutdown first discards GUI queues and submits as required before completing recorded GPU work and releasing resources.
On a backend with one host-owned command encoder, ordinary Canvas uploads and fences are recorded in the current host submission; only terminal completion may submit explicitly after every GUI queue has been consumed or discarded.
Failed target destruction retains ownership and its permit; terminal cleanup may retry only unreleased per-resource work, preserving the earlier failure if retry also fails.
Successfully requested asynchronous destruction is polled without repeating `close()`; the 26.2 and 26.3 Vulkan adapters observe physical texture and view destruction rather than relying on a fixed number of delayed frames.
After submitted work completes, terminal cleanup requests all retirements, drains the backend destruction queue, and requires every target's physical acknowledgment before returning its permit.
Repeated failed shutdown cannot report success.
Once terminal shutdown starts, ordinary polling performs no further native work, including when device completion failed and old fences later signal.
Those failed terminal resources remain quarantined until external device teardown rather than being released by a late frame callback.
The fixed orientation-specific sampling programs are device-owned, keyed only by native API family and row orientation, bounded to two variants, and released only after terminal GPU completion.

Deterministic protocol tests independently control capture and GUI fences and cover long unsignalled histories, resize, source replacement, reattachment, shared sources, cancellation, partial producer/GUI/cleanup failures, partial allocation rollback, the three/64 limits, rapid key churn, and retained old frames.
Loaded native tests must separately inspect known GPU texels and a custom offscreen renderer before comparing the same-generation Headless capture; agreement between two snapshots alone is not native parity evidence.
Backend-specific loaded results, especially OpenGL versus Vulkan, are recorded separately and must not be inferred from JVM protocol tests.
The 26.2 and 26.3 Vulkan Canvas resize gates keep the native surface fixed while varying the logical viewport, framebuffer, and owned Canvas targets; these scenes establish target retention, and separate full-suite runs establish complete acceptance.

### Player-head filtered-image cache

Each retained `PlayerHead` node keeps at most the current bilinearly resampled face and hat pair, keyed by source `DrawImage` identity and requested logical size.
Sizes divisible by eight bypass this cache and paint the original 8 by 8 skin regions with nearest sampling, while other accepted sizes clamp every bilinear sample to its face or hat region.
A filtered layer is limited to 1,024 by 1,024 pixels, bounding the two derived straight-ARGB snapshots below 8 MiB; larger integer-scale heads still reuse the original skin without derived storage.
Skin or size replacement, asynchronous snapshot replacement, detachment, and disposal release the cached pair.
Deterministic runtime tests cover the nearest path, region-clamped bilinear pixels, premultiplied alpha behavior, stable derived-image reuse, invalid-size rejection, and synchronous and asynchronous ownership.

### Resource-font caches

Each common host owns one font engine for its immutable profile snapshot and captured font options.
Decoded bitmap sheets use detached resource identity; scanned cells use that resource, grid dimensions, and cell index, retaining only the cell's current height/ascent result.
TrueType faces and glyphs use resource identity and exact size, oversampling, and shift settings, with provider-specific skips checked before lookup.
Other glyph results use snapshot-local provider identity and Unicode scalar keys.
The access-ordered raster cache has a combined default limit of 4,096 entries and 16 MiB of retained pixel payload; oversized values bypass retention, and a separate default 8 MiB input ceiling bounds bitmap sheets in every cache mode.
Native faces use an independent access-ordered cache limited to 16 entries and combined encoded input no larger than the snapshot's `maxAssetBytes`, 32 MiB by default.
Eviction removes accounting and closes the previous face before opening its replacement.
Snapshot loading separately bounds distinct TrueType resource-and-settings descriptors and their weighted encoded bytes, defaulting to 256 descriptors and 128 MiB; exact duplicate declarations and different skip lists share that descriptor charge.
Successful preflight descriptors and detached initialization failures remain bounded by the snapshot independently of raster and face eviction, preventing repeated preflight decoding or opening of duplicate declarations.
If a custom face returns an image exceeding the captured limits, the engine closes and permanently disables that face key; a detached typed failure remains even with raster caching disabled, after churn, or for another scalar.
Provider and font initialization status is bounded by the snapshot's provider graph; unknown font identifiers and historical text strings are never retained as cache entries.

Changing resource packs, provider filters, or language direction requires a new snapshot and host; existing host engines never mutate their snapshot and never share native faces.
All host-cache and native access is confined to the host's owner thread.
Terminal cleanup clears cache and snapshot references and closes all faces and the backend, including when an initialization, rasterization, or cleanup step fails.
Detachment preserves common host ownership for reattachment but releases Fabric presentation textures independently.

Tests compare enabled and disabled raster caches, assert entry and payload bounds, churn face keys and weighted input limits, exercise duplicate-provider preflight and permanent poisoned-face rejection, isolate engines sharing one snapshot, and verify terminal counters and backend release after failures.
The raster byte bound covers cache-owned pixels; the face byte bound covers retained encoded native inputs, not arbitrary native bookkeeping or total heap usage.
Neither includes glyphs in current caller-owned runs or immutable source-file bytes in a shared snapshot.
Native font acceptance separately compares standard Minecraft rendering at each tested GUI scale; sharing the portable rasterizer cannot by itself establish native equality.

### Visible glyph submission

A current text run retains its immutable positioned glyphs and at most three additional float extrema for horizontal candidate selection.
It does not retain past clip rectangles, scroll positions, runs, or lookup callbacks.
When all advances are positive and finite and cursor positions increase strictly, painting uses binary searches over the existing positions before testing the actual foreground and shadow quads against the local viewport.
The candidate range includes the run's largest horizontal bearing and shadow overhang; unusually wide overhang can enlarge that range.
Zero, negative, non-finite, or rounded-to-equal advances, and non-finite horizontal metrics, conservatively fall back to scanning the current run.

Sampled candidates preserve the painter's float addition order: origin, positioned cursor, bearing, then shadow offset.
They do not pre-add bearings to large cursor values, round the integer clip to float, or crop source and destination rectangles before sampling.
Legacy bitmap bounds use exact long or double arithmetic, including positions above float's exact integer range.
Separate raw vertical extrema ignore horizontal collapse and prepared-text rejection at origin zero.
Current-line aggregates evaluate monotone top and bottom bounds at each candidate's actual origin before per-line vertical intersection, preserving large-coordinate float rounding without fixed padding or historical range storage.
An overflowing upper envelope stays infinite and conservative; it never excludes a potentially visible line.
The caller still owns the actual clip, and the selected native shadow order remains unchanged.
Prepared-text bounds return early only after both accumulated raw axes are strictly ordered; this cannot turn a later native rejection into acceptance and preserves the existing NaN comparison behavior.

Tests require bounded candidate visits and command counts at the beginning, middle, and end of a 32,767-glyph forward run, compare clipped full painting with visible painting pixel-for-pixel at scales one through three, and cover signed advances, overhang, shadows, large coordinates, empty clips, and unchanged retention across many viewport replacements.
The returned diagnostic count includes prepared-bounds visits and a second candidate visit when a target draws all shadows before the foreground pass; it excludes the logarithmic binary-search comparisons.
Run construction and exceptional signed-metric fallback remain proportional to the current text; this is a submission bound, not an incremental text-editing or universal constant-time guarantee.

### Fabric profile reuse between screen opens

The installed Fabric presenter retains at most one complete immutable UI profile for ordinary `UiDefinition.open()` calls.
Its key is the active resource-manager identity, the current native resource generation, the complete compiler-selected font compatibility value, and all captured font-selection and language-direction options.
GUI scale is deliberately absent: profile pixels, resource bytes, and logical font data do not depend on presentation density; the separate prepared-layer cache includes density when rasterizing.
The public `extractMinecraftUiProfile()` factory still reads and returns a fresh snapshot on every explicit call.

The cache stores only derived immutable GUI pixels and one detached font snapshot.
Every host still owns its own font engine, raster caches, and native faces.
Replacing the key drops the previous cache entry before extraction, and a failed extraction publishes nothing.
One atomic state captures a monotonic generation counter, the optional current entry, and the terminal flag.
Both the initial claim and completed publication compare that captured state, so invalidation while empty also rejects work which has not claimed its pending entry yet.
A separate loading flag remains set until the active extraction unwinds, including after invalidation, and rejects reentry into a canceled load.
The internal extraction factory receives the exact captured manager, capabilities, and options from the key rather than reading mutable global inputs again.
It never retains the extraction callback, old resource generations, or historical option selections.

Required client Mixins invalidate the matching manager at the start of native `createReload` and `close` on every supported target.
The exact descriptors are compiled and remapped with that target; unrelated integrated-server resource managers cannot invalidate the client entry.
Reload listeners cannot provide these guarantees: native reload closes old packs and constructs its next resource view before dispatching listener preparation, so a failure there has no prepare/apply callback; native close does not notify those listeners either.
Invalidation is thread-safe and drops references only, without accessing native font state or disposing profiles still owned by open hosts.
Repeated reloads replace one empty generation token and retain no manager, profile, callback, or historical generation collection.
Closing the actual client manager permanently marks the cache terminal and prevents later normal opens; closing an unrelated manager cannot terminate it.
Open hosts continue with their immutable old snapshot until they close, after which the adapter does not retain it.

Pure tests require single extraction for equal keys, exact key forwarding to extraction, independent option and capability invalidation, identity-based manager isolation, failure retry, reentrancy rejection even after canceled loading, cross-thread fencing before the pending claim and during extraction, terminal rejection, and one-entry or empty retention during reload storms.
The loaded-client probe exercises real public screen opens and a normal Minecraft resource reload, checks unchanged old-host pixels, forces an owned native manager to fail before any listener prepares, and verifies that closing an owned manager evicts its cached value without terminating the active client's cache.
Pure tests cover permanent active-client shutdown, while native lifecycle probes never destroy the game's active resource manager.
Published-artifact checks require the exact client-only Mixin configuration once across outer and nested jars, and loaded probes independently require one effective classpath configuration, a compatible Mixin runtime, and a matching startup log without Mixin errors.
Its `profile-cache.properties` receipt records fresh extraction and warm screen-open timings without a wall-clock pass threshold, the unique primitive-array payload of a snapshot, and actual weak-reference collection of retired snapshots while the closed host object remains reachable.
Primitive-array bytes exclude object headers, temporary decoding allocations, and host-owned native memory; they are not a complete heap-size estimate.

### Virtual-list retention

A virtual list materializes only the visible rows plus its bounded overscan rows, caches only the current materialized range, and reuses that range while its inputs and viewport remain clean.
Jumping across a large indexed source replaces the current range instead of retaining visited ranges.
Prepending data preserves the visible stable key without materializing the intervening items.

### Observed-region retention

Observe retains its current child descriptions and committed value tuple, rebuilding only for a changed tuple or parent callback.
Repeated geometry passes reuse those descriptions; compatible child nodes preserve editing and viewport state.
Direct source components delegate to the same region mechanism and never open another screen.
The source registry indexes consumers and derived dependents by source identity, so one changed source does not allocate value tuples for unrelated consumers.
Each retained map descriptor stores one current projected result; changed inputs recompute the result, while equal outputs stop downstream propagation.
Current immutable dynamic-child lists are keyed by list identity and replaced after actual content evaluation; identical cached lists skip sibling validation and reconciliation.
Pending content and measure dirtiness are separate: only the actual child difference propagates layout work, and paint-only progress updates do not remeasure ancestors.
Parent-data delegation caches only the immutable node capability on its retained entry; layout still reads current parent data on every required pass, and replacing the node replaces this capability reference.
Projection edges, consumer tuples, cached child descriptions, and monitoring callbacks belong to the current tree and are released with their last owner or terminal cleanup.
The tree-owned registry shares one source subscription by reference identity and is bounded by the sources referenced during the current frame or standalone tree operation.
Bindings whose last owner disappears remain available for same-operation readmission, preserving the committed cutoff and pending notifications; still-unused bindings are released before the operation returns.
An ordered pending-release set is empty on stable frames, so frame completion does not add a full registry scan when no source was removed.
Its revision binding retains committed, pending, and captured values plus the subscription carrier; terminal release drops registry references and closes each subscription once.
Core tests count content evaluations and subscriptions for nested, repeated-argument, equal-value, background-burst, removal, replacement, and closed-tree cases.
After initial dynamic materialization settles, unchanged frames retain their immutable frame cache and perform no additional component, measure, layout, paint, or semantics work.
The bounded regression scenario holds 128 independent regions, runs 100 unchanged frames, and requires one sibling content evaluation and one primitive update when one source changes.
A changed label may legitimately invalidate ancestor measurement.

### Detached declaration lists

The core declaration cutoff creates owned child and modifier lists after reconciliation and reads each live projection again.
Its internal snapshot constructor takes exclusive ownership of these fresh lists instead of copying them a second time.
Empty and singleton snapshots use their exact list forms without intermediate map buffers.
Later reconciliation and terminal close cannot mutate lists in an earlier snapshot; this does not cache projections or suppress endpoint encoding.
The retained remote corpus measures this path separately for idle projection, one-source updates, and complete lifecycle operations.

### Remote topology allocation

Tree validation uses one declaration identity set for component and modifier uniqueness, a component visit count for connectivity, and parallel primitive identity/depth stacks bounded by the admitted node count.
It preserves depth, cycle, shared-child, absent-child, modifier-collision, and aggregate admission checks before returning a detached immutable tree.
Empty remote node lists reuse immutable empty lists; populated lists still defensively copy caller inputs.
Patch duplicate validation uses one identity set; removal uses the standard key-set difference, preserving boxed identity reuse during membership checks.
Message field arrays are exposed only to the immediately defensive projection sequence constructor, avoiding an extra intermediate list copy.
These changes do not skip live projections, cache authoritative state, or change wire bytes.
Resource identifiers validate the same ASCII namespace and nonempty path-segment grammar without per-identifier regex matchers, rejecting dot and parent segments as before.
ASCII wire text packs into the invocation-owned bounded output buffer after one complete growth check and decodes through the JDK string constructor after verifying every byte is ASCII.
It preserves the standard stream byte count and creates no temporary encoder buffer, per-field copy or retained scratch storage.
Unicode and malformed sequences retain strict UTF-8 validation; text, byte, depth, aggregate and deadline limits remain unchanged.
Decoding uses one invocation-local JDK ByteBuffer with explicit big-endian order, eliminating synchronized per-byte input dispatch while checking remaining bytes before allocation.
Truncated primitives still fail as malformed values, and trailing bytes remain rejected.
Primitive output uses the standard DataOutputStream format over exclusively owned bounded storage, with checked bulk copies and no per-byte monitor acquisition.

### Player-skin lifecycle

The asynchronous skin completion path must retain only its detached lifecycle target and must not capture the screen, platform bridge, or binding owner after close.
Close must atomically reject late publication, drop a queued completion, clear a committed ready-image snapshot, clear its observer, and remain idempotent.
Owner-thread draining must transfer an accepted completion at most once, and a closed lifecycle must never accept another snapshot commit.

### Editable literal widths

TextField measures literal scalar ranges directly, without constructing positioned glyph runs or copying each candidate substring.
Resource-font metrics preserve forward floating-point addition and release-specific signed width rounding; compatibility glyphs preserve checked integer addition.
Nonnegative advances permit a scalar-boundary binary search when the native integer rounding remains monotone, including positive infinity and overflow with saturating rounding.
Release-specific rounding that wraps after overflow keeps its original scalar order.
Ordinary integral advances retain the fixed-unit two-scan search when their combined absolute magnitude is at most 2^24, avoiding the generalized floating-point proof on this common path.
Other finite advances permit an exact two-scan suffix search when their exact prefix range contains at most 2^24 common power-of-two units and stays within the finite Float range.
This includes integral, fractional, negative, subnormal and large cancelling advances while proving that every suffix intermediate uses exact forward Float addition.
Other metrics preserve exact forward accumulation while fetching each scalar advance only once.
Attained minimum and maximum Float widths summarize the exact forward accumulation of all candidate suffix starts through each prefix.
A fixed Float addition preserves the order of non-NaN candidates; an endpoint becoming NaN witnesses an actual permanently fitting suffix, because both native rounding modes map NaN to zero.
At the final scalar, the minimum detects fitting widths in the monotone region and the maximum detects the legacy positive overflow region that wraps to a fitting negative width.
Existence of a fitting suffix among starts up to a selected boundary is monotone even when individual signed widths are not.
Binary search over that existence predicate returns the same first fitting scalar as the original ordered search, using at most O(n log n) Float additions and one glyph lookup per scalar.
Prefix extrema let each query resume after its last admitted start; all-overwide values return after the initial scan.
Four primitive arrays belong to one call, require storage proportional to the supplied UTF-16 range, and retain no text, font owner or history after return.
Unrestricted prefix subtraction and reversed accumulation are not substitutes for exact native widths.
The visible endpoint accumulates widths once in forward scalar order and stops at the first prefix that exceeds the viewport, preserving the previous behavior even when later negative advances would fit again.
Pointer hit testing also accumulates rounded prefix widths once, applying each signed midpoint in its original order even for zero, negative or non-finite advances.
A deterministic uncached-font test requires exactly one glyph lookup per scalar for a 16,384-unit zero-width value, rather than using elapsed time as a threshold.
This adds no cache and preserves caret, composition, pointer midpoint and visible pixel behavior.
The separate stress corpus records initial ownership, clean frames and real updates for short and 16,384-unit fields through the shared testkit.

## Interpreting measurements

`OverlayRenderingBenchmark` separates retained command generation from full headless source-over composition with one changing opaque lower layer and 1, 16, or 64 immutable translucent foregrounds.
It runs at 320 by 180 and 1920 by 1080 physical pixels, with diagnostics disabled and enabled.
The command fixture still assembles the complete ordered display list; the composition fixture also allocates a complete output image and applies every ordered foreground blend, using uniform-surface scalar evaluation where exact.
These are different costs, and the headless timings are not native GPU frame-rate measurements.
The eligible nonuniform full-area fill run performs at most 65,536 rounded channel-state blends per layer plus one mapping per physical pixel; other nonuniform alpha composition still scales with its covered area and layers.
Do not recommend dense full-area translucent stacks for frequent updates without measuring their intended physical resolution and composition path.

`:quality:benchmarks:verifyOverlayRenderingWork` uses the same fixture for deterministic retention and pixel checks.
Each layer count runs 10,000 changes, requires exactly one lower paint and Observe evaluation per update, no measure/layout or node creation/disposal, one active source subscription, bounded node/display-list counts, and no subscription after close.
Every verification image is compared pixel-for-pixel with an independently calculated source-over color.
For a longer local soak, invoke `OverlayWorkEvidence` from the JMH jar with explicit update count, raster frame count, and viewport width; it keeps the same assertions and never accumulates frame history.
Record those counts and any elapsed-time measurements with the review evidence; no elapsed-time threshold gates CI.

Record the measured revision, configuration, and environment with each temporary report.
Compare wall-clock results only when host load and power conditions are controlled; normalized allocation and deterministic retention checks provide different evidence.
Historical measurements in Git history do not establish performance on the current revision.
Promote a verified architectural conclusion into this contract instead of accumulating host-specific result tables in it.
