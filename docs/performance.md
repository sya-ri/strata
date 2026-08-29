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

Run the complete suite from the repository root with `./gradlew :quality:benchmarks:jmh`.
The benchmark module uses JMH 1.37 in average-time mode with one worker thread, three one-second warmup iterations, five one-second measurement iterations, and one fork.
The built-in `gc` profiler records normalized allocation in bytes per operation alongside elapsed time in microseconds per operation.
The three logical viewports are `Compact` at 320 by 180, `Windowed` at 854 by 480, and `FullHd` at 1920 by 1080.
Every case uses the same public API-built scene containing a full-viewport background and 54 keyed paint-and-semantics leaves in a centered nine-column grid.

`cleanUiSessionFrame` primes one retained session before measurement and then requests another frame without invalidation.
`cleanTimedUiSessionFrame` advances that same clean scene with a stable explicit host timestamp before requesting the frame, matching the per-render call shape used by Minecraft without causing a time-dependent invalidation.
`dirtyUiSessionFrame` invalidates every representative leaf with all retained phases before requesting the measured frame.
`headlessRasterization` obtains a detached display list from a real retained frame, closes the temporary session, and measures creation of fresh headless pixel storage.

JMH writes structured output to `quality/benchmarks/build/reports/jmh/results.json`.
The report and every other file under `quality/benchmarks/build/` are temporary, untracked build outputs and must not be committed.

## Baseline before fixes

The baseline below was measured from commit `729da15` before the frame-reuse, bounded raster-texture cache, and player-skin lifecycle fixes.
Values are the JMH average-time score and `gc.alloc.rate.norm`, rounded to three decimal places and the nearest byte respectively.

| Benchmark | Viewport | Average time (µs/op) | Allocation (B/op) |
| --- | --- | ---: | ---: |
| Clean session frame | Compact | 7.795 | 20,056 |
| Clean session frame | Windowed | 6.762 | 20,056 |
| Clean session frame | FullHd | 7.487 | 20,056 |
| Dirty session frame | Compact | 14.288 | 58,648 |
| Dirty session frame | Windowed | 13.965 | 58,648 |
| Dirty session frame | FullHd | 14.086 | 58,648 |
| Headless rasterization | Compact | 514.090 | 230,796 |
| Headless rasterization | Windowed | 3,244.911 | 1,643,615 |
| Headless rasterization | FullHd | 17,392.278 | 8,298,495 |

The equal clean-frame allocation across viewport sizes showed that retained node phase caches were working while complete frame and bridge snapshots were still recreated.
The raster allocation scales primarily with the fresh physical pixel array required by the headless facade and is therefore expected to grow with viewport area.

## Why wall-clock time is not a hard CI gate

Microbenchmark time changes with processor model, power management, thermal state, operating-system scheduling, background load, JVM compilation decisions, and virtualized CI contention.
The single-fork configuration keeps a local run practical but does not make a fixed microsecond threshold portable across machines.
CI must therefore not fail solely because an average-time score crosses a fixed wall-clock boundary.
Reviewers should compare runs made on the same controlled host and investigate sustained regressions together with allocation and deterministic structural evidence.

## Deterministic structural gates

The following gates encode the intended ownership and reuse behavior without depending on machine speed.
Existing exact headless-to-Fabric rendering parity tests remain required so caching cannot change pixels, command order, or native presentation.

### Clean frame reuse

An unchanged session with equal constraints and an unchanged whole-tree revision must return the same immutable core frame instance.
The public runtime bridge must also return the same immutable bridge snapshot, draw-command list, and semantics list for that clean frame.
A content rebuild, changed constraints, retained invalidation, or invalidation raised during a frame must prevent stale reuse and produce a fresh snapshot before the next clean frame can be retained.
Failure and close paths must clear cached references so a session cannot keep a released tree or content graph alive.
The time-aware clean path must preserve the same complete frame snapshot when no time-aware node changes observable state.
Loading indicators and delayed tooltips additionally verify that timestamps inside one discrete animation or delay cell reuse the complete snapshot and that crossing the boundary creates exactly one fresh snapshot.

