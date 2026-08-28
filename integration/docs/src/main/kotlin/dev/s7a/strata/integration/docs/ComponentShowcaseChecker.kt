package dev.s7a.strata.integration.docs

/**
 * Checks showcase freshness synchronously from explicit read-only assets without launching Minecraft or changing source files.
 *
 * Portable examples use fresh CPU rendering without a GPU context, while the inventory image requires its explicit native receipt.
 * Inputs remain caller-owned; the independent loaded-game parity gate is not a prerequisite of this launcher.
 */
internal object ComponentShowcaseChecker {
    /**
     * Renders portable examples and verifies the native inventory input before comparing every owned source artifact byte-for-byte.
     *
     * Staging includes deterministic source and asset hashes, logical viewports, GUI scales, and physical image dimensions.
     * Invalid inputs, rendering failures, and stale documentation fail the invocation without changing source files.
     *
     * @param args project root, module build root, exact check staging root, client archive, asset index, asset objects directory, version manifest, native inventory PNG, native inventory receipt, and one or more compiled API component class directories, in that order.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        val launch = ShowcaseLaunchArguments.parse(args, ShowcaseStagingKind.Check)
        val prepared = ShowcasePipeline.prepare(launch)
        ShowcasePipeline.writeStaging(prepared)
        ShowcasePipeline.checkSource(launch.projectRoot, prepared)
    }
}
