package dev.s7a.strata.performance

/**
 * Deterministic work and retention assertions, independent of elapsed time.
 *
 * @param exact counters that must equal the declared value.
 * @param maximum counters that must stay within the declared bound.
 * @param minimum counters that must establish required actual work.
 */
public class WorkExpectation(
    exact: Map<String, Long> = emptyMap(),
    maximum: Map<String, Long> = emptyMap(),
    minimum: Map<String, Long> = emptyMap(),
) {
    private val exact = exact.toMap()
    private val maximum = maximum.toMap()
    private val minimum = minimum.toMap()

    init {
        require((this.exact.entries + this.maximum.entries + this.minimum.entries).all { it.key.isNotBlank() && 0 <= it.value })
        require(this.exact.all { (name, value) -> this.maximum[name]?.let { value <= it } ?: true })
        require(this.exact.all { (name, value) -> this.minimum[name]?.let { it <= value } ?: true })
        require(this.minimum.all { (name, value) -> this.maximum[name]?.let { value <= it } ?: true })
    }

    /**
     * Missing metrics and overflow cannot satisfy an expectation.
     */
    public fun verify(
        actual: Map<String, Long?>,
        overflowed: Boolean = false,
    ) {
        check(overflowed.not()) { "Performance diagnostics overflowed" }
        exact.forEach { (name, expected) -> check(actual[name] == expected) { "$name: expected $expected, got ${actual[name]}" } }
        maximum.forEach { (name, bound) ->
            val value = checkNotNull(actual[name]) { "Missing performance metric: $name" }
            check(0 <= value && value <= bound) { "$name: expected at most $bound, got $value" }
        }
        minimum.forEach { (name, bound) ->
            val value = checkNotNull(actual[name]) { "Missing performance metric: $name" }
            check(bound <= value) { "$name: expected at least $bound, got $value" }
        }
    }
}
