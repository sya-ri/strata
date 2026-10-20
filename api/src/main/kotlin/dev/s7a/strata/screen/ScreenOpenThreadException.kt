package dev.s7a.strata.screen

import dev.s7a.strata.ui.UiDefinition

/**
 * Reports that a runtime cannot present a [UiDefinition] from the calling thread.
 *
 * Runtime implementations must raise this failure before transferring the definition, allowing the caller to retry on the platform owner thread or close it.
 *
 * @param message platform diagnostic describing the required thread.
 */
public class ScreenOpenThreadException(
    message: String,
) : IllegalStateException(message)
