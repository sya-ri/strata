package dev.s7a.strata.integration.docs

/**
 * Entry point that freshly renders the compiled README demonstration and rejects stale tracked output.
 */
internal object ReadmeDemoChecker {
    /**
     * Writes only ignored staging on the process thread; checked documentation is always read-only.
     */
    @JvmStatic
    fun main(arguments: Array<String>) {
        ReadmeDemoLaunch.run(arguments, synchronize = false)
    }
}
