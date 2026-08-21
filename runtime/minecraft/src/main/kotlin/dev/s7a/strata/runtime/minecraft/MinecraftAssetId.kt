package dev.s7a.strata.runtime.minecraft

/**
 * Immutable resource-pack asset identifier shared by client and server code.
 *
 * Identifiers contain only the namespace and resource-manager path; they retain no pixels or platform object.
 */
public sealed interface MinecraftAssetId {
    /**
     * Lowercase resource namespace.
     */
    public val namespace: String

    /**
     * Lowercase slash-separated resource path, including the file extension when one is required.
     */
    public val path: String
}
