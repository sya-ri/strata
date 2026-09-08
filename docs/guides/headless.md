# Headless rendering and testing

Use the headless runtime to inspect portable drawing and semantics without opening a Minecraft client or creating a GPU context.
It produces deterministic immutable images and PNG bytes, which can be compared in ordinary JVM tests.
Application screens still use the public API; a test harness selects the runtime and any resource profile it needs.

## Choose the rendering boundary

| Input | Entry point | Use |
| --- | --- | --- |
| An immutable `Element` root | `renderHeadless` | Render a custom primitive or a structural SPI fixture and close its temporary tree. |
| Portable draw commands | `rasterizeHeadless` | Convert a prepared frame into pixels without retaining a tree. |
| A `ScreenDefinition` and Minecraft profile | The opt-in Minecraft host bridge | Exercise profile-backed components, frames, input, and lifecycle under a supplied profile. |

The [headless rendering tests](../../runtime/headless/src/test/kotlin/dev/s7a/strata/runtime/headless/HeadlessRenderTest.kt) demonstrate pixels, semantics, scaling, and CPU Canvas sources.
The [external host integration tests](../../integration/api/src/test/kotlin/dev/s7a/strata/integration/external/ExternalMinecraftUiHostIntegrationTest.kt) demonstrate a screen definition with a supplied profile.
That host bridge is an explicit runtime-testing boundary; its privileged element builder and session controls are not application screen APIs.
See the [API reference](https://gh.s7a.dev/strata/) for signatures.

## Supply a viewport and resources

Pass a positive logical viewport and a positive integer output scale.
Physical image dimensions are the checked product of each logical dimension and that scale.
The renderer validates dimensions before element creation or lifecycle callbacks, and arithmetic overflow fails before allocation.

A Minecraft-style screen also needs a complete immutable profile.
Offline font tools can assemble [resource snapshots](fonts.md) from caller-supplied archives, resource packs, and indexed assets.
The repository's [documentation renderer](../development/documentation.md#headless-showcase-generation) is a compiled example of that arrangement and can use four explicit read-only resource inputs.

## Inspect the result

Images expose immutable reads, fresh pixel copies, and deterministic PNG encoding.
Pixels have a top-left origin with x increasing rightward and y downward.
Output starts transparent, follows command order, and clips to the viewport and explicit child clips.
Semantics remain logical, unscaled, unclipped, and in core emission order, so compare them independently from physical image coordinates.

One-shot rendering always closes its temporary tree on the calling thread.
If rendering and cleanup both fail, the work failure remains primary and distinct cleanup failures are suppressed.
Frames retain no element description or live tree.
The [rendering contract](../development/rendering.md) specifies sampling, blending, and PNG details.

## Keep native evidence separate

CPU Canvas sources and ordinary portable components can be rendered directly.
Server-bound Slot items and opaque native commands require their platform environment; portable rasterization rejects unsupported payloads before output.
A [native Canvas capture](canvas.md#capture-a-presented-frame) becomes portable only with an exact snapshot for each presented generation.

Headless comparisons establish deterministic portable behavior.
Native Minecraft equality requires the separate [loaded-client checks](../development/build.md#loaded-client-verification), and font equality requires [independent font verification](../development/font-verification.md).
