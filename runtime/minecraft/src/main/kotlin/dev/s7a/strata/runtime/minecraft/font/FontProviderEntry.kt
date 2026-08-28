package dev.s7a.strata.runtime.minecraft.font

/**
 * Immutable provider identity, resource provenance, and resolved option requirements.
 *
 * @property identity snapshot-local declaration identity, preserved across reference expansion.
 * @property provider detached typed provider data.
 * @property filter option requirements whose outer references override inner requirements.
 * @property source originating pack label for diagnostics.
 */
internal data class FontProviderEntry(
    val identity: Int,
    val provider: FontProvider,
    val filter: Map<FontOption, Boolean>,
    val source: String,
)