### Bounded raster texture cache

The Fabric presenter reuses the complete partitioned frame when the immutable draw-command list has referential identity, the logical viewport is equal, and the actual GUI scale is unchanged.
When a mixed portable-and-platform display list changes, portable textures may be reused only when the complete ordered list of localized immutable commands, image extents, viewport, and GUI scale is equal; platform layers are still extracted natively every time.
Sampled glyph geometry is rasterized at physical resolution, so a scale change requires a new raster and texture even when the logical display list is identical.
Changed portable inputs allocate a complete replacement generation before any GUI output, rather than modifying a texture that unconsumed GUI work may still reference.
The screen retains only its current portable generation, and equivalent replacement commands replace old CPU input references without uploading identical pixels again.
Detachment, a zero-sized viewport, and terminal screen cleanup immediately clear every screen-owned texture, prepared-layer, and capture-receipt reference.
Already queued native resources move to the screen-independent device owner and release only after their initialization and actual GUI-consumption fences complete.
The complete prepared texture list is pinned across ordered submission, including intermediate legacy GUI flushes; reentrant screen close cannot free a later overlay or repopulate a closed screen's cache afterward.
The separate portable pool reserves at most three generation sets per stable presenter and 64 per device before allocation, with each set bounded by the exact layer extents of one prepared portable list.
These permits are independent of Canvas's native target-set budget and remain held through physical destruction, including partial allocation and Vulkan's deferred destruction queue.
Portable pool exhaustion fails before GUI output; it never attaches stale pixels to new portable commands or needs another permit to close an existing generation.
Terminal cleanup submits as required and completes recorded work, closes both native Canvas and portable resources, drains native destruction, and checks physical acknowledgements in that order.
Pointer dispatch and inventory or skin refresh coalescing must still invalidate the frame path when observable presentation state changes.
Loaded-client GameTests read render-work counters from the real Fabric screen and require an unchanged display list to perform no repartition, portable rasterization, or texture upload.
They also require equivalent replacement portable layers to reuse their raster textures, inspect detached presenters for absent current texture generations and prepared-frame references, and wait for actual retirement before checking native destruction.
The common portable-lifetime tests exercise incomplete initialization, pinned close, repeated queue consumption, arbitrarily delayed fences, both capacity bounds, and physical destruction acknowledgement.

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
Terminal shutdown stops acquisition, completes submitted work once, closes Canvas, portable-layer, and direct sampled-image resources, drains deferred native destruction, and requires physical acknowledgement before releasing entry and byte accounting.

The required direct subset is normal orientation, white tint, zero alpha cutoff, an integer contained source rectangle, nearest sampling, and ordinary straight-alpha source-over pixels within the native texture limit.
Other command shapes retain exact output through a portable layer bounded to their visible command run rather than the complete viewport.
Presentation counters distinguish direct hit, miss, upload, draw, eviction, ineligible and capacity fallback, retained entries and bytes, and ordinary portable rasterization and upload.
After warm-up, stable image identities under destination or clip changes must report zero image uploads and zero sampled-image portable rasterizations.

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
Successfully requested asynchronous destruction is polled without repeating `close()`; the 26.2 Vulkan adapter observes physical texture and view destruction rather than relying on a fixed number of delayed frames.
After submitted work completes, terminal cleanup requests all retirements, drains the backend destruction queue, and requires every target's physical acknowledgment before returning its permit.
Repeated failed shutdown cannot report success.
Once terminal shutdown starts, ordinary polling performs no further native work, including when device completion failed and old fences later signal.
Those failed terminal resources remain quarantined until external device teardown rather than being released by a late frame callback.
The fixed orientation-specific sampling programs are device-owned, keyed only by native API family and row orientation, bounded to two variants, and released only after terminal GPU completion.

