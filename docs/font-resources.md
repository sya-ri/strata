# Font resources and offline rendering

Application declarations select a font through a `ResourceId`; see [Text and text input](text.md).
This guide describes the runtime boundary that supplies those fonts without adding Minecraft or LWJGL types to application source.

## Modules and ownership

`runtime:minecraft` owns resource loading, provider resolution, immutable font snapshots, glyph metrics, and bounded host caches.
It has no dependency on Minecraft classes, a graphics context, or an operating-system font service.
`runtime:minecraft-fonts-lwjgl` supplies the optional CPU backend for PNG decoding, the selected Minecraft TrueType rasterizer, and ICU text ordering.
`runtime:headless` rasterizes the resulting portable commands without a display or GPU.

A `MinecraftFontSnapshot` contains detached immutable resource data and selection options.
It may be reused by independent hosts and across threads; a source, archive stream, native face, backend, or mutable cache is never retained in it.
Each host opens its own backend on its owner thread and releases it after disposing the tree on every terminal path.
Detachment keeps the common host and its font engine available for reattachment; closing the host releases both.
An engine used directly is `AutoCloseable` and must be closed by its caller.

## Loading an offline resource stack

Supply sources from lowest to highest priority to `MinecraftFontSnapshot.load`.
The built-in sources read directories, ZIP or JAR archives, already-downloaded Minecraft asset indexes and object directories, or immutable in-memory files.
They do not download resources, start Minecraft, or extract archives to disk.
Directory and object reads reject paths or symbolic links that escape the selected root.
Callers must keep input files stable until loading finishes; subsequent changes cannot alter the returned snapshot.

For example, an offline tool can combine a supplied client JAR and asset store with a higher-priority custom pack:

```kotlin
import dev.s7a.strata.runtime.minecraft.font.MinecraftArchiveFontAssetSource
import dev.s7a.strata.runtime.minecraft.font.MinecraftDirectoryFontAssetSource
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontCompatibility
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontLoadLimits
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontOptions
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontSnapshot
import dev.s7a.strata.runtime.minecraft.font.MinecraftIndexedFontAssetSource
import java.nio.file.Path

/**
 * Loads detached font resources synchronously while the caller keeps its input files stable.
 * The returned snapshot may be shared across hosts and threads; no input stream remains open.
 * Invalid documents and pack metadata produce snapshot diagnostics; ordinary source enumeration failures propagate.
 *
 * @param clientJar caller-supplied client archive for the exact target release.
 * @param assetIndex caller-supplied Minecraft asset index.
 * @param objects directory containing the index's hashed asset objects.
 * @param customPack directory containing the highest-priority custom pack.
 * @param compatibility exact release capabilities selected by the caller.
 * @param options captured font and language options for the new profile.
 * @param limits immutable allocation and work ceilings applied before reading the index and snapshot.
 * @return immutable resource snapshot, independent of later input-file changes.
 */
internal fun loadFonts(
    clientJar: Path,
    assetIndex: Path,
    objects: Path,
    customPack: Path,
    compatibility: MinecraftFontCompatibility,
    options: MinecraftFontOptions,
    limits: MinecraftFontLoadLimits = MinecraftFontLoadLimits(),
): MinecraftFontSnapshot =
    MinecraftFontSnapshot.load(
        sources =
            listOf(
                MinecraftIndexedFontAssetSource(assetIndex, objects, "Minecraft assets", limits),
                MinecraftArchiveFontAssetSource(clientJar),
                MinecraftDirectoryFontAssetSource(customPack),
            ),
        compatibility = compatibility,
        options = options,
        limits = limits,
    )
```

The caller supplies the target release's `MinecraftFontCompatibility`, including its resource-pack format, provider-filter and overlay capabilities, TrueType rasterizer, shadow ordering, atlas-fallback metrics, width-rounding behavior, and prepared-text bounds.
`MinecraftFontOptions` captures forced Unicode, Japanese glyph variants, and language direction.
Do not derive these capabilities from the host operating system or assume that a neighboring Minecraft release has the same contract.
The versioned Fabric adapter selects them from its exact compiled game boundary.

Within an otherwise complete `MinecraftUiProfileBuilder` declaration, call `fonts(snapshot)` instead of declaring the compatibility ASCII glyph table.
GUI images and other required profile assets remain independently required.
The builder rejects duplicate snapshots and any mixture of snapshot fonts with ASCII glyph declarations.
Open the resulting profile with the `createMinecraftUiHost` overload accepting `LwjglMinecraftFontBackendFactory`, or another backend implementing the same target contract.
The old host overloads remain usable with ASCII-only profiles and do not initialize native libraries.

