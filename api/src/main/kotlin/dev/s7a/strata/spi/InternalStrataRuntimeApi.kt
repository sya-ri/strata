package dev.s7a.strata.spi

/**
 * Opt-in marker for privileged Strata runtime bridge functions.
 *
 * A separate runtime module uses this narrow boundary to validate ownership and invoke typed element and node operations.
 *
 * The bridge requires no friend access, reflective discovery, or component registry.
 * Opting in grants access to bridge functions; it does not itself enforce thread confinement.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
@RequiresOptIn(
    message = "Internal Strata runtime bridge is an opt-in integration contract.",
    level = RequiresOptIn.Level.ERROR,
)
public annotation class InternalStrataRuntimeApi
