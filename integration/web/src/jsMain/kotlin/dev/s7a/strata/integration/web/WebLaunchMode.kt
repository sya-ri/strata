package dev.s7a.strata.integration.web

/**
 * Startup intent decoded at the browser URL boundary before any state is constructed.
 */
internal enum class WebLaunchMode {
    Prerender,
    Mount,
    ;

    /**
     * Decodes the build harness's reserved query while leaving other URLs interactive.
     */
    companion object {
        /**
         * Interprets the complete native query string without retaining browser objects.
         */
        fun decode(query: String): WebLaunchMode = mapOf("?strata-prerender" to Prerender)[query] ?: Mount
    }
}
