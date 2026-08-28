package dev.s7a.strata.runtime.minecraft.font

import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections

/**
 * Offline source for a Minecraft asset index and its already-downloaded object directory.
 * Construction snapshots the index; object bytes are read synchronously without downloading or starting the game.
 * Resolved object paths must remain inside the supplied object directory.
 *
 * @param index JSON asset-index file.
 * @param objects directory containing hash-prefix folders and content-addressed objects.
 * @property name diagnostic label.
 * @param limits ceilings applied before the asset index is read and copied.
 * @throws IllegalArgumentException when an entry contains an invalid path or hash.
 * @throws Throwable when the index or an object cannot be read.
 */
public class MinecraftIndexedFontAssetSource(
    index: Path,
    objects: Path,
    override val name: String,
    limits: MinecraftFontLoadLimits,
) : MinecraftBoundedFontAssetSource {
    private val objects: Path = objects.toRealPath()
    private val entries: Map<String, String>

    init {
        val maximum = minOf(limits.maxDocumentBytes.toLong(), limits.maxInputBytes).toInt()
        requireFontLimit(Files.size(index), maximum.toLong(), "asset index bytes")
        val bytes = Files.newInputStream(index).use { input -> input.readMinecraftFontBytes(maximum) }
        val document = FontJson.document(bytes, limits)
        val objectsValue = FontJson.objectValue(document.get("objects"))
        requireFontLimit(objectsValue.size().toLong(), limits.maxSourceEntries.toLong(), "asset index entries")
        val hashes = LinkedHashMap<String, String>()
        for ((path, value) in objectsValue.entrySet()) {
            requireFontLimit(path.length.toLong() + 7, limits.maxPathLength.toLong(), "asset index path length")
            val relative = "assets/${path.checkedFontSourcePath()}"
            val hash = FontJson.string(FontJson.objectValue(value).get("hash"))
            require(Regex("[0-9a-f]{40}").matches(hash)) { "Asset index object hash is invalid." }
            hashes[relative] = hash
        }
        entries = Collections.unmodifiableMap(hashes)
    }

    /**
     * Captures an asset index using the default loading ceilings while retaining the existing constructor signature.
     * Inputs, ownership, and failures follow the primary constructor.
     */
    public constructor(index: Path, objects: Path, name: String = index.toString()) : this(index, objects, name, MinecraftFontLoadLimits())

    override fun paths(): Set<String> = entries.keys

    override fun read(path: String): ByteArray? = read(path, MinecraftFontLoadLimits())

    override fun read(
        path: String,
        limits: MinecraftFontLoadLimits,
    ): ByteArray? {
        requireFontLimit(path.length.toLong(), limits.maxPathLength.toLong(), "asset index path length")
        val hash = entries[path.checkedFontSourcePath()] ?: return null
        val candidate = objects.resolve(hash.substring(0, 2)).resolve(hash).normalize()
        require(candidate.startsWith(objects)) { "Asset index path escapes the object directory." }
        val resolved = candidate.toRealPath()
        require(resolved.startsWith(objects)) { "Asset object symbolic link escapes the object directory." }
        requireFontLimit(Files.size(resolved), limits.maxAssetBytes.toLong(), "asset object bytes")
        return Files.newInputStream(resolved).use { input -> input.readMinecraftFontBytes(limits.maxAssetBytes) }
    }
}
