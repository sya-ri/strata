package dev.s7a.strata.performance

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
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
     */
    internal fun classTree(path: Path): JsonObject {
        val entries = ClassArchiveInventory.entries(path, 16_384, 8L * 1024 * 1024)
        val tree = MessageDigest.getInstance("SHA-256")
        var total = 0L
        ZipFile(path.toFile()).use { archive ->
            entries.forEach { name ->
                val entry = checkNotNull(archive.getEntry(name))
                require(0 <= entry.size)
                total = Math.addExact(total, entry.size)
                require(total <= 64L * 1024 * 1024) { "Oversized preserved class tree" }
                val bytes = archive.getInputStream(entry).use { it.readNBytes(8 * 1024 * 1024 + 1) }
                require(bytes.size.toLong() == entry.size && bytes.size <= 8 * 1024 * 1024) { "Invalid preserved class resource size" }
                val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
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
}
