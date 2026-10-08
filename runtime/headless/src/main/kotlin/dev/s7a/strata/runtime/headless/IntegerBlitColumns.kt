package dev.s7a.strata.runtime.headless

/**
 * Builds exact clipped horizontal coordinates for one integer-blit invocation.
 * Eligible logical or physical coverage has at least four rows and 4096 cells, with at most 16384 columns.
 * Each call owns at most 64 KiB of primitive payload and retains no callback, image, command or output.
 * Scalar fallbacks allocate no map and evaluate no coordinates during setup; failure publishes no partial map.
 */
internal object IntegerBlitColumns {
    /**
     * Calls [sourceAt] once per absolute column when repeated coverage admits bounded temporary storage.
     * The caller supplies the original validated integer equation and exclusively owns the returned array.
     * All legal coordinates are total after command preflight; precomputation therefore moves no arithmetic failure.
     */
    inline fun create(
        left: Int,
        right: Int,
        rows: Int,
        sourceAt: (Int) -> Int,
    ): IntArray? {
        val width = right - left
        if (rows < 4 || width <= 0 || 16384 < width || width.toLong() * rows < 4096L) return null
        return IntArray(width) { offset -> sourceAt(left + offset) }
    }
}
