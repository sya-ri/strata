# Tiled images and pan-and-zoom navigation

`TiledImage` presents a bounded logical raster from independently revisioned immutable image tiles.
It is a standard component because maps, large scans, and schematics all need visible-tile demand, resolution selection, and bounded observation that composition from independent `Image` children cannot provide.
It does not load files, decode images, perform network requests, generate map data, or own application markers.

`PanZoomState` is caller-owned navigation state.
The state stores a content-space center and a zoom multiplier over either a contain or cover fit scale, while the attached viewport publishes its current content bounds and logical size.
One state may have one live geometry-owning viewport and any number of observing controls.
All state access and observer release stay on the thread that created the state.
Observers may read the published snapshot and release observations, while synchronous writes from an observer callback are rejected so later observers never receive stale metrics.
One observer failure does not prevent the remaining live observers from invalidating; the first failure escapes after all are attempted and the new metrics stay committed.

## Source geometry

A `TiledImageSource` instance is one complete source generation.
Its nonempty half-open `LongRect` bounds and finest-to-coarsest `TiledImageLevel` list cannot change while that source identity remains attached.
Replace the source object to publish another generation.
Every bound edge must convert to `Double` and back without rounding, and each mathematical axis midpoint must be exactly representable as `Double`, because navigation exposes its center and coordinate conversions as `DoubleOffset`; inexact geometry beyond that precision is rejected during declaration instead of failing during measurement or silently dropping edge tiles.

Each level declares an exact tile pixel size and a positive number of content units represented by one source pixel.
The tile content extent is the checked product of those values.
Each coarser tile content width and height is an aligned multiple of the preceding finer level, which keeps the level grids coherent while fallback layers overlap.
The grid origin is content coordinate zero; negative coordinates use mathematical floor division.
Ready images must exactly match their level and pixels outside finite source bounds are transparent.

The source returns a `StateSource<TiledImageTile>` for one `TiledImageTileId` without blocking on I/O, decoding, or rendering.
`Empty` means that the tile currently contributes no pixels; loading, retry, and failure presentation remain application responsibilities.
Callbacks may arrive on any thread and only enqueue newer revisions.
The retained component captures every active tile at the shared frame cutoff before committing any of them, so one returned frame never mixes pre-cutoff and post-cutoff observations.

## Working set and invalidation

The component observes only tiles intersecting the viewport plus the configured overscan margin and any visible coarser fallbacks.
It reserves entry count and RGBA8 byte cost before replacing the current working set.
When a preferred level exceeds the policy, it selects a coarser level; if the coarsest visible set still exceeds the policy, the change fails before partial subscriptions are installed.

The retained working-set key is the source identity and `TiledImageTileId`.
A binding accepts only newer `StateRevision` values and retains its committed value, newest pending value, and cutoff capture while required by the current transaction.
Leaving the working set, changing source identity, detaching, closing, or failing terminal cleanup closes the observation without closing the externally owned source.
No historical offscreen tile cache belongs to the component.

Pan, zoom, and resize change only the visible plan, destination rectangles, and selected level.
They do not change a ready `DrawImage` identity.
Overlay movement changes only overlay layout and paint.
A tile revision invalidates only that tile binding, while source replacement invalidates the complete generation.

## Painting and overlays

Ready fallback tiles paint as complete images from the coarsest subscribed level toward the selected level, with deterministic row-major order inside each level and one viewport clip.
Finer ready tiles cover their corresponding coarser output, while an empty fine tile leaves the best available coarser image visible.
The component never slices a coarser image into selected-level cells, so every command retains an integral whole-image source rectangle and cannot introduce fallback seams or a new joined-image copy.
Absolute content coordinates remain `Long` or `Double` until the viewport origin is subtracted; only local destination rectangles convert to `Float`.
This preserves useful precision at ordinary Minecraft world coordinates.
Headless and Minecraft adapters consume the same portable command list.

The optional `TiledImageScope` positions fixed-size direct children with `Modifier.atContentPosition`.
Children share the image transform for placement, paint after tiles, and remain clipped to the viewport, but their own size does not scale with zoom.
Applications may pass either one fixed `DoubleOffset` or a `StateSource<DoubleOffset>` whose any-thread revisions commit at the frame cutoff and invalidate only overlay placement.
Moving a player marker through that source therefore leaves tile observations, immutable tile identities, and native tile uploads unchanged.
An interactive overlay that consumes a primary press prevents the later pan modifier from acquiring pointer capture.

`Modifier.panZoom` uses the generic captured-pointer contract.
A consumed primary press captures subsequent drag and matching release outside bounds and clips; cancel, removal, detach, close, window input reset, and failure end the gesture once.
Wheel zoom preserves the content coordinate under the pointer, and programmatic controls use the same `PanZoomState` operations.

Rotation, tilt, inertia, multi-touch gestures, arbitrary subtree scaling, marker models, selection, route finding, and editing are outside the initial contract.

## Minecraft presentation acceleration

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