Standard Minecraft resources are caller-owned build or runtime inputs.
Strata does not redistribute the client JAR, Mojang font assets, or a downloaded resource pack.
The backend tests use original synthetic font outlines and images instead.

## Providers and failures

The resource stack applies supported overlays and resource filters before merging font documents.
Definitions use the native pack priority and provider order, including references and captured provider filters.
The supported providers are:

| Provider | Resource behavior |
| --- | --- |
| `bitmap` | PNG cells and Unicode scalar rows, with declared height and ascent, measured alpha bounds, and preserved color and alpha. |
| `space` | Explicit floating-point advances without a raster image. |
| `reference` | Ordered provider expansion with cycle detection and inherited filters. |
| `unihex` | ZIP-contained hexadecimal glyph rows and ordered size overrides. |
| `ttf` | Supplied TrueType bytes, size, oversampling, shifts, and skipped code points, rasterized by the selected native backend. |

Snapshots expose detached load diagnostics; engines add bounded provider-initialization diagnostics.
Malformed documents are skipped and unresolved or failed provider bundles cannot supply partial font results.
Malformed resource filters are ignored with a pack diagnostic.
Malformed overlay metadata follows the selected release contract: earlier releases ignore that section, while the newer strict contract excludes the affected pack.
Ordinary resource or provider failures follow the target's missing-font behavior; fatal JVM or native linkage failures propagate.
Unknown font IDs produce the missing-glyph result instead of silently selecting `minecraft:default`.
The special forced-Unicode default-font selection is applied only at the release boundary where Minecraft uses that mapping.
A caller may load a custom-only snapshot; unlike the game's global font-manager reload, creating that snapshot does not require a default font.

This implementation covers standard font providers and the standard text shader.
It does not invoke providers installed through Mod code, custom shaders, system fonts, Java2D, or an additional color-emoji or grapheme-composition engine.
Translation-key resolution remains outside this change.

## Input and allocation limits

`MinecraftFontLoadLimits` supplies immutable, inclusive ceilings to the snapshot loader and its decoders.
The overload accepting explicit limits preserves the original default-loading methods; built-in source constructors also accept limits where they read input before snapshot creation.
Pass the same limits to those sources and the snapshot when a caller needs tighter ceilings.
The defaults are:

| Work or payload | Default ceiling |
| --- | --- |
| Sources, entries per source or nested archive, aggregate examined entries | 256; 65,536; 262,144 |
| Source-relative or archive path length | 1,024 UTF-16 units |
| Compressed ZIP or JAR; one encoded asset; aggregate encoded input | 256 MiB; 32 MiB; 128 MiB |
| Font document, pack metadata, or asset-index input | 2 MiB |
| Expanded ZIP entry or PNG image-data stream; aggregate expanded payload | 32 MiB; 128 MiB |
| Font document locations; declared providers; resolved provider entries | 4,096; 16,384; 65,536 |
| Explicit glyph, mapping, skip, and override records; Unihex row payload | 1,048,576 records; 128 MiB |
| JSON nesting; JSON tokens per document; font-reference depth | 64; 1,048,576; 128 |
| Image width or height; one decoded image payload | 8,192 pixels; 64 MiB |
| Bitmap provider sheet payload, independent of raster-cache settings | 8 MiB |
| Distinct TrueType resource-and-settings descriptors; their weighted encoded input | 256; 128 MiB |

Byte ceilings describe payload, not total JVM or native memory.
Aggregate expansion includes PNG four-byte pixel payloads and inflated image data as well as expanded Unihex entries.
Duplicate Unihex records, ignored ZIP entries, and directory entries still consume the corresponding work budgets.
Observed work and conservatively charged failed reads are not refunded after rejection; independent providers can continue only while the relevant budget remains.
TrueType input is charged again for each distinct size, oversampling, or shift configuration, even when the underlying file is shared; skip lists and provider filters do not duplicate that native descriptor.

