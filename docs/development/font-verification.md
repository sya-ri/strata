# Font verification

Use these procedures when changing font loading, native compatibility, rasterization, or evidence generation.
The [font guide](../guides/fonts.md) explains resource-loading choices and links to KDoc for individual options, limits, and provider contracts.
Portable parity alone cannot prove agreement with Minecraft's native renderer.

## Run the checks

`./gradlew :runtime:minecraft-fonts-lwjgl:check` runs the isolated CPU dependency matrix without starting Minecraft.
`./gradlew :runtime:minecraft-fonts-lwjgl:verifyOfflineFontParity` recreates the representative native runs and compares their metrics and complete images with fresh renders from separate CPU workers.

## Measured vanilla inputs

The checked-in [vanilla load-budget receipt](https://github.com/sya-ri/strata/blob/master/runtime/minecraft/src/test/resources/font-evidence/vanilla-load-budgets.json) records every measured supported release, official client and asset-index hashes, source-entry counts, and observed font document, provider, glyph, archive, and image payload maxima.
It contains no game assets, local user paths, machine identifiers, or timestamps.
The [measurement script](https://github.com/sya-ri/strata/blob/master/tools/measure-vanilla-font-budgets.py) verifies the caller's cached official inputs and derives the receipt deterministically from the version catalog; it never downloads resources or modifies those inputs.

```text
python tools/measure-vanilla-font-budgets.py --loom-cache <supplied-loom-cache> --output runtime/minecraft/src/test/resources/font-evidence/vanilla-load-budgets.json --verify
```

Omit `--verify` only when intentionally regenerating the receipt after an input or supported-version change.
The tool requires each version metadata ID to match its catalog entry before opening the associated index or client.
Its synthetic provenance regressions run with `python -B -m unittest discover -s tools -p 'test_*.py'` and require no game files.
JVM tests compare the receipt's complete ordered version list with the root typed target matrix, check observations against default ceilings, and recompute maxima without requiring official assets.
The largest observed selected asset is 1,615,995 bytes; the largest font document is 19,118 bytes; the largest Unihex expanded entry is 7,771,248 bytes; and the largest bitmap is 129,600 pixels.
These are observed payload sizes, not total heap or native-memory bounds.
Vanilla supplies no TTF providers: the TrueType ceilings are explicit security policy backed by synthetic and redistributable-font tests, not by this dataset.

## Acceptance evidence

JVM tests exercise pack precedence, overlays, filters, references, malformed resources, missing glyphs, cache-disabled parity, host isolation, cleanup, Unicode editing, and new strings rendered from resource bytes.
The independent loaded font gate compares native provider metrics and text rendering with offline and Fabric results at GUI scales 1, 2, and 3 using bitmap, Unihex, and original TrueType fixtures.
Colored backgrounds, partially transparent glyphs, and overlapping shadows are part of that gate.
A successful common raster test or a Fabric-to-headless match alone is not evidence of equality with Minecraft's standard renderer.
Fresh loaded receipts and their full-frame images are required before claiming that a target and fixture combination passed.

Native provider metrics, raw glyph texels, source orientation, and public layout dimensions must match exactly.
Fabric and CPU output must also match exactly at every tested physical scale.
The resource-font gate permits final native image differences only when the same run supplies independent evidence of a device effect.
It does not apply a blanket image tolerance or reuse historical accepted pixels.

The standard native renderer draws the unchanged scene into both its ordinary RGBA8 target and an owned RGBA32F target at the same physical viewport and GUI scale.
Minecraft 26.2 validates a pipeline's declared color format against the actual target, so that capture copies the native pipeline declaration and changes only its color attachment format.
Every scale first renders the same declaration into RGBA8, requires every ordinary screenshot pixel to be opaque, and requires exact RGB equality at every pixel; `visibleRgbCalibration=exact` records this observation.
Minecraft normalizes ordinary screenshot alpha to 255, so that screenshot cannot establish equality of hidden framebuffer alpha.
The raw calibration image is retained without changing its alpha, hashed in the receipt, and compared again offline.
Shaders, vertex generation, texture setup, sorting, and blending remain native, and the original ordinary screenshot is never replaced by the calibration capture.
At every differing pixel, the native float RGB output must match independently evaluated resource-derived shader and blend arithmetic, allowing only measured subpixel-boundary alternatives and bounded floating-point interpolation at the actual atlas extent.
Float captures preserve and hash all four raw channels, require each channel to be finite and normalized, and do not classify hidden alpha against the opaque screenshot.
This exclusion applies only to final screenshot alpha; raw glyph alpha, source-alpha blending, alpha discard, and transparent-target rendering retain their existing checks.
Arithmetic error is propagated separately for each color channel through the actual magnitudes of normalization, multiplication, subtraction, and addition; a fixed unit-magnitude tolerance cannot hide a small tint change.
The final native byte result must then remain within one 8-bit unit per visible color channel and effective blend, propagated through source alpha and rounded outward after every discrete blend conversion.
Channels that remain exactly zero or one receive no conversion allowance, and a fully opaque fragment discards uncertainty from covered earlier fragments.
These conservative bounds are checked against current captures; they are not a promise that every GPU falls within them.
Unclassified float, geometry, sampling, color, or candidate-raster differences fail the gate.
OpenGL does not guarantee exact cross-device fixed-point blending or a universal shared-edge ownership rule; see the [blend reference](https://registry.khronos.org/OpenGL-Refpages/gl4/html/glBlendFunc.xhtml) and [core rasterization specification](https://registry.khronos.org/OpenGL/specs/gl/glspec32.core.pdf).

Receipts distinguish exact pixels from verified GPU differences and include complete difference classifications, float captures, precision observations, original-input hashes, and exact provider evidence.
The separate CPU comparison process recomputes glyph metrics and texel hashes from the original resource bytes, rerenders the scene, and reevaluates every GPU proof without loading Minecraft or a graphics context.
It also binds both saved metadata sets to the current packaged fixture bytes, scene, compiled target capabilities, and dependency generation rather than accepting agreement between two stale outputs.
Missing, changed, incomplete, or unsuccessful evidence cannot produce an acceptance receipt.
The native oracle serializes the standard font manager's preparation work to avoid concurrent access to shared FreeType faces; it retains the original resource definitions and standard provider and renderer implementations.

The representative ordinary-font fixtures compare Minecraft 1.20, 1.20.5, and 26.2 at GUI scales 1, 2, and 3; a pass requires fresh successful receipts from the selected revision and environment.
Each target compares 23 provider metric probes, 21 glyph rasters, 19 layout rows, and 1,075,200 final pixels across the three scales, with no unclassified final-image differences.
A successful receipt establishes evidence for its resources, target contract, and device observations, not pixel identity for every resource pack or graphics device.
Build-only native evidence lives under each representative integration module's `font-parity` output; the separate CPU receipts live under `runtime/minecraft-fonts-lwjgl/build/font-offline-parity`.
Earlier failed runs remain diagnostics, not acceptance receipts.

Minecraft 26.2 also runs a separate default-font readability scene with Japanese, Korean, and a supplementary emoji, using the active resource stack and unchanged native font options.
Its opaque container-label text must match native Minecraft, Fabric, and headless ARGB exactly at GUI scales 1, 2, and 3, without a GPU-difference allowance.
The `font-parity/readability` receipt records resource hashes, options, actual scales, and full-frame captures; separately labelled Text and TextArea previews are newly rasterized headlessly at scales 2 and 3.
These previews explain the loss of CJK strokes at scale 1 without substituting a different font or enlarging an existing raster; see [rendering density](../guides/text.md#rendering-density).

## Numeric provider evidence

The separate numeric gate compares the representative native targets and independent offline workers at GUI scales 1, 2, and 3.

Its seven size and oversampling combinations check 42 raw provider observations, 56 signed native widths, 14 styled rows, and 1,075,200 final pixels per target.
The cases distinguish absent glyphs, empty ink, oversized rasters, reversed axes, signed zero, non-finite accumulation, and the prepared-text bounds behavior.
Raw metrics and glyph texels match exactly; final native image differences require the same independent GPU proof as the ordinary-font gate, with no unclassified differences.
Numeric receipts and images are stored in a separate `numeric` directory beneath each native and offline evidence root.

## Text and density fixtures

The fixed ASCII native comparison scenes remain supported, and the component catalog adds compiled Unicode multiline Text and TextArea viewports.
Minecraft-independent tests cover supplementary insertion and deletion, UTF-16 limits, malformed input, scrolled pointer placement, visual-line affinity, custom-font metrics, fractional glyph bounds, composition state, clip lifetime, and bounded current-layout painting.
Those deterministic tests do not by themselves establish native pixel equality for every resource pack, provider, or GUI scale.
Native font acceptance must compare the selected resources against an independent Minecraft rendering result.

GUI scale affects readability, especially for characters with many strokes.
In the Minecraft 26.2 default-font comparison, a 16-by-16 CJK Unihex glyph occupies eight logical pixels in each direction: eight physical pixels at GUI scale 1, sixteen at scale 2, and twenty-four at scale 3.
Scale 1 therefore loses fine strokes even when the Unicode text and selected Japanese glyph are correct.
The Text, TextField, and TextArea component images are rendered at scale 2 so the source glyph's fine strokes remain visible.
Their logical viewports are unchanged; the headless renderer samples the original font resources directly into the larger physical image.

The density example describes its fixed native fixture rather than every possible resource font.
Reader-facing advice belongs in [text rendering density](../guides/text.md#rendering-density); showcase production belongs in [documentation maintenance](documentation.md).
