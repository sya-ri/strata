package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage

/**
 * Shares exact immutable factor tables only during one synchronous render-thread frame preparation.
 * Keys are complete ordered tint snapshots, including the empty sequence's one zero-tint row.
 * At most 256 keys and 1,024 rows belong to this workspace; a full workspace creates an ordinary uncached table.
 * Only integer keys and derived image values are retained, without commands, sources, callbacks or native resources.
 * Existing per-tile reservations conservatively charge every factor payload and their fixed/per-pass owner overhead.
 * The caller drops the workspace on return or failure; no previous frame or authoritative state is retained.
 */
internal class FabricMinecraftCompositionFactors {
    private val tables = HashMap<List<Int>, DrawImage>()
    private var rows = 0

    /**
     * Bounded current-preparation keys, independently of how many tiles borrow a table.
     */
    @get:JvmSynthetic
    internal val retainedKeys: Int
        get() = tables.size

    /**
     * Charged key rows, counting the empty ordered tint sequence's fallback row.
     */
    @get:JvmSynthetic
    internal val retainedRows: Int
        get() = rows

    /**
     * Borrows an existing complete map's table or generates exact Float32 words without changing row numbering.
     * The caller reserves the complete tile before this call; [existing] is an immutable table with the matching key.
     * Generated/existing pixel values never escape as mutable arrays, and failures publish no workspace entry.
     */
    @JvmSynthetic
    internal fun get(
        tints: List<Int>,
        existing: DrawImage? = null,
    ): DrawImage {
        val key = tints.toList()
        val size = IntSize(1536, maxOf(1, key.size))
        require(existing == null || existing.size == size)
        tables[key]?.let { return it }
        val image = existing ?: createDrawImage(size) { x, y -> FabricMinecraftCompositionMap.encode(factor(x, key.getOrNull(y) ?: 0).toRawBits()) }
        if (tables.size < 256 && size.height <= 1024 - rows) {
            tables[key] = image
            rows += size.height
        }
        return image
    }

    private fun factor(
        x: Int,
        tint: Int,
    ): Float {
        val normalized = (x and 255).toFloat() / 255f
        val alpha = (tint ushr 24).toFloat() / 255f
        return when (x / 256) {
            0 -> normalized
            1 -> normalized * alpha
            2 -> 1f - normalized * alpha
            else -> normalized * ((tint ushr ((5 - x / 256) * 8) and 255).toFloat() / 255f)
        }
    }
}
