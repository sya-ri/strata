package dev.s7a.strata.input

/**
 * Result of pointer handling.
 */
public enum class InputResult {
    /**
     * The event was handled and dispatch stops.
     */
    Consumed,

    /**
     * The event was not handled and dispatch continues.
     */
    Ignored,
}
