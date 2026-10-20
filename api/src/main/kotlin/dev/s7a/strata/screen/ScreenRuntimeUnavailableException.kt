package dev.s7a.strata.screen

import dev.s7a.strata.ui.UiDefinition

/**
 * Reports that no platform runtime is installed to present a [UiDefinition].
 *
 * This failure occurs before ownership transfer, allowing the caller to install the matching runtime, retry, or close the definition.
 */
public class ScreenRuntimeUnavailableException :
    IllegalStateException(
        "No Strata screen runtime is installed for this platform.",
    )
