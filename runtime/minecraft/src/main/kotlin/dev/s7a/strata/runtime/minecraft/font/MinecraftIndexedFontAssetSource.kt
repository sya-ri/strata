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
 * @throws IllegalArgumentException when an entry contains an invalid path or hash.
 * @throws Throwable when the index or an object cannot be read.
 */
public class MinecraftIndexedFontAssetSource(
    index: Path,
    objects: Path,
    override val name: String = index.toString(),
) : MinecraftFontAssetSource {
    private val objects: Path = objects.toRealPath()
    private val entries: Map<String, String>

    init {
        val document = FontJson.document(Files.readAllBytes(index))
        val objectsValue = FontJson.objectValue(document.get("objects"))
        val hashes = LinkedHashMap<String, String>()
        for ((path, value) in objectsValue.entrySet()) {
            val relative = "assets/${path.checkedFontSourcePath()}"
            val hash = FontJson.string(FontJson.objectValue(value).get("hash"))
            require(Regex("[0-9a-f]{40}").matches(hash)) { "Asset index object hash is invalid." }
            hashes[relative] = hash
        }
        entries = Collections.unmodifiableMap(hashes)
    }

    override fun paths(): Set<String> = entries.keys

    override fun read(path: String): ByteArray? {
        val hash = entries[path.checkedFontSourcePath()] ?: return null
        val candidate = objects.resolve(hash.substring(0, 2)).resolve(hash).normalize()
        require(candidate.startsWith(objects)) { "Asset index path escapes the object directory." }
        val resolved = candidate.toRealPath()
        require(resolved.startsWith(objects)) { "Asset object symbolic link escapes the object directory." }
        return Files.readAllBytes(resolved)
    }
}
