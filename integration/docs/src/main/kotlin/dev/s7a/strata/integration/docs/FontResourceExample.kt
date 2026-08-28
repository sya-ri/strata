package dev.s7a.strata.integration.docs

// showcase-source-begin:font-resources
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
// showcase-source-end:font-resources
