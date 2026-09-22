package dev.s7a.strata.projection

/**
 * Opt-in typed projection of an element or active modifier description.
 * Properties and the encoder remain local; only the encoder's detached value crosses the transport.
 * Local rendering never requires a projection or a remote registry entry.
 */
public class DeclarationProjection<P>(
    public val type: ProjectionType,
    private val properties: P,
    public val inputEnabled: Boolean = true,
    private val encode: (P, ProjectionScope) -> ProjectionValue,
) {
    /**
     * Produces one detached property snapshot on the session owner thread.
     */
    public fun encode(scope: ProjectionScope): ProjectionValue = encode(properties, scope)
}
