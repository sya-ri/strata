package dev.s7a.strata.quality.benchmark

/**
 * Genuine downstream CPU descriptions, with all complete current source/factor/map metadata.
 */
public fun SamplingCoverageRuntime.portable(inputs: Any): List<Any> = (portableGetter.invoke(inputs) as List<*>).map(::checkNotNull)

/**
 * Genuine requested immutable source identities; their native pinning remains a separate loaded gate.
 */
public fun SamplingCoverageRuntime.sources(inputs: Any): List<Any> = (sampledGetter.invoke(inputs) as List<*>).map(::checkNotNull)

/**
 * Actual byte/image capacity fallback count after full composition preparation.
 */
public fun SamplingCoverageRuntime.capacity(inputs: Any): Long = capacityGetter.invoke(inputs) as Long

/**
 * Actual unsupported sampling count after full composition preparation.
 */
public fun SamplingCoverageRuntime.ineligible(inputs: Any): Long = ineligibleGetter.invoke(inputs) as Long

/**
 * Actual unsupported tint fallback count.
 */
public fun SamplingCoverageRuntime.tintFallback(inputs: Any): Long = tintFallbackGetter.invoke(inputs) as Long
