package dev.s7a.strata.runtime.minecraft.font

import dev.s7a.strata.resource.ResourceId

/**
 * Immutable typed provider descriptions containing only detached resource data.
 */
internal sealed interface FontProvider {
    /**
     * A bitmap sheet and its scalar-to-cell mapping, validated before publication.
     */
    data class Bitmap(
        val resource: FontResource,
        val height: Int,
        val ascent: Int,
        val columns: Int,
        val rows: Int,
        val cells: Map<Int, Int>,
    ) : FontProvider

    /**
     * Explicit advances without glyph pixels.
     */
    data class Space(
        val advances: Map<Int, Float>,
    ) : FontProvider

    /**
     * A font-family reference whose providers expand at this exact position.
     */
    data class Reference(
        val font: ResourceId,
    ) : FontProvider

    /**
     * Detached sparse Unihex rows and inclusive width overrides.
     */
    data class Unihex(
        val glyphs: FontUnihexData,
        val overrides: List<WidthOverride>,
    ) : FontProvider

    /**
     * Detached TrueType bytes, native settings, and skipped scalar values.
     */
    data class TrueType(
        val resource: FontResource,
        val settings: MinecraftTrueTypeSettings,
        val skipped: Set<Int>,
    ) : FontProvider

    /**
     * An asset failure that invalidates the complete containing font bundle.
     */
    data class Failed(
        val diagnostic: MinecraftFontDiagnostic,
    ) : FontProvider

    /**
     * Inclusive scalar range and inclusive pixel bounds from a Unihex override.
     */
    data class WidthOverride(
        val first: Int,
        val last: Int,
        val left: Int,
        val right: Int,
    )
}
