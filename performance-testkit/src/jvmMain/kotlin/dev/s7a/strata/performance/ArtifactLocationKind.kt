package dev.s7a.strata.performance

/**
 * Declared physical location kinds decoded before verifying their distinct hash contracts.
 */
internal enum class ArtifactLocationKind(
    internal val token: String,
) {
    File("file"),
    JarRoot("jar_root"),
    JarEntry("jar_entry"),
    ClassDirectory("class_directory"),
    ;

    /**
     * Strict location decoding; an unknown kind cannot borrow another origin's hash contract.
     */
    companion object {
        /**
         * Returns a declared physical kind or rejects an unrecognized evidence contract.
         */
        internal fun decode(token: String): ArtifactLocationKind = requireNotNull(entries.firstOrNull { it.token == token }) { "Unknown artifact location kind: $token" }
    }
}
