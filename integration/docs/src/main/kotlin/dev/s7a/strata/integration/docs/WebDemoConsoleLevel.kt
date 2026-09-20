package dev.s7a.strata.integration.docs

/**
 * Console levels that fail demo verification; other messages remain informational diagnostics.
 */
internal enum class WebDemoConsoleLevel {
    Error,
    ;

    /**
     * Decodes the browser's external console level at the Playwright boundary.
     */
    companion object {
        /**
         * Returns the failing level, or null for a console level outside the error contract.
         */
        fun decode(value: String): WebDemoConsoleLevel? = entries.singleOrNull { it.name.equals(value, ignoreCase = true) }
    }
}