Built-in sources bound stream reads before copying, stop at the first detection byte beyond a ceiling, and close streams on success or failure.
On Fabric, Minecraft's `ResourceManager` may materialize the font-document enumeration before Strata receives it.
These ceilings bound Strata's subsequent entry copies and resource reads, not allocations performed by that earlier Minecraft enumeration.
Custom sources can use the synchronous `InputStream.readMinecraftFontBytes(maximumBytes)` extension for the same inclusive limit and one-byte overflow detection.
That helper borrows the stream without closing or retaining it; the source must use `use` or equivalent cleanup on both success and failure.
Unihex parsing uses fixed-size record and read buffers instead of retaining expanded archive strings.
Recognizable PNG input is checked before native decoding: dimensions must satisfy pixel limits and the structural native byte-buffer bound, and IDAT expansion is inspected with a fixed scratch buffer before STB can allocate its decoded output.
Bitmap sheets additionally obey the independent 8 MiB default ceiling, so the default 16 MiB raster cache can retain a sheet alongside a copied glyph.
A sheet exceeding that input ceiling is rejected in every cache mode before the bundled decoder allocates its pixels.
Raising the sheet ceiling or lowering the raster cache may cause repeated decoding during glyph lookup; it does not make cache settings choose a different provider.

Native TrueType faces validate atlas-sized glyph dimensions before raster allocation.
A source raster beyond Minecraft's 256 by 256 atlas is represented by measured rejection metadata without allocating its pixels.
Custom backends must also enforce their own allocation boundary: the host cannot undo an allocation performed inside an old callback.
It checks returned image sizes before retention; a TrueType face returning an oversized image is closed and permanently disabled for that engine, including with raster caching disabled or after cache churn.
Only detached failure messages remain, and independent faces remain usable.

Existing `MinecraftFontAssetSource` and `MinecraftFontBackend` implementations keep their original callback behavior.
Custom sources returning an already allocated byte array are responsible for bounding that allocation; the loader checks its length before taking a copy.
Implement `MinecraftBoundedFontAssetSource` or `MinecraftBoundedFontBackend` to receive the selected limits before allocation.
A bounded source reporting physical entries must invoke its accounting callback synchronously on the loading thread, before filtering each entry, and must not retain that callback.
Late or foreign-thread callback calls fail before touching counters, and callback completion releases its budget reference even if enumeration throws.

`MinecraftFontLoadLimitException` identifies an intentional input-policy rejection.
Source-construction failures propagate immediately; limits encountered while loading are reported through existing source, document, or provider diagnostics where that boundary permits independent recovery.
Unexpected enumeration errors and fatal failures still propagate.
These ceilings are an explicit safety policy, not a claim that Minecraft accepts exactly the same maximum inputs.
Non-PNG STB-decodable payloads also remain outside exact format-rejection parity: native readers from Minecraft 1.20.3 onward require a PNG header, while this CPU backend preserves STB format acceptance after its allocation checks.

## Measured vanilla inputs