Deterministic protocol tests independently control capture and GUI fences and cover long unsignalled histories, resize, source replacement, reattachment, shared sources, cancellation, partial producer/GUI/cleanup failures, partial allocation rollback, the three/64 limits, rapid key churn, and retained old frames.
Loaded native tests must separately inspect known GPU texels and a custom offscreen renderer before comparing the same-generation Headless capture; agreement between two snapshots alone is not native parity evidence.
Backend-specific loaded results, especially OpenGL versus Vulkan, are recorded separately and must not be inferred from JVM protocol tests.
The 26.2 Vulkan Canvas-only resize gate keeps the native surface fixed while varying the logical viewport, framebuffer, and owned Canvas targets; it is target-retention evidence, not swapchain-resize or full-suite evidence.

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

The installed Fabric presenter retains at most one complete immutable UI profile for ordinary `ScreenDefinition.open()` calls.
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

### Player-skin lifecycle

The asynchronous skin completion path must retain only its detached lifecycle target and must not capture the screen, platform bridge, or binding owner after close.
Close must atomically reject late publication, drop a queued completion, clear a committed ready-image snapshot, clear its observer, and remain idempotent.
Owner-thread draining must transfer an accepted completion at most once, and a closed lifecycle must never accept another snapshot commit.

## Post-fix results

The first verified post-fix run was measured from commit `01d0705` after all three fixes and their deterministic tests landed together.
The clean retained-frame path now rounds to zero bytes per operation at every viewport and takes 0.004 microseconds per operation, removing the former 20,056-byte snapshot allocation and reducing measured time by more than 99.9% on this host.
Dirty-frame allocation is unchanged, and its measured time ranges from 1.3% faster to 0.1% slower than the baseline.
Headless rasterization retains the expected viewport-sized pixel allocation and measured between 1.7% and 4.4% faster than the baseline.
These comparisons confirm the intended clean-frame improvement without moving work into the dirty or raster paths; they remain diagnostic measurements subject to the environmental limits described above.

| Benchmark | Viewport | Average time (µs/op) | Allocation (B/op) |
| --- | --- | ---: | ---: |
| Clean session frame | Compact | 0.004 | 0 |
| Clean session frame | Windowed | 0.004 | 0 |
| Clean session frame | FullHd | 0.004 | 0 |
| Dirty session frame | Compact | 14.309 | 58,648 |
| Dirty session frame | Windowed | 13.669 | 58,648 |
| Dirty session frame | FullHd | 13.905 | 58,648 |
| Headless rasterization | Compact | 505.134 | 230,796 |
| Headless rasterization | Windowed | 3,208.000 | 1,643,616 |
| Headless rasterization | FullHd | 16,631.944 | 8,298,491 |

## Minecraft 1.21 family closure verification

The suite was rerun after every release from Minecraft 1.21 through 1.21.11 passed its development and production-jar loaded-client gates.
This run used the checked-in JMH configuration and OpenJDK 17.0.18 on the current Windows development host.
The timing values must not be compared directly with the earlier tables because host load and power state were not controlled across runs; allocation and deterministic structural gates remain the comparable evidence.

The ordinary clean path still rounds to zero bytes per operation and returns the retained snapshot in 0.004 microseconds.
The time-aware clean path traverses the 54-leaf retained scene to deliver the host timestamp but does not remeasure, relayout, repaint, rebuild semantics, or replace the complete frame snapshot.
It measured 1.207 to 1.210 microseconds and at most 0.035 normalized bytes per operation, which is profiler noise rather than one allocation per invocation.
The fully dirty path remains independent of viewport size at approximately 59,024 bytes per operation; this is the expected detached command and semantics replacement for all 54 invalidated leaves rather than an accumulating cache.
Headless allocation remains one fresh viewport-sized pixel image plus bounded command-processing overhead.

