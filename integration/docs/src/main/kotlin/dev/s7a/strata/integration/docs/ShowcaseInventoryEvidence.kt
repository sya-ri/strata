package dev.s7a.strata.integration.docs

import dev.s7a.strata.geometry.IntSize
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Detached explicit native inventory image and its verified source provenance.
 *
 * Loading never starts a game or searches an implicit build directory.
 * No file handles are retained and each returned image is an independent byte array.
 *
 * @param png verified image bytes copied during construction.
 * @property frame immutable dimensions, source hash, image hash, and loaded-server origin.
 * @property proofSha256 hash of the exact explicit three-field verification receipt.
 */
internal class ShowcaseInventoryEvidence private constructor(
    png: ByteArray,
    internal val frame: ShowcaseFrameReceipt,
    internal val proofSha256: String,
) {
    private val image: ByteArray = png.copyOf()

    /**
     * Returns an independent snapshot of the verified native inventory PNG.
     *
     * @return exact encoded 320 by 240 frame bytes.
     */
    internal fun png(): ByteArray = image.copyOf()

    /**
     * Reads one explicitly selected native inventory image and its canonical receipt.
     */
    companion object {
        /**
         * Verifies version, exact PNG bytes, and the current compiled inventory source region.
         *
         * @param pngPath read-only native inventory PNG path.
         * @param receiptPath read-only receipt containing exactly minecraft.version, png.sha256, and source.sha256.
         * @param source current compiled inventory source region, normalized to LF before hashing.
         * @return detached immutable evidence with no retained file handles.
         * @throws IllegalArgumentException when paths, encoding, schema, version, dimensions, source, or image hashes differ.
         * @throws java.io.IOException when an input cannot be read.
         */
        internal fun load(
            pngPath: Path,
            receiptPath: Path,
            source: String,
        ): ShowcaseInventoryEvidence {
            ShowcasePaths.requireRegularFile(pngPath, "native inventory image")
            ShowcasePaths.requireRegularFile(receiptPath, "native inventory receipt")
            val proof = Files.readAllBytes(receiptPath)
            val values = parseReceipt(proof)
            require(values.keys == setOf("minecraft.version", "png.sha256", "source.sha256")) {
                "Native inventory receipt fields differ from the explicit evidence contract."
            }
            require(MinecraftVersion.entries.any { version -> version.receiptValue == values.getValue("minecraft.version") }) {
                "Native inventory receipt has the wrong Minecraft version."
            }
            val png = Files.readAllBytes(pngPath)
            val frame = ShowcaseFrameReceipt(ShowcaseViewport(IntSize(320, 240), 1), source, png, ShowcaseFrameReceipt.Origin.LoadedServerFabric)
            require(values.getValue("png.sha256") == frame.pngSha256) { "Native inventory PNG hash differs from its receipt." }
            require(values.getValue("source.sha256") == frame.sourceSha256) { "Native inventory compiled source differs from its receipt." }
            return ShowcaseInventoryEvidence(png, frame, ShowcaseFrameReceipt.sha256(proof))
        }

        private fun parseReceipt(bytes: ByteArray): Map<String, String> {
            val text =
                try {
                    StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString()
                } catch (failure: CharacterCodingException) {
                    throw IllegalArgumentException("Native inventory receipt is not valid UTF-8.", failure)
                }
            require(text.endsWith('\n') && text.dropLast(1).endsWith('\n').not() && text.contains('\r').not()) {
                "Native inventory receipt must use LF and one terminal line ending."
            }
            val values = LinkedHashMap<String, String>()
            text.dropLast(1).lines().forEach { line ->
                val separator = line.indexOf('=')
                require(0 < separator && separator + 1 < line.length) { "Native inventory receipt contains a malformed field." }
                require(values.put(line.substring(0, separator), line.substring(separator + 1)) == null) {
                    "Native inventory receipt contains a duplicate field."
                }
            }
            return values
        }

        private enum class MinecraftVersion(
            val receiptValue: String,
        ) {
            Current("26.2"),
        }
    }
}