The checked-in [vanilla load-budget receipt](https://github.com/sya-ri/strata/blob/master/runtime/minecraft/src/test/resources/font-evidence/vanilla-load-budgets.json) records all 21 supported releases, official client and asset-index hashes, source-entry counts, and observed font document, provider, glyph, archive, and image payload maxima.
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

## Native dependencies

Fabric uses the LWJGL, ICU, and Gson libraries already provided by the exact game release.
The CPU backend jar does not bundle a second copy of those libraries or native binaries.
An offline application must explicitly supply matching Java bindings, ICU, Gson, and the native classifier for its operating system and architecture.
The repository's [version catalog](https://github.com/sya-ri/strata/blob/master/gradle/libs.versions.toml) pins those dependencies from the official game manifests, and [backend verification configuration](https://github.com/sya-ri/strata/blob/master/runtime/minecraft-fonts-lwjgl/verification.gradle.kts) records the isolated runtime combinations.
Use those declarations as the source of dependency versions instead of copying a fixed version from this guide.

Minecraft 1.20 through 1.20.4 use the STB TrueType path; later supported releases use FreeType.
Native library generations must not be mixed in one process.
The offline verification tasks therefore run distinct dependency combinations in separate JVMs, including the target's core-jar and native-classifier variants.
`strata.fontNatives` can explicitly select a supported classifier when the build host's default is not appropriate.
No OpenGL or GLFW classpath or GPU context is required by those tests.

## Rasterization and cache contract

Glyphs retain floating-point advances and ink bounds independently of their source-image dimensions.
Text emits portable `SampledImage` commands with a tint, source rectangle, source orientation, nearest sampling, and alpha-discard threshold.
Reversed source axes are sampled directly rather than approximated by rotated pixels, preserving nearest-texel ties for negative bitmap heights and signed TrueType oversampling while finite destination rectangles remain normalized.
The headless renderer samples and blends at the final physical output resolution.
Fabric passes its actual GUI scale and includes that scale in prepared-frame and raster-texture reuse decisions.
`BlitImage` keeps its existing integer-region contract.

The default per-host raster cache is limited to 4,096 entries and 16 MiB of pixel payload; glyph results, cell results, and decoded bitmap sheets share those limits.
At most 16 native faces remain open, and their combined encoded input payload cannot exceed the snapshot's `maxAssetBytes`, 32 MiB by default.
Eviction closes faces before opening replacements; raising the asset ceiling also raises this derived native-input retention ceiling.
Bitmap sheet keys use detached resource identity; cell scans share resource, grid, and cell identity while retaining only the current height/ascent result for that cell.
TrueType faces and glyphs share resource and exact settings, with skips evaluated per provider before glyph lookup.
Other glyphs use snapshot-local provider identity and scalar keys; neither complete historical strings nor unknown font-ID lookups accumulate.
Successful descriptor checks and detached initialization failures are bounded by the current snapshot, independent of the raster and live-face LRUs, so duplicate declarations do not repeat preflight decoding or opening.
A raster larger than a caller's configured cache budget can be returned without retaining it in the cache.
TrueType source rasters that exceed Minecraft's 256 by 256 font atlas are measured without allocating their pixels and use the native missing sprite.
The limits exclude detached glyphs still owned by a live retained tree or caller.
See [Rendering performance](performance.md) for invalidation, ownership, and retention checks.

Resources and font options are fixed when a profile and screen are created.
To apply a changed pack, language-direction option, or Unicode option, create a new snapshot, profile, and screen; do not mutate a live engine or reuse an old profile as a reload mechanism.

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
Every scale first renders the same declaration into RGBA8 and requires all pixels to equal the ordinary native screenshot; this calibration image is hashed in the receipt and compared again offline.
Shaders, vertex generation, texture setup, sorting, and blending remain native, and the original ordinary screenshot is never replaced by the calibration capture.
At every differing pixel, the native float output must match independently evaluated resource-derived shader and blend arithmetic, allowing only measured subpixel-boundary alternatives and bounded floating-point interpolation at the actual atlas extent.
Arithmetic error is propagated separately for each color channel through the actual magnitudes of normalization, multiplication, subtraction, and addition; a fixed unit-magnitude tolerance cannot hide a small tint change.
The final native byte result must then remain within one RGBA8 unit per effective blend, propagated through source alpha.
Channels that remain exactly zero or one receive no conversion allowance, and a fully opaque fragment discards uncertainty from covered earlier fragments.
These conservative bounds are checked against current captures; they are not a promise that every GPU falls within them.
Unclassified float, geometry, sampling, color, or candidate-raster differences fail the gate.
OpenGL does not guarantee exact cross-device fixed-point blending or a universal shared-edge ownership rule; see the [blend reference](https://registry.khronos.org/OpenGL-Refpages/gl4/html/glBlendFunc.xhtml) and [core rasterization specification](https://registry.khronos.org/OpenGL/specs/gl/glspec32.core.pdf).

Receipts distinguish exact pixels from verified GPU differences and include complete difference classifications, float captures, precision observations, original-input hashes, and exact provider evidence.
The separate CPU comparison process recomputes glyph metrics and texel hashes from the original resource bytes, rerenders the scene, and reevaluates every GPU proof without loading Minecraft or a graphics context.
It also binds both saved metadata sets to the current packaged fixture bytes, scene, compiled target capabilities, and dependency generation rather than accepting agreement between two stale outputs.
Missing, changed, incomplete, or unsuccessful evidence cannot produce an acceptance receipt.
The native oracle serializes the standard font manager's preparation work to avoid concurrent access to shared FreeType faces; it retains the original resource definitions and standard provider and renderer implementations.

The original ordinary-font fixtures passed the native font gate and separate CPU comparison on Minecraft 1.20, 1.20.5, and 26.2 at GUI scales 1, 2, and 3 on the validation host.
Each target compares 23 provider metric probes, 21 glyph rasters, 19 layout rows, and 1,075,200 final pixels across the three scales, with no unclassified final-image differences.
This is evidence for those resources, target contracts, and device observations, not a guarantee of pixel identity for every resource pack or graphics device.
Build-only native evidence lives under each representative integration module's `font-parity` output; the separate CPU receipts live under `runtime/minecraft-fonts-lwjgl/build/font-offline-parity`.
Earlier failed runs remain diagnostics, not acceptance receipts.

Minecraft 26.2 also runs a separate default-font readability scene with Japanese, Korean, and a supplementary emoji, using the active resource stack and unchanged native font options.
Its opaque container-label text must match native Minecraft, Fabric, and headless ARGB exactly at GUI scales 1, 2, and 3, without a GPU-difference allowance.
The `font-parity/readability` receipt records resource hashes, options, actual scales, and full-frame captures; separately labelled Text and TextArea previews are newly rasterized headlessly at scales 2 and 3.
These previews explain the loss of CJK strokes at scale 1 without substituting a different font or enlarging an existing raster; see [rendering density](text.md#rendering-density).

## Numeric provider settings

Finite TrueType size and oversampling accept either sign, including positive and negative zero, subject to the STB coordinate-safety boundary below.
A sign or zero value alone does not discard the containing font document or its sibling providers.
The selected STB or FreeType provider determines their metrics and rasterization; a negative setting is not automatically treated as a missing or empty glyph.
FreeType's pixel-size setter result is handled like Minecraft's provider, while errors from subsequent glyph loading still propagate.
An absent character, an existing glyph without ink, and an atlas-rejected raster remain distinct results.

For a TrueType source raster exceeding the native 256 by 256 atlas, Minecraft through 1.21.8 retains the provider advance while drawing the missing sprite.
Minecraft 1.21.9 and later use the missing glyph's advance as well as its pixels.
Both paths avoid allocating the oversized raster.
Offline callers pass `bakedGlyphMetrics = true` when constructing `MinecraftFontCompatibility` for the latter behavior; its default is `false`.

Zero oversampling can produce NaN or infinite logical metrics even when the source pixels are small.
The font engine retains those native metrics and floating-point accumulation, including their effect on later glyph positions.
Minecraft 1.21.6 and later also test the aggregate raw ink bounds of each styled text run before accepting it for GUI drawing.
An empty or reversed aggregate extent suppresses the entire run, including an earlier ordinary prefix.
NaN retains the native comparison behavior and does not trigger this aggregate rejection by itself.
Spacing-only glyphs do not add ink bounds, although their advance affects later glyph positions.
Offline callers pass `preparedTextBounds = true` when constructing `MinecraftFontCompatibility` for these releases; its default is `false`.
Within an accepted run, final quads that cannot be represented as finite portable geometry are omitted.
The selected compatibility contract also preserves native integer width conversion: Minecraft 26.1 and later saturate after rounding in double precision, while earlier releases retain the integer-increment overflow behavior.
Offline callers pass `saturatingCeil = true` when constructing `MinecraftFontCompatibility` for the later conversion; its default is `false`.
The versioned Fabric adapters set these capabilities automatically.
General UI layout extents remain non-negative; text runs retain the signed native width separately for editor geometry.
Existing single-line `Text` overloads keep their requirement that layout constraints contain the natural size; an extremely large measured width is not silently reduced to the viewport.
The explicit multiline layout instead wraps or clips against its parent's finite constraints as described in [Text and text input](text.md#multiline-display).

STB operations whose computed coordinates are non-finite or outside the native integer range are rejected before the native float-to-integer conversion.
Those extreme operations have no defined portable result in the native rasterizer and are a safety boundary, not a claimed pixel-parity case.
Non-finite JSON settings remain invalid.

The separate numeric font gate passed native and independent offline comparison on Minecraft 1.20, 1.20.5, and 26.2 at GUI scales 1, 2, and 3 on the validation host.
Its seven size and oversampling combinations check 42 raw provider observations, 56 signed native widths, 14 styled rows, and 1,075,200 final pixels per target.
The cases distinguish absent glyphs, empty ink, oversized rasters, reversed axes, signed zero, non-finite accumulation, and the prepared-text bounds behavior.
Raw metrics and glyph texels match exactly; final native image differences require the same independent GPU proof as the ordinary-font gate, with no unclassified differences.
Numeric receipts and images are stored in a separate `numeric` directory beneath each native and offline evidence root.

`./gradlew :runtime:minecraft-fonts-lwjgl:check` runs the isolated CPU dependency matrix without starting Minecraft.
`./gradlew :runtime:minecraft-fonts-lwjgl:verifyOfflineFontParity` recreates the representative native runs and compares their metrics and complete images with fresh renders from separate CPU workers.
