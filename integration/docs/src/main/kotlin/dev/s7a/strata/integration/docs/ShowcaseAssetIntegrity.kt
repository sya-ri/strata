package dev.s7a.strata.integration.docs

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Reads bounded caller-owned inputs and computes content identities without retaining streams.
 * Callers keep the files stable during a showcase load; all methods are synchronous and stateless.
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
     * SHA-1 verifies Mojang metadata; SHA-256 binds the generated showcase inputs.
     */
    fun hashes(
        path: Path,
        maximum: Long,
    ): Hashes {
        require(Files.isRegularFile(path)) { "A declared showcase input is not a regular file." }
        require(Files.size(path) <= maximum) { "A declared showcase input exceeds its byte ceiling." }
        val sha1 = MessageDigest.getInstance("SHA-1")
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
     * Returns the SHA-256 identity of immutable bytes without retaining or changing them.
     */
    fun sha256(bytes: ByteArray): String = hex.formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /**
     * Returns the SHA-1 identity used by an official asset index without retaining its input.
     */
    fun sha1(bytes: ByteArray): String = hex.formatHex(MessageDigest.getInstance("SHA-1").digest(bytes))

    /**
     * Detached identities of the same bounded file read, safe to retain in immutable evidence.
     */
    data class Hashes(
        val sha1: String,
        val sha256: String,
    )

    private val hex = HexFormat.of()
}
