package dev.s7a.strata.runtime.minecraft.font

/**
 * Immutable identity of one native face descriptor within a detached snapshot.
 * Resource identity and exact settings determine native input; provider filters and skipped scalars do not.
 * The key contains no source, backend, face, or mutable cache and may be shared with snapshot-owned data.
 *
 * @property resource immutable selected bytes, compared by their snapshot-local identity.
 * @property settings exact finite provider settings, preserving signed zero.
 */
internal data class FontFaceKey(
    val resource: FontResource,
    val settings: MinecraftTrueTypeSettings,
)
