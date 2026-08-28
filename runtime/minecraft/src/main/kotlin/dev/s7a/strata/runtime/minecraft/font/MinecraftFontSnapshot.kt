package dev.s7a.strata.runtime.minecraft.font

import dev.s7a.strata.resource.ResourceId
import java.util.Collections

/**
 * Immutable detached font-provider graph loaded directly from a resource-pack stack.
 * All referenced bytes and sparse Unihex rows are owned snapshots; no source, stream, native face, or game object is retained.
 * The same snapshot may be shared across threads and independent engines.
 *
 * @property compatibility typed release capabilities used during loading and rasterization.
 * @property options captured font-selection and language-direction options.
 * @param fonts resolved ordered providers for each available family.
 * @param diagnostics immutable resource-load diagnostics.
 * @property limits immutable loading and image-allocation ceilings retained without mutable counters.
 */
public class MinecraftFontSnapshot internal constructor(
    public val compatibility: MinecraftFontCompatibility,
    public val options: MinecraftFontOptions,
    fonts: Map<ResourceId, List<FontProviderEntry>>,
    diagnostics: List<MinecraftFontDiagnostic>,
    internal val limits: MinecraftFontLoadLimits,
) {
    /**
     * Preserves the existing internal construction boundary using default allocation ceilings.
     * Inputs and immutable ownership follow the primary constructor.
     */
    internal constructor(
        compatibility: MinecraftFontCompatibility,
        options: MinecraftFontOptions,
        fonts: Map<ResourceId, List<FontProviderEntry>>,
        diagnostics: List<MinecraftFontDiagnostic>,
    ) : this(compatibility, options, fonts, diagnostics, MinecraftFontLoadLimits())

    /**
     * Copied resolved providers, shared read-only by engines without retaining loader state.
     */
    internal val fonts: Map<ResourceId, List<FontProviderEntry>> =
        Collections.unmodifiableMap(fonts.mapValues { (_, entries) -> Collections.unmodifiableList(entries.toList()) })

    /**
     * Available font families whose complete reference graphs resolved.
     */
    public val fontIds: Set<ResourceId> = Collections.unmodifiableSet(LinkedHashSet(this.fonts.keys))

    /**
     * Detached diagnostics for skipped documents and unresolved bundles, in deterministic load order.
     */
    public val diagnostics: List<MinecraftFontDiagnostic> = Collections.unmodifiableList(diagnostics.toList())

    /**
     * Owns synchronous resource-pack snapshot loading without a Minecraft dependency.
     */
    public companion object {
        /**
         * Loads all font definitions and referenced assets in increasing pack priority order.
         * Resource filters and selected overlays apply before font-provider stacks are merged.
         * Invalid source metadata is diagnosed and excluded according to the selected compatibility contract; sources without metadata remain supported.
         * Malformed font documents are diagnosed and skipped; an asset or reference failure invalidates its containing font bundle.
         * Default [MinecraftFontLoadLimits] bound source enumeration, input, decompression, and later image allocation.
         *
         * @param sources source list from lowest to highest priority, never retained.
         * @param compatibility typed target capabilities.
         * @param options immutable provider-filter and language-direction options.
         * @return a thread-safe detached font snapshot.
         * @throws Throwable when pack enumeration or a fatal loading failure prevents snapshot creation.
         */
        @JvmStatic
        @JvmOverloads
        public fun load(
            sources: List<MinecraftFontAssetSource>,
            compatibility: MinecraftFontCompatibility,
            options: MinecraftFontOptions = MinecraftFontOptions(),
        ): MinecraftFontSnapshot = load(sources, compatibility, options, MinecraftFontLoadLimits())

        /**
         * Loads a detached resource stack under caller-selected inclusive input and allocation ceilings.
         * A source enumeration limit excludes that source with a diagnostic; malformed or oversized documents are skipped.
         * Provider and graph limits invalidate the containing bundle while other inputs may use remaining capacity.
         * Custom sources and decoders must bound their own allocations; their returned payloads are also checked before loader copying or retention.
         *
         * @param sources stable source list in increasing priority order, never retained.
         * @param compatibility typed target capabilities.
         * @param options immutable provider-filter and language-direction options.
         * @param limits immutable ceilings shared across this load and retained only for later image checks.
         * @return thread-safe snapshot without streams, sources, native faces, or mutable budget counters.
         * @throws IllegalArgumentException when the source list itself exceeds its ceiling.
         * @throws Throwable when ordinary source enumeration or a fatal loading failure prevents creation.
         */
        @JvmStatic
        public fun load(
            sources: List<MinecraftFontAssetSource>,
            compatibility: MinecraftFontCompatibility,
            options: MinecraftFontOptions,
            limits: MinecraftFontLoadLimits,
        ): MinecraftFontSnapshot {
            requireFontLimit(sources.size.toLong(), limits.maxSources.toLong(), "snapshot sources")
            return FontSnapshotLoader(sources.toList(), compatibility, options, limits).load()
        }
    }
}
