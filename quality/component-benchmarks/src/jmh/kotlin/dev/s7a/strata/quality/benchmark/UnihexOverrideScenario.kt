package dev.s7a.strata.quality.benchmark

/**
 * Frozen synthetic custom-pack inputs, including admission and unchanged-work controls.
 * These inputs establish no claim about typical vanilla override distributions.
 *
 * @property ranges declared ordered width ranges, independent of present glyph count.
 * @property cacheEntries configured public raster-entry ceiling.
 * @property queries number of present or deliberately absent scalars per complete batch.
 */
public enum class UnihexOverrideScenario(
    public val ranges: Int,
    public val cacheEntries: Int = 0,
    public val queries: Int = 64,
) {
    /**
     * No override selection work.
     */
    Empty(0),

    /**
     * One range uses the original loop.
     */
    Single(1),

    /**
     * Small range list below admission.
     */
    Small(8),

    /**
     * Earliest declaration wins without constructing an index.
     */
    First(128),

    /**
     * A middle declaration wins after preceding misses.
     */
    Middle(128),

    /**
     * Last declaration wins for every cold glyph.
     */
    Last(128),

    /**
     * Present glyphs keep their natural bounds after a complete range miss.
     */
    None(128),

    /**
     * Absent sparse glyphs return before width selection.
     */
    Absent(128),

    /**
     * Unsorted nested overlaps and identical declarations retain first priority.
     */
    Overlap(128),

    /**
     * Larger admitted range list.
     */
    Large(1_024),

    /**
     * Four queries expose short-owner construction costs.
     */
    Few(128, queries = 4),

    /**
     * Eight raster entries cause repeated eviction across 64 scalars.
     */
    Evicted(128, cacheEntries = 8),

    /**
     * Fully warm raster hits skip selection entirely.
     */
    Cached(128, cacheEntries = 4_096),

    /**
     * Above the private per-provider storage ceiling, preserving the original loop.
     */
    Unadmitted(8_193),

    /**
     * Supplementary scalar intervals with original UTF-16 text consumption.
     */
    Supplementary(128),

    /**
     * Negative and out-of-bitfield pixel bounds preserve transparent padding.
     */
    Padding(128),
}
