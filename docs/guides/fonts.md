# Font resources for offline rendering

Use a detached font snapshot when an offline tool needs to render text from Minecraft resources or a custom resource pack.
Ordinary application screens select a font identifier through [Text or an editor](text.md#selecting-a-font); the installed Fabric runtime supplies their font environment.
This guide explains how an offline caller supplies that environment without launching Minecraft or creating a graphics context.

## Loading an offline resource stack

The compiled example below combines already-downloaded indexed assets, a client JAR, and a higher-priority custom pack.
Supply sources from lowest to highest priority and keep their files stable until loading returns.
The result is independent of later input-file changes.

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

## Choose modules and a profile

| Module | Role in an offline tool |
| --- | --- |
| `runtime:minecraft` | Loads resource stacks and provides detached font snapshots and profile-backed text. |
| `runtime:minecraft-fonts-lwjgl` | Supplies CPU image decoding, native TrueType rasterization, and text ordering. |
| `runtime:headless` | Converts portable drawing commands into images and PNG output. |

Install the snapshot through `fonts(snapshot)` in an otherwise complete Minecraft profile; GUI images and other profile assets are still required.
A resource-font profile uses a backend factory when creating its host.
The finite ASCII profile is a separate option that does not initialize native font libraries, and a profile cannot combine the two font formats.
See the profile builder's [fonts method](https://gh.s7a.dev/strata/runtime/minecraft/dev.s7a.strata.runtime.minecraft/-minecraft-ui-profile-builder/fonts.html) and the [LWJGL backend factory](https://gh.s7a.dev/strata/runtime/minecraft-fonts-lwjgl/dev.s7a.strata.runtime.minecraft.font.lwjgl/-lwjgl-minecraft-font-backend-factory/index.html) for exact declarations.

A snapshot can be shared across hosts and threads because it retains detached resources rather than streams or native faces.
Each host owns its backend and closes it on the host thread during terminal cleanup; temporary detachment keeps that ownership available for reattachment.
A directly opened [font engine](https://gh.s7a.dev/strata/runtime/minecraft/dev.s7a.strata.runtime.minecraft.font/-minecraft-font-engine/index.html) must be closed by its caller.
To apply resource-pack or language-option changes, create a new snapshot, profile, and host.

## Supply resources and native dependencies

Built-in sources read directories, ZIP or JAR archives, indexed asset stores, and immutable in-memory files.
They do not download resources or extract archives to disk.
Directory and indexed-object reads reject paths and symbolic links that escape their selected root.
Standard Minecraft assets and downloaded packs remain caller-supplied inputs; Strata does not redistribute them.

Choose compatibility for the exact target resource and rendering environment, and capture the desired font and language options when loading.
The Fabric adapter makes that choice at its compiled game boundary; an offline caller must supply it explicitly rather than infer it from the operating system or a neighboring game version.
The [compatibility type](https://gh.s7a.dev/strata/runtime/minecraft/dev.s7a.strata.runtime.minecraft.font/-minecraft-font-compatibility/index.html) and [font options](https://gh.s7a.dev/strata/runtime/minecraft/dev.s7a.strata.runtime.minecraft.font/-minecraft-font-options/index.html) document individual settings.
The [backend verification matrix](../../runtime/minecraft-fonts-lwjgl/verification.gradle.kts) records the tested capability and dependency combinations.

Fabric uses the LWJGL, ICU, and Gson libraries supplied by its game release.
An offline application supplies matching Java libraries and the native classifier for its operating system and architecture; the CPU backend does not bundle another copy.
Use the [version catalog](../../gradle/libs.versions.toml) and verification matrix for those dependencies.
Do not mix native library generations in one process; the repository verifies incompatible combinations in separate JVMs.
See [font verification](../development/font-verification.md#run-the-checks) for running those checks and [adapter development](../development/minecraft-versions.md) for establishing a target boundary.

## Handle missing or rejected resources

The loader supports standard bitmap, space, reference, Unihex, and TrueType font providers.
It does not execute Mod-defined provider code, custom shaders, operating-system fonts, or an additional emoji renderer.
Translation-key resolution remains the caller's responsibility.

Inspect the snapshot's diagnostics when a document, resource, or provider cannot be used.
Malformed documents and unresolved bundles can leave fonts unavailable, and unknown font IDs produce missing glyphs rather than silently selecting the default font.
A custom-only snapshot does not require a default font.
A load can also fail outright on source enumeration or fatal backend errors.
The [snapshot loading contract](https://gh.s7a.dev/strata/runtime/minecraft/dev.s7a.strata.runtime.minecraft.font/-minecraft-font-snapshot/-companion/load.html) defines recovery and failure behavior.

## Bound resource loading

`MinecraftFontLoadLimits` bounds input size, expansion, record counts, and decoded image payloads.
Keep the defaults unless the tool needs a deliberate tighter or larger budget, and pass the same policy to source constructors that read input before snapshot loading, as the example does.
The [limits reference](https://gh.s7a.dev/strata/runtime/minecraft/dev.s7a.strata.runtime.minecraft.font/-minecraft-font-load-limits/index.html) contains the individual ceilings and defaults.
These are payload and work budgets, not a total JVM or native-memory limit.

Inspect load diagnostics for policy rejections before increasing a limit.
A larger input budget can increase allocation and repeated decoding; raster-cache size does not change which providers are accepted.
Custom sources and decoders remain responsible for allocations they perform before returning data to Strata.
The [bounded source](https://gh.s7a.dev/strata/runtime/minecraft/dev.s7a.strata.runtime.minecraft.font/-minecraft-bounded-font-asset-source/index.html) and [bounded backend](https://gh.s7a.dev/strata/runtime/minecraft/dev.s7a.strata.runtime.minecraft.font/-minecraft-bounded-font-backend/index.html) contracts define allocation ownership and callback rules for implementers.

## Numeric provider settings

Unusual font settings can affect metrics as well as pixels, including missing-glyph fallback when a raster exceeds the native atlas.
Signed and zero TrueType settings follow the selected native provider, while non-finite settings and unsafe native operations are rejected.
Consult [TrueType settings](https://gh.s7a.dev/strata/runtime/minecraft/dev.s7a.strata.runtime.minecraft.font/-minecraft-true-type-settings/index.html) and the compatibility reference above for their individual contracts.
The [numeric font gate](../development/font-verification.md#numeric-provider-evidence) covers atlas rejection, signed and non-finite derived metrics, and target-specific preparation behavior.

## Rendering and verification

Use [headless rendering](headless.md) to consume a completed profile in an offline tool.
The [rendering contract](../development/rendering.md#headless-rasterization) owns sampling and blending, and [font-cache performance](../development/performance.md#resource-font-caches) owns reuse, bounds, and release.
A portable image match alone does not establish equality with Minecraft's native renderer; the [font acceptance gate](../development/font-verification.md#acceptance-evidence) defines the required independent evidence.
