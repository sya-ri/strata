package dev.s7a.strata.dsl

/**
 * Marks the receiver scopes that participate in the Strata declarative UI builder.
 * The marker prevents declarations from unrelated nested Strata scopes from being used accidentally through an outer receiver.
 */
@DslMarker
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
public annotation class StrataDsl
