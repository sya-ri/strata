package dev.s7a.strata.component

/**
 * Identifies one tile in a source-owned resolution level.
 *
 * Columns and rows use the mathematical grid whose origin is content coordinate zero.
 * Negative content coordinates therefore map with floor division rather than truncation toward zero.
 * The identifier is meaningful only with the identity of its [TiledImageSource].
 *
 * @property level zero-based index in the source's finest-to-coarsest level list.
 * @property column signed horizontal tile coordinate.
 * @property row signed vertical tile coordinate.
 * @throws IllegalArgumentException when [level] is negative.
 */
public data class TiledImageTileId(
    public val level: Int,
    public val column: Long,
    public val row: Long,
) {
    init {
        require(0 <= level) { "Tiled image level indexes must be non-negative." }
    }
}
