package dev.s7a.strata.runtime.headless

/**
 * Exact destination-independent Float products for one ARGB tint, owned exclusively by raster scratch.
 * Each lazily admitted alpha row stores all three source channels without eight-bit quantization or reassociation.
 * Sixteen rows bound work and storage; additional alpha values use the original scalar multiplication.
 */
internal class SampledSourceWeights(
    tint: Int,
) {
    private val alpha = (tint ushr 24).toFloat() / 255f
    private val sourceAlpha = FloatArray(256) { it.toFloat() / 255f * alpha }
    private val inverseAlpha = FloatArray(256) { 1f - sourceAlpha[it] }
    private val tinted =
        FloatArray(768) { index ->
            val shift = (2 - index / 256) * 8
            val channelTint = (tint ushr shift and 255).toFloat() / 255f
            (index and 255).toFloat() / 255f * channelTint
        }
    private val rows = arrayOfNulls<FloatArray>(256)
    private var rowCount = 0

    /**
     * Returns the original normalized source-alpha multiplication.
     */
    fun alpha(value: Int): Float = sourceAlpha[value]

    /**
     * Returns the original subtraction after source-alpha multiplication.
     */
    fun inverse(value: Int): Float = inverseAlpha[value]

    /**
     * Returns one unquantized source contribution in the original multiplication order.
     */
    fun channel(
        sourceAlpha: Int,
        sourceChannel: Int,
        shift: Int,
    ): Float {
        val index = (2 - shift / 8) * 256 + sourceChannel
        val row =
            rows[sourceAlpha] ?: if (rowCount < 16) {
                FloatArray(768) { tinted[it] * this.sourceAlpha[sourceAlpha] }.also {
                    rows[sourceAlpha] = it
                    rowCount += 1
                }
            } else {
                null
            }
        return row?.get(index) ?: tinted[index] * this.sourceAlpha[sourceAlpha]
    }

    /**
     * Primitive arrays occupy at most 54,272 bytes per tint, excluding the fixed reference array.
     */
    val retainedBytes: Int
        get() = (256 * 2 + 768 + rowCount * 768) * Float.SIZE_BYTES
}
