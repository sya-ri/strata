package dev.s7a.strata.integration.docs

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Immutable source, dimensions, provenance, and image hashes for one complete showcase frame.
 *
 * Construction validates the PNG header against the exact physical viewport and retains no image or source buffers.
 * The detached value can be shared across threads.
 *
 * @property viewport logical dimensions, GUI scale, and checked physical dimensions.
 * @param source compiled example region whose line endings are normalized to LF before hashing.
 * @param png complete encoded frame, read synchronously and never retained.
 * @property origin distinguishes fresh CPU output from explicit loaded-server inventory evidence.
 * @throws IllegalArgumentException when the PNG signature or physical dimensions differ from the viewport.
 */
internal class ShowcaseFrameReceipt(
    internal val viewport: ShowcaseViewport,
    source: String,
    png: ByteArray,
    internal val origin: Origin = Origin.Headless,
) {
    init {
        require(24 <= png.size && png.copyOfRange(0, pngSignature.size).contentEquals(pngSignature)) {
            "Showcase frame is not a PNG."
        }
        val buffer = ByteBuffer.wrap(png)
        require(buffer.getInt(16) == viewport.physicalSize.width && buffer.getInt(20) == viewport.physicalSize.height) {
            "Showcase PNG dimensions differ from its logical viewport and GUI scale."
        }
    }

    /**
     * Lowercase SHA-256 of the LF-normalized compiled source region without an added terminal newline.
     */
    internal val sourceSha256: String = sha256(source.replace("\r\n", "\n").replace('\r', '\n').toByteArray(StandardCharsets.UTF_8))

    /**
     * Lowercase SHA-256 of the exact encoded PNG bytes.
     */
    internal val pngSha256: String = sha256(png)

    /**
     * Explicit image provenance, independent of whether a native parity gate has run.
     *
     * @property receiptValue stable external spelling used in generated receipt files.
     */
    internal enum class Origin(
        internal val receiptValue: String,
    ) {
        /**
         * Freshly rasterized by the portable CPU renderer during this invocation.
         */
        Headless("headless"),

        /**
         * Explicit verified image captured from the loaded server-backed Fabric inventory screen.
         */
        LoadedServerFabric("loaded-server-fabric"),
    }

    /**
     * Pure byte hashing shared by headless and explicit native inventory receipts.
     */
    companion object {
        private val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        /**
         * Hashes the supplied bytes synchronously without retaining or modifying them.
         *
         * @param bytes exact file or metadata bytes.
         * @return lowercase 64-character SHA-256 value.
         */
        internal fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
