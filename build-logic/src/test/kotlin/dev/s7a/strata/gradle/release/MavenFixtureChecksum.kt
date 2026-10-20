package dev.s7a.strata.gradle.release

import java.security.MessageDigest
import java.util.HexFormat

/**
 * Associates Maven sidecar extensions, without a leading dot, with their standard JCA algorithms.
 * The fixture mapping remains independent of the production publication verifier.
 */
internal enum class MavenFixtureChecksum(
    val extension: String,
    private val algorithm: String,
) {
    MD5("md5", "MD5"),
    SHA1("sha1", "SHA-1"),
    SHA256("sha256", "SHA-256"),
    SHA512("sha512", "SHA-512"),
    ;

    /**
     * Calculates a lowercase digest without modifying or retaining the supplied bytes.
     */
    fun digest(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes))
}
