package dev.s7a.strata.quality.benchmark

/**
 * Eight complete public snapshot loads over prepared space-provider documents.
 * Direct, empty-target and fully filtered references are unchanged-path controls.
 *
 * @property control whether every expansion keeps its original copy behavior or has no providers to copy.
 */
public enum class FontReferenceLoadCase(
    public val control: Boolean = false,
) {
    /** Sixty-four direct provider declarations without references. */
    Direct(true),

    /** One reference to an empty provider bundle. */
    EmptyTarget(true),

    /** One unfiltered reference to sixty-four unfiltered providers. */
    Alias,

    /** One unfiltered reference preserving sixty-four inner uniform requirements. */
    FilteredLeafAlias,

    /** Seven unfiltered references before sixty-four providers. */
    Chain,

    /** Two unfiltered branches expanding the same sixty-four declarations twice. */
    Diamond,

    /** One nonempty outer filter overriding sixty-four inner requirements. */
    FilteredAlias(true),

    /** Seven alternating filtered and unfiltered references before sixty-four filtered providers. */
    MixedChain,
}
