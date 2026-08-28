package dev.s7a.strata.integration.docs

/**
 * Generates the showcase synchronously from explicit read-only assets without launching Minecraft or creating a GPU context.
 *
 * Portable examples are rendered afresh; the server-backed inventory image is verified against its explicit native receipt.
 * Only staging and generator-owned documentation outputs are written, and input ownership remains with the caller.
 */
internal object ComponentShowcaseGenerator {
    /**
     * Renders compiled portable examples, verifies the native inventory input, and synchronizes the isolated staging result.
     *
     * Input validation and rendering finish before documentation synchronization begins; failures propagate to the caller.
     * The deterministic receipt records source and asset hashes, logical viewports, GUI scales, and physical image dimensions.
     *
     * @param args project root, module build root, exact generation staging root, client archive, asset index, asset objects directory, version manifest, native inventory PNG, native inventory receipt, and one or more compiled API component class directories, in that order.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        val launch = ShowcaseLaunchArguments.parse(args, ShowcaseStagingKind.Generate)
        val prepared = ShowcasePipeline.prepare(launch)
        ShowcasePipeline.writeStaging(prepared)
        ShowcaseSynchronizer.synchronize(launch, prepared)
    }
}
