# Canvas

Use `Canvas` to display external drawing output inside a positive, explicitly sized logical rectangle.
CPU image frames, leased native textures, and custom offscreen renderers share the same component placement and clipping behavior.
The application owns its producer and any decoding, camera, video, audio, or rendering model.

The [component overview](../reference/components.md) contains a portable CPU example.
The [API-only source example](../../integration/api/src/main/kotlin/dev/s7a/strata/integration/consumer/ApiOnlyCanvas.kt) connects a revisioned image source and an independent pointer handler.
Use the [API reference](https://gh.s7a.dev/strata/) for factory and provider signatures.

## CPU sources

`UiScope.Canvas(source, size, modifier, key)` stretches the complete source image with nearest sampling.
`canvasSource(DrawImage)` and `canvasSource(StateSource<DrawImage>)` need only the platform-neutral API.
DrawImage owns immutable straight-ARGB pixels independent of the caller's array; replacing its pixel extent updates paint without changing the declared destination.

`canvasSource(StateSource<DrawImage>)` observes immutable frames that may arrive from any thread.
Observers enqueue revisions; timed and untimed host frames commit them before painting.
The destination remains its explicit logical size when a replacement image has different pixel dimensions.
The [source contract](../reference/state-sources.md) defines revision ordering and subscription ownership.

Each attached Canvas owns its binding; the source remains externally owned.
Source replacement, screen detachment, or terminal cleanup closes that binding without closing the source.
A failed binding open must release every resource acquired by that attempt.

## Native textures and renderers

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

Native providers prepare output only for actual presentation, after layout has settled.
Declaration evaluation, measurement, cached painting, and extra host frames do not execute the producer.
The runtime owns preparation targets and GPU completion handling; producer implementations must respect the borrowed callback and image-lease lifetimes.
The [native presentation contract](../development/rendering.md#native-canvas-presentation) describes those implementation boundaries.

## Capture a presented frame

`FabricMinecraftScreen.captureCanvasFrame()` converts the last successfully presented frame to portable commands using its immutable CPU receipts only.
Every native token must have an exact same-generation, same-extent, normalized snapshot; missing, mismatched, or unsupported native commands fail before partial output.
An initially unavailable Canvas remains transparent on screen, but its presentation cannot be captured until every requested Canvas has a committed generation and matching snapshot.
Native images become BlitImagePixels commands with unchanged logical destinations; rasterize the capture at the presentation's GUI scale to reproduce every physical texel.
This call never resolves a live GPU token, reads pixels back implicitly, or substitutes placeholder pixels for native evidence.

## Input and boundaries

Canvas supplies no pointer, keyboard, or focus behavior itself.
Compose ordinary [input modifiers](modifiers.md) when the displayed output should be interactive.
Captured gestures use the same generic pointer contract as other components and do not change image-source observation.

The native input contract accepts ordinary two-dimensional RGBA8 straight-alpha color with nearest sampling.
HDR, multisample and depth images as source content, direct drawing into the current GUI framebuffer, and implicit GPU readback are outside that contract.
Optional depth belongs to an offscreen renderer's target configuration.
The runtime's [resource bounds and release rules](../development/performance.md#canvas-source-and-target-retention) apply independently to every attachment.
