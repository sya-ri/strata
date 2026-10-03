package dev.s7a.strata.gradle.fabric

import kotlin.math.ceil

/**
 * Bound new admission by available physical RAM and CPU; unknown memory falls back to one client.
 */
internal fun fabricClientCapacity(
    freeMemoryBytes: Long,
    reservedMemoryBytes: Long,
    clientMemoryBytes: Long,
    processors: Int,
    cpuLoad: Double,
    maximum: Int,
): Int {
    require(0 < clientMemoryBytes && 0 < maximum && 0 < processors && 0 <= reservedMemoryBytes)
    if (freeMemoryBytes < 0) return 1
    val memoryLimit =
        ((freeMemoryBytes - reservedMemoryBytes).coerceAtLeast(0) / clientMemoryBytes)
            .coerceAtMost(maximum.toLong())
            .toInt()
    val cpuLimit =
        if (cpuLoad in 0.0..1.0) {
            ceil(processors * (1.0 - cpuLoad) / 2).toInt().coerceAtLeast(1)
        } else {
            maximum
        }
    return minOf(maximum, memoryLimit, cpuLimit)
}
