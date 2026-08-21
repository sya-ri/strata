package dev.s7a.strata.runtime.minecraft

/**
 * Creates typed resource-pack identifiers without exposing an implementation constructor.
 */
public object MinecraftAssets {
    /**
     * Creates one immutable resource identifier usable by common, client, and server code.
     *
     * @param namespace lowercase namespace matching `[a-z0-9_.-]+`.
     * @param path lowercase slash-separated path matching `[a-z0-9/._-]+`.
     * @return an immutable structural identifier.
     * @throws IllegalArgumentException when either part is empty or contains unsupported characters.
     */
    @JvmStatic
    public fun resource(
        namespace: String,
        path: String,
    ): MinecraftAssetId {
        require(namespacePattern.matches(namespace)) { "Minecraft asset namespace is invalid." }
        require(pathPattern.matches(path)) { "Minecraft asset path is invalid." }
        return AssetId(namespace, path)
    }

    private data class AssetId(
        override val namespace: String,
        override val path: String,
    ) : MinecraftAssetId

    private val namespacePattern: Regex = Regex("[a-z0-9_.-]+")
    private val pathPattern: Regex = Regex("(?!\\.{1,2}(?:/|$))(?!.*?/\\.{1,2}(?:/|$))[a-z0-9._-]+(?:/[a-z0-9._-]+)*")
}
