package dev.s7a.strata.integration.docs

/**
 * Generates the checked-in public Strata skill references from the current API and examples.
 */
internal object StrataSkillGenerator {
    /**
     * Writes isolated evidence and synchronizes generated references.
     *
     * @param args launcher paths consumed by [StrataSkillLaunchArguments].
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        val launch = StrataSkillLaunchArguments.parse(args)
        val prepared = StrataSkillPipeline.prepare(launch)
        StrataSkillPipeline.writeStaging(launch, prepared)
        StrataSkillPipeline.synchronize(launch.projectRoot, prepared)
    }
}
