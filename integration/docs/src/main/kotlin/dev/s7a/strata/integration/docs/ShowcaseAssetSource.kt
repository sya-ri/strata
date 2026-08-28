package dev.s7a.strata.integration.docs

import dev.s7a.strata.runtime.minecraft.font.MinecraftBoundedFontAssetSource
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontLoadLimits

/**
 * Records the exact bounded source bytes consumed by one synchronous showcase load.
 * The wrapper closes no borrowed source, retains only path hashes, and verifies indexed objects against their declared SHA-1.
 * Its owner must discard it after producing detached immutable evidence.
 */
internal class ShowcaseAssetSource(
    private val delegate: MinecraftBoundedFontAssetSource,
    private val indexedHashes: Map<String, String> = emptyMap(),
) : MinecraftBoundedFontAssetSource {
    private val reads = linkedMapOf<String, String?>()
    private var pathHash: String? = null
    private var bytesRead = 0L

    override val name: String = delegate.name

    override fun paths(): Set<String> = paths(MinecraftFontLoadLimits())

    override fun paths(limits: MinecraftFontLoadLimits): Set<String> = paths(limits) {}

    override fun paths(
        limits: MinecraftFontLoadLimits,
        onEntryExamined: () -> Unit,
    ): Set<String> =
        delegate.paths(limits, onEntryExamined).also { paths ->
            val hash = ShowcaseAssetIntegrity.sha256(paths.sorted().joinToString("\n").toByteArray())
            val previous = pathHash
            require(previous == null || previous == hash) { "Showcase resource enumeration changed during loading." }
            pathHash = hash
        }

    override fun read(path: String): ByteArray? = read(path, MinecraftFontLoadLimits())

    override fun read(
        path: String,
        limits: MinecraftFontLoadLimits,
    ): ByteArray? {
        val bytes = delegate.read(path, limits)
        val hash = bytes?.let(ShowcaseAssetIntegrity::sha256)
        if (reads.containsKey(path)) {
            require(reads[path] == hash) { "A showcase resource changed during loading." }
        } else {
            bytesRead = Math.addExact(bytesRead, bytes?.size?.toLong() ?: 0L)
            require(bytesRead <= limits.maxInputBytes) { "Showcase resource bytes exceed their aggregate ceiling." }
            reads[path] = hash
        }
        indexedHashes[path]?.let { expected ->
            require(bytes != null && ShowcaseAssetIntegrity.sha1(bytes) == expected) { "A showcase asset object differs from its declared index hash." }
        }
        return bytes
    }

    /**
     * Rereads consumed resources, including absent metadata, and rejects concurrent mutation before publication.
     * Streams remain bounded and close synchronously; no decoded image or font is reconstructed.
     */
    fun verifyStable(limits: MinecraftFontLoadLimits) {
        reads.keys.toList().forEach { path -> read(path, limits) }
        if (pathHash != null) paths(limits)
    }

    /**
     * Returns detached logical input identities without filesystem paths or digest suffixes in the keys.
     */
    fun hashes(): Map<String, String> =
        buildMap {
            pathHash?.let { hash -> put("paths.$name", hash) }
            reads.forEach { (path, hash) ->
                if (hash != null) put("resource.$name.$path", hash)
            }
        }
}
