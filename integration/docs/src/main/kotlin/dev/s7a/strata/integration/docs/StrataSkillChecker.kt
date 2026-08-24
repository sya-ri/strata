package dev.s7a.strata.integration.docs

/**
 * Checks generated public Strata skill references without changing tracked files.
 */
internal object StrataSkillChecker {
    /**
     * Writes isolated evidence, byte-checks generated references, and validates the complete skill package.
     *
     * @param args launcher paths consumed by [StrataSkillLaunchArguments].
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        val launch = StrataSkillLaunchArguments.parse(args)
        val prepared = StrataSkillPipeline.prepare(launch)
        StrataSkillPipeline.writeStaging(launch, prepared)
        StrataSkillPipeline.check(launch.projectRoot, prepared)
        StrataSkillValidator.validate(launch.projectRoot)
    }
}
