package dev.s7a.strata.ui

import dev.s7a.strata.resource.ResourceId

/**
 * An application-defined screen category; no runtime registration is required.
 */
public data class UiCategory(
    public val id: ResourceId,
)
