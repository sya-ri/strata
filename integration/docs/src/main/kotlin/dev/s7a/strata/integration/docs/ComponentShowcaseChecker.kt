package dev.s7a.strata.integration.docs

/**
 * Launches the component showcase freshness checker without changing source files.
 */
internal object ComponentShowcaseChecker {
    /**
     * Renders expected output into staging and compares every owned source artifact byte-for-byte.
     *
     * @param args project root, module build root, exact check staging root, and compiled API class directories.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        val launch = ShowcaseLaunchArguments.parse(args, ShowcaseStagingKind.Check)
        val prepared = ShowcasePipeline.prepare(launch)
        ShowcasePipeline.writeStaging(prepared)
        ShowcasePipeline.checkSource(launch.projectRoot, prepared)
    }
}
