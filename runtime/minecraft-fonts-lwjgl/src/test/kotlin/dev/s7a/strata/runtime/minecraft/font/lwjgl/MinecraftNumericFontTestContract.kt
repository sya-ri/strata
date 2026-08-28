package dev.s7a.strata.runtime.minecraft.font.lwjgl

import dev.s7a.strata.runtime.minecraft.font.MinecraftFontCompatibility
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeRasterizer

/**
 * Decodes the existing isolated worker's explicit typed numeric capabilities at the test boundary.
 * No release behavior is inferred from the Minecraft version string or the process's selected dependencies.
 */
internal object MinecraftNumericFontTestContract {
    /**
     * Returns the contract supplied by the existing per-release Gradle worker, failing on incomplete configuration.
     */
    fun compatibility(): MinecraftFontCompatibility =
        MinecraftFontCompatibility(
            rasterizer = MinecraftTrueTypeRasterizer.valueOf(property("strata.fontRasterizer")),
            packFormat = 0,
            providerFilters = property("strata.fontProviderFilters").toBooleanStrict(),
            packOverlays = property("strata.fontPackOverlays").toBooleanStrict(),
            minorPackFormats = property("strata.fontMinorPackFormats").toBooleanStrict(),
            interleavedShadows = property("strata.fontInterleavedShadows").toBooleanStrict(),
            fractionalUnihexAdvance = property("strata.fontFractionalUnihexAdvance").toBooleanStrict(),
            rejectMalformedOverlayMetadata = property("strata.fontRejectMalformedOverlayMetadata").toBooleanStrict(),
            bakedGlyphMetrics = property("strata.fontBakedGlyphMetrics").toBooleanStrict(),
            saturatingCeil = property("strata.fontSaturatingCeil").toBooleanStrict(),
            preparedTextBounds = property("strata.fontPreparedTextBounds").toBooleanStrict(),
        )

    /**
     * Requires one external worker property without introducing a fallback that could silently select another contract.
     */
    fun property(name: String): String = checkNotNull(System.getProperty(name)) { "The numeric font worker requires $name." }
}
