package dev.s7a.strata.performance

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.InputStream
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipFile

/**
 * Bounded actual-file and class-tree verification shared by JVM collection and evidence processing.
 */
internal object JvmEvidenceFiles {
    /**
     * Loads one bounded JSON document; shape validation belongs to its evidence contract.
     */
    internal fun document(path: Path): JsonObject = snapshot(path).first

    /**
     * Parses one bounded strict UTF-8 snapshot and binds its receipt to those exact bytes.
     */
    internal fun snapshot(path: Path): Pair<JsonObject, JsonObject> {
        val (element, receipt) = elementSnapshot(path)
        return element.asJsonObject to receipt
    }

    /**
     * Reads a raw result array once and validates the receipt against those exact bytes before admission.
     */
    internal fun array(
        path: Path,
        expected: String,
    ): JsonArray {
        validateHash(expected)
        val (element, receipt) = elementSnapshot(path)
        require(receipt.textField("sha256").contentEquals(expected)) { "Preserved raw result differs" }
        return element.asJsonArray
    }

    private fun elementSnapshot(path: Path): Pair<JsonElement, JsonObject> {
        val maximum = 64 * 1024 * 1024
        require(Files.size(path) <= maximum) { "Oversized evidence document: $path" }
        val bytes = Files.newInputStream(path).use { it.readNBytes(maximum + 1) }
        require(bytes.size <= maximum) { "Evidence document grew beyond its bound" }
        val decoder =
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        val report = JsonParser.parseString(decoder.decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF"))
        val receipt =
            JsonObject().apply {
                addProperty("path", path.toAbsolutePath().normalize().toString())
                addProperty("sha256", EntryIdentity.sha256(bytes.inputStream()))
            }
        return report to receipt
    }

    /**
     * Checks a canonical lower-case SHA-256 against the actual preserved file.
     */
    internal fun verifyFile(
        path: Path,
        expected: String,
    ) {
        validateHash(expected)
        require(Files.size(path) <= 64L * 1024 * 1024) { "Oversized preserved artifact: $path" }
        require(ArtifactIdentity.file(path).contentEquals(expected)) { "Preserved evidence artifact differs: $path" }
    }

    /**
     * Rejects absent, noncanonical or malformed artifact identities before comparison.
     */
    internal fun validateHash(hash: String) {
        require(hash.length == 64 && hash.all { it in '0'..'9' || it in 'a'..'f' }) { "Invalid SHA-256 identity" }
    }

    /**
     * Resolves only a direct preserved-file name, excluding traversal, authorities and platform separators.
     */
    internal fun preservedPath(
        directory: Path,
        filename: String,
    ): Path {
        require(filename.isNotBlank() && filename !in setOf(".", "..") && filename.none { it in "/\\:" || it.isISOControl() }) { "Unsafe preserved archive name: $filename" }
        return directory.resolve(filename)
    }

    /**
     * Recomputes the same unsigned-UTF-8 class tree used by loaded-origin verification.
     * This traversal owns bounded read scratch and verifies complete actual bytes against each declared entry size.
     */
    internal fun classTree(path: Path): JsonObject {
        if (Files.isDirectory(path)) return classDirectoryTree(path)
        val entries = ClassArchiveInventory.entries(path, 16_384, 8L * 1024 * 1024)
        val tree = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        ZipFile(path.toFile()).use { archive ->
            entries.forEach { name ->
                val entry = checkNotNull(archive.getEntry(name))
                require(0 <= entry.size)
                total = Math.addExact(total, entry.size)
                require(total <= 64L * 1024 * 1024) { "Oversized preserved class tree" }
                val hash = archive.getInputStream(entry).use { classHash(it, entry.size, buffer) }
                tree.update("$name=$hash\n".toByteArray(Charsets.UTF_8))
            }
        }
        return JsonObject().apply {
            addProperty("status", "available")
            addProperty("algorithm", "sha256(path=sha256(resource-bytes)\\n)-utf8-v1")
            addProperty("entryCount", entries.size)
            addProperty("bytes", total)
            addProperty("sha256", HexFormat.of().formatHex(tree.digest()))
        }
    }

    /**
     * Binds a file resource to its canonical declared class directory, rejecting aliases and escapes.
     */
    internal fun classDirectoryResource(
        root: Path,
        name: String,
        location: URL,
    ): Path {
        val uri = location.toURI()
        require(LocalResourceProtocol.decode(uri.scheme) == LocalResourceProtocol.File && uri.rawAuthority.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null) { "Expected a local class resource" }
        val directory = root.toAbsolutePath()
        require(directory == directory.normalize()) { "Noncanonical class directory: $root" }
        val expected = directory.resolve(name).normalize()
        require(directory.toRealPath() == directory && expected.startsWith(directory) && expected.toRealPath() == expected) { "Aliased or escaping class resource: $name" }
        val actual = Path.of(uri).toAbsolutePath()
        require(actual == actual.normalize()) { "Noncanonical class resource: $location" }
        require(actual == expected && Files.isRegularFile(actual)) { "The classpath resource resolved from a different code source: $location" }
        return actual
    }

    private fun classDirectoryTree(path: Path): JsonObject {
        val entries = ClassArchiveInventory.entries(path, 16_384, 8L * 1024 * 1024)
        val tree = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        entries.forEach { name ->
            val entry = path.resolve(name)
            val size = Files.size(entry)
            val maximum = 8 * 1024 * 1024
            require(size in 1L..maximum.toLong()) { "Invalid class-directory resource size" }
            total = Math.addExact(total, size)
            require(total <= 64L * 1024 * 1024) { "Oversized preserved class tree" }
            val hash =
                Files.newInputStream(entry, LinkOption.NOFOLLOW_LINKS).use { input ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    var bytes = 0L
                    while (true) {
                        val count = input.readNBytes(buffer, 0, minOf(buffer.size.toLong(), maximum + 1L - bytes).toInt())
                        if (count == 0) break
                        bytes += count
                        require(bytes <= maximum) { "Invalid class-directory resource size" }
                        digest.update(buffer, 0, count)
                    }
                    require(bytes == size) { "Class-directory resource size changed" }
                    HexFormat.of().formatHex(digest.digest())
                }
            tree.update("$name=$hash\n".toByteArray(Charsets.UTF_8))
        }
        require(entries == ClassArchiveInventory.entries(path, 16_384, 8L * 1024 * 1024)) { "Class directory inventory changed" }
        return JsonObject().apply {
            addProperty("status", "available")
            addProperty("algorithm", "sha256(path=sha256(resource-bytes)\\n)-utf8-v1")
            addProperty("entryCount", entries.size)
            addProperty("bytes", total)
            addProperty("sha256", HexFormat.of().formatHex(tree.digest()))
        }
    }

    private fun classHash(
        input: InputStream,
        expectedBytes: Long,
        buffer: ByteArray,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val maximum = 8 * 1024 * 1024
        var bytes = 0L
        while (true) {
            val remaining = maximum + 1L - bytes
            val count = input.readNBytes(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count == 0) break
            bytes += count
            require(bytes <= maximum) { "Invalid preserved class resource size" }
            digest.update(buffer, 0, count)
        }
        require(bytes == expectedBytes) { "Invalid preserved class resource size" }
        return HexFormat.of().formatHex(digest.digest())
    }
}
