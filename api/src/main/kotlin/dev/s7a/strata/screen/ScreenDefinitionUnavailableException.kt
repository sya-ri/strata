package dev.s7a.strata.screen

/**
 * Reports that a one-shot [ScreenDefinition] can no longer transfer its payload.
 *
 * The definition is either already owned by a runtime or was explicitly closed.
 * This failure never transfers ownership and is safe to observe from any calling thread.
 *
 * @param message stable diagnostic describing the unavailable state.
 */
public class ScreenDefinitionUnavailableException(
    message: String,
) : IllegalStateException(message)