| Benchmark | Viewport | Average time (µs/op) | Allocation (B/op) |
| --- | --- | ---: | ---: |
| Clean timed session frame | Compact | 1.207 | 0.034 |
| Clean timed session frame | Windowed | 1.210 | 0.035 |
| Clean timed session frame | FullHd | 1.209 | 0.035 |
| Clean session frame | Compact | 0.004 | 0 |
| Clean session frame | Windowed | 0.004 | 0 |
| Clean session frame | FullHd | 0.004 | 0 |
| Dirty session frame | Compact | 17.217 | 59,024 |
| Dirty session frame | Windowed | 16.666 | 59,024 |
| Dirty session frame | FullHd | 16.611 | 59,024 |
| Headless rasterization | Compact | 503.499 | 230,796 |
| Headless rasterization | Windowed | 3,299.307 | 1,643,617 |
| Headless rasterization | FullHd | 16,282.742 | 8,298,489 |

No unbounded temporary-data retention or repeated clean-frame rendering was observed by these measurements and structural gates.
Every 1.21 loaded client additionally proves that detachment empties the Fabric presenter's dynamic-texture and prepared-layer collections and clears its prepared frame references.
Session tests prove that close releases the content owner before lifecycle cleanup, clears cached immutable frames, clears bindings and retained-tree ownership, and disposes every claimed node exactly once.
This statement is limited to the retained session, virtual-list current-range cache, Fabric prepared-layer and texture ownership, tooltip and loading-indicator time cells, and asynchronous player-skin lifecycle covered above; it is not a general heap-leak proof for downstream Mods.

## Minecraft 1.20 family closure verification

The suite was rerun from commit `820cc49` after Minecraft 1.20 through 1.20.6 passed their development, production-jar, and publication gates.
It used the same checked-in configuration, current Windows development host, and OpenJDK 17.0.18 as the 1.21 family-close run.
Host load and power state were still uncontrolled, so timing remains diagnostic while allocation and the deterministic gates are directly comparable.

The ordinary clean path remains an immutable snapshot lookup at 0.005 to 0.006 microseconds and effectively zero normalized allocation.
The time-aware clean path remains viewport-independent at 1.447 to 1.533 microseconds and at most 0.044 normalized bytes per operation, which is profiler noise rather than one allocation per invocation.
The dirty path remains approximately 59,025 bytes per operation at every viewport, and headless allocation remains the required fresh pixel image plus bounded command-processing overhead.
No allocation trend indicates retained historical frames, layers, textures, or visited virtual-list ranges.

| Benchmark | Viewport | Average time (µs/op) | Allocation (B/op) |
| --- | --- | ---: | ---: |
| Clean timed session frame | Compact | 1.447 | 0.042 |
| Clean timed session frame | Windowed | 1.533 | 0.044 |
| Clean timed session frame | FullHd | 1.486 | 0.044 |
| Clean session frame | Compact | 0.006 | ≈ 0 |
| Clean session frame | Windowed | 0.005 | ≈ 0 |
| Clean session frame | FullHd | 0.005 | ≈ 0 |
| Dirty session frame | Compact | 17.731 | 59,025 |
| Dirty session frame | Windowed | 17.450 | 59,024 |
| Dirty session frame | FullHd | 17.893 | 59,025 |
| Headless rasterization | Compact | 538.687 | 230,796 |
| Headless rasterization | Windowed | 4,356.504 | 1,643,627 |
| Headless rasterization | FullHd | 23,156.293 | 8,298,529 |

Every 1.20 development and production-jar loaded client also requires that an unchanged portable display list performs no extra partition, rasterization, or texture upload and that detachment clears screen-owned texture generations, prepared commands, prepared viewport, prepared layers, pointer caches, inventory bindings, and common host ownership.
Native texture storage and registered identifiers are checked after their actual completion fences allow device-owned retirement.
The Minecraft 1.20 and 1.20.1 Authlib 4 boundaries each publish only a normalized detached skin snapshot into that bounded lifecycle and pass the same late-completion and terminal-release contract.
Together with the unchanged session, virtual-list, tooltip, loading-indicator, and player-skin unit gates, the completed family shows no repeated clean rendering or unbounded temporary-data retention in the covered ownership domains.
