package dev.s7a.strata.projection

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Opt-in typed projection of an element or active modifier description.
 * Properties and the encoder remain local; only the encoder's detached value crosses the transport.
 * Local rendering never requires a projection or a remote registry entry.
 */
public class DeclarationProjection<P> private constructor(
    public val type: ProjectionType,
    private val properties: P,
    public val inputEnabled: Boolean,
    private val encode: (P, ProjectionScope) -> ProjectionValue,
    private val fixed: ProjectionValue?,
) {
    /**
     * Retains an ordinary caller-provided encoder without claiming its output or scope use is fixed.
     * The original properties, enabled flag and owner-thread encoding behavior remain unchanged.
     */
    public constructor(
        type: ProjectionType,
        properties: P,
        inputEnabled: Boolean = true,
        encode: (P, ProjectionScope) -> ProjectionValue,
    ) : this(type, properties, inputEnabled, encode, null)

    /**
     * Runtime proof containing the immutable value returned without a caller encoder or scope operation.
     * Ordinary caller-provided encoders never provide this proof, even when their previous output was equal.
     */
    @InternalStrataRuntimeApi
    public val fixedValue: ProjectionValue? get() = fixed

    /**
     * Produces one detached property snapshot on the session owner thread.
     */
    public fun encode(scope: ProjectionScope): ProjectionValue = encode(properties, scope)

    /**
     * Privileged construction of a fixed detached projection; ordinary encoders retain their existing constructor.
     */
    @InternalStrataRuntimeApi
    public companion object {
        /**
         * Returns a projection that always exports [value] without accessing its scope or authoritative state.
         * The value follows [ProjectionValue]'s immutable collection and byte ownership contracts.
         */
        public fun <P : ProjectionValue> fixed(
            type: ProjectionType,
            value: P,
            inputEnabled: Boolean = true,
        ): DeclarationProjection<P> = DeclarationProjection(type, value, inputEnabled, { properties, _ -> properties }, value)
    }
}
