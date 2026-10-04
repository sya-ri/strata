package dev.s7a.strata.performance

/**
 * URL protocols decoded at the local provenance boundary.
 */
internal enum class LocalResourceProtocol(
    private val scheme: String?,
) {
    File("file"),
    Jar("jar"),
    Other(null),
    ;

    /**
     * Decodes the external URL scheme once at the resource boundary.
     */
    companion object {
        /**
         * Preserves unknown protocols as unsupported instead of treating them as local files.
         */
        internal fun decode(value: String): LocalResourceProtocol = entries.firstOrNull { it.scheme == value } ?: Other
    }
}
