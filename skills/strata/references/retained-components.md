# Retained components

Read this when a requested component owns measurement, procedural drawing, input, or lifecycle behavior that ordinary composition cannot provide.
Use the project's selected public API signatures; the [Element SPI](https://github.com/sya-ri/strata/blob/master/docs/reference/element-spi.md) and [Modifier SPI](https://github.com/sya-ri/strata/blob/master/docs/reference/modifier-spi.md) are the authoritative contracts.
For an external CPU image or native drawing producer, consider the standard `Canvas` source contract before introducing a retained primitive.

## Description and node

Keep an immutable `Element` description and one stable singleton `ElementType` for each logical primitive kind.
Emit the description through `UiScope.element`; create a fresh `Node` in the token's creation hook and update the retained node in its previous/current hook.
No central registry or runtime implementation imports are needed.
Copy caller-owned mutable collections into the description; do not let later mutation change an already accepted frame.
Expose presentation values, stable keys, a root modifier, and typed events rather than an entire application controller.

Use `MeasureNode`, `PaintNode`, input, semantics, and lifecycle capabilities only when the primitive owns those behaviors.
`MeasureNode.measure` returns a non-negative size constrained by the supplied `Constraints`.
A node that measures children must also lay them out; each direct child is measured and placed at most once per pass.
`PaintNode.paint` emits the complete local display list through `PaintScope`; drawing uses local coordinates and the runtime applies accumulated translation, scaling, and clips.
Do not retain callback scopes or issue platform rendering calls from portable paint code.

## Retention and updates

Return a `DirtyMask` for the phases affected by changed properties.
Geometry changes affect measurement; color or waveform samples can affect paint without replacing the node; a changed accessible value affects semantics as well.
Node-local input changes use protected invalidation on the owning UI thread.
Avoid work proportional to an entire data series when the visible output can be bounded by the component's viewport, while preserving the required visual meaning.
Describe the key, invalidation, bound, and lifetime of any derived-data cache.

Any-thread external notifications must enqueue revisions rather than mutate retained nodes or issue drawing work.
Use the public cutoff/session contracts when the primitive owns an observation.
Acquire external resources on attachment and release them on the appropriate detach/dispose boundary; externally supplied sources remain caller-owned.
Start a captured gesture only after capture is confirmed, handle cancellation, and provide keyboard behavior and unresolved semantics for interactive content.

## Verify the contract

Compile the component with `strata-api` as its only Strata implementation dependency.
Exercise it from two independent compositions when reuse is claimed.
Verify constrained and zero-size measurement, clips and scaling, local input coordinates, semantics, equal-property reuse, property updates, replacement, detach/reattach, and final cleanup.
Keep runtime-only instrumentation in a separate test harness.
The [external primitive fixture](https://github.com/sya-ri/strata/blob/master/integration/api/src/test/kotlin/dev/s7a/strata/integration/external/ExternalElement.kt) and its [node](https://github.com/sya-ri/strata/blob/master/integration/api/src/test/kotlin/dev/s7a/strata/integration/external/ExternalNode.kt) demonstrate the current public hooks; they are SPI fixtures rather than a recommendation to replace a standard component.
