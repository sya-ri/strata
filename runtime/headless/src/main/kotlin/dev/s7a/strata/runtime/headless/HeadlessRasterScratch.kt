package dev.s7a.strata.runtime.headless

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Reuses exact source-color weights during one synchronous rasterization or native frame preparation.
 * Calls belong to the constructing thread. The key is the complete ARGB tint; destination, cutoff and geometry are not retained.
 * At most four least-recently-used tints each retain sixteen alpha rows, with less than 256 KiB of primitive storage in total.
 * No image, command or destination is retained. Close releases every table and permanently rejects further use.
 */
@InternalStrataRuntimeApi
public class HeadlessRasterScratch : AutoCloseable {
    private val owner = Thread.currentThread()
    private var closed = false
    private var entries: LinkedHashMap<Int, SampledSourceWeights>? = null

    /**
     * Borrows one tint's exact weights on the owner thread, promoting an existing entry before LRU eviction.
     */
    internal fun weights(tint: Int): SampledSourceWeights {
        requireOpen()
        val current = entries ?: LinkedHashMap<Int, SampledSourceWeights>(4, 0.75f, true).also { entries = it }
        current[tint]?.let { return it }
        if (current.size == 4) current.remove(current.keys.first())
        return SampledSourceWeights(tint).also { current[tint] = it }
    }

    /**
     * Rejects expired or concurrently borrowed scratch before any raster storage is changed.
     */
    internal fun requireOpen() {
        check(Thread.currentThread() === owner) { "Raster scratch belongs to its constructing thread." }
        check(closed.not()) { "Raster scratch is closed." }
    }

    /**
     * Current primitive payload used by deterministic retention tests; excludes fixed object and map overhead.
     */
    internal val retainedBytes: Int
        get() = entries?.values?.sumOf { it.retainedBytes } ?: 0

    /**
     * Severs all derived storage on the owner thread; repeated close is harmless.
     */
    override fun close() {
        check(Thread.currentThread() === owner) { "Raster scratch belongs to its constructing thread." }
        entries = null
        closed = true
    }
}
