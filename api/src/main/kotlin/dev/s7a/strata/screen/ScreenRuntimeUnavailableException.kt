package dev.s7a.strata.screen

/**
 * Reports that no platform runtime is installed to present a [ScreenDefinition].
 *
 * This failure occurs before ownership transfer, allowing the caller to install the matching runtime, retry, or close the definition.
 */
public class ScreenRuntimeUnavailableException :
    IllegalStateException(
        "No Strata screen runtime is installed for this platform.",
    )
