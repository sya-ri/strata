package dev.s7a.strata.integration.docs

/**
 * Launches the component showcase render and staging serializer.
 */
internal object ComponentShowcaseGenerator {
    /**
     * Renders all compiled examples, writes the isolated staging result, and synchronizes the generator-owned source files.
     *
     * @param args four argument groups: project root, module build root, exact generation staging root, and API class directories.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        val launch = ShowcaseLaunchArguments.parse(args, ShowcaseStagingKind.Generate)
        val prepared = ShowcasePipeline.prepare(launch)
        ShowcasePipeline.writeStaging(prepared)
        ShowcaseSynchronizer.synchronize(launch, prepared)
    }
}
