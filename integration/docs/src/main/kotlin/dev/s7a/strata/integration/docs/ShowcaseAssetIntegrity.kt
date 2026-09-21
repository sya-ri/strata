package dev.s7a.strata.integration.docs

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Reads bounded inputs and computes content hashes, closing streams before return. Callers keep files stable during a load.
 */
internal object ShowcaseAssetIntegrity {
    /**
     * Reads one complete document under an inclusive byte ceiling and closes its stream on every path.
     * Missing, nonregular, changing, or oversized inputs fail before a complete oversized array is retained.
     */
    fun read(
        path: Path,
        maximum: Int,
    ): ByteArray {
        require(Files.isRegularFile(path)) { "A declared showcase input is not a regular file." }
        require(Files.size(path) <= maximum.toLong()) { "A declared showcase document exceeds its byte ceiling." }
        return Files.newInputStream(path).use { input ->
            input.readNBytes(Math.addExact(maximum, 1)).also { bytes ->
                require(bytes.size <= maximum) { "A declared showcase document grew beyond its byte ceiling." }
            }
        }
    }

    /**
     * Hashes one complete regular file with fixed scratch storage and an inclusive byte ceiling.
     * SHA-1 matches Mojang's declared checksums; SHA-256 binds the generated showcase inputs.
     */
    fun hashes(
        path: Path,
        maximum: Long,
    ): Hashes {
        require(Files.isRegularFile(path)) { "A declared showcase input is not a regular file." }
        require(Files.size(path) <= maximum) { "A declared showcase input exceeds its byte ceiling." }
        val sha1 = mojangSha1Digest()
        val sha256 = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(8 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total = Math.addExact(total, count.toLong())
                require(total <= maximum) { "A declared showcase input grew beyond its byte ceiling." }
                sha1.update(buffer, 0, count)
                sha256.update(buffer, 0, count)
            }
        }
        return Hashes(hex.formatHex(sha1.digest()), hex.formatHex(sha256.digest()))
    }

    /**
     * Returns the SHA-256 identity of [bytes].
     */
    fun sha256(bytes: ByteArray): String = hex.formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /**
     * Returns the compatibility checksum used by Mojang's asset index.
     */
    fun sha1(bytes: ByteArray): String = hex.formatHex(mojangSha1Digest().digest(bytes))

    // Mojang's manifest and asset index require SHA-1 for compatibility with their declared checksums.
    // Input provenance belongs to the caller; SHA-256 is used for retained identities and mutation detection.
    @Suppress("kotlin:S4790")
    private fun mojangSha1Digest(): MessageDigest = MessageDigest.getInstance("SHA-1")

    /**
     * Detached identities of the same bounded file read, safe to retain in immutable evidence.
     */
    data class Hashes(
        val sha1: String,
        val sha256: String,
    )

    private val hex = HexFormat.of()
}
