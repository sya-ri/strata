package dev.s7a.strata.quality.benchmark

/**
 * Distinct public-setter controls: no subscribers, one retained text callback, or one declarative dependency.
 */
public enum class TextAreaObservation {
    Unobserved,
    TextObserver,
    ContentDependency,
}
