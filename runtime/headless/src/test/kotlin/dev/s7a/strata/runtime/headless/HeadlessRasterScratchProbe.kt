@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.headless

import dev.s7a.strata.spi.InternalStrataRuntimeApi

private val rasterScratchEntries = HeadlessRasterScratch::class.java.getDeclaredField("entries").apply { isAccessible = true }
private val sampledWeightFields = SampledSourceWeights::class.java.declaredFields.onEach { it.isAccessible = true }

/**
 * Observes retained primitive float arrays while the fixture is idle, excluding fixed object and reference-array overhead.
 * Reads actual storage rather than estimating it from the runtime's admission counters; closed scratch retains no entries.
 */
internal val HeadlessRasterScratch.retainedBytes: Int
    get() =
        (rasterScratchEntries.get(this) as Map<*, *>?)?.values?.sumOf { weights ->
            sampledWeightFields.sumOf { field ->
                when (val payload = field.get(weights)) {
                    is FloatArray -> payload.size * Float.SIZE_BYTES
                    is Array<*> -> payload.sumOf { (it as FloatArray?)?.size ?: 0 } * Float.SIZE_BYTES
                    else -> 0
                }
            }
        } ?: 0
