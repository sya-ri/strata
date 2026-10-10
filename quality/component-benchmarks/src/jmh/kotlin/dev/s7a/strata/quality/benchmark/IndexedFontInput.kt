package dev.s7a.strata.quality.benchmark

/**
 * Frozen index sizes and alias shapes; the empty input occurs once rather than once per alias shape.
 *
 * @property records exact number of asset-index records.
 * @property shared whether every distinct path selects the default-font object's hash.
 */
public enum class IndexedFontInput(
    public val records: Int,
    public val shared: Boolean,
) {
    Empty(0, false),
    Distinct1(1, false),
    Shared1(1, true),
    Distinct128(128, false),
    Shared128(128, true),
    Distinct4096(4096, false),
    Shared4096(4096, true),
    Distinct16384(16384, false),
    Shared16384(16384, true),
}
