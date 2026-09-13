package dev.s7a.strata.integration.docs

/**
 * Entry point that freshly renders and synchronizes the compiled README demonstration.
 */
internal object ReadmeDemoGenerator {
    /**
     * Runs on the process thread, propagating invalid source, asset, rendering, and file failures.
     */
    @JvmStatic
    fun main(arguments: Array<String>) {
        ReadmeDemoLaunch.run(arguments, synchronize = true)
    }
}
