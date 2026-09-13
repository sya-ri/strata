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
 * Loads a shareable snapshot while the caller keeps the exact-target files stable.
 * The custom pack has highest priority; [limits] applies to both index reads and loading.
 * Invalid documents produce diagnostics, enumeration failures propagate, and streams close before return.
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
