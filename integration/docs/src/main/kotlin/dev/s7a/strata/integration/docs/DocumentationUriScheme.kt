package dev.s7a.strata.integration.docs

/**
 * URI schemes admitted for links that resolve back into staged documentation.
 */
internal enum class DocumentationUriScheme {
    /**
     * Encrypted HTTP used by the public Pages origin.
     */
    HTTPS,
    ;

    /**
     * Converts untrusted URI text into an admitted scheme.
     */
    companion object {
        /**
         * Decodes an external URI scheme without admitting an unknown value.
         *
         * @param value untrusted URI scheme text, or null when no scheme was supplied.
         * @return the admitted typed scheme, or null for absent and unknown schemes.
         */
        internal fun decode(value: String?): DocumentationUriScheme? = entries.singleOrNull { scheme -> scheme.name.equals(value, ignoreCase = true) }
    }
}
