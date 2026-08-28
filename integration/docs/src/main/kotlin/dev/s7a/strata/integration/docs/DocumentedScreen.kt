package dev.s7a.strata.integration.docs

/**
 * Typed complete-screen use cases emitted after the primitive component catalog.
 *
 * Values identify documentation and GameTest evidence only; runtime component dispatch never observes this type.
 *
 * @property title human-readable section title.
 * @property slug stable generated image and anchor name.
 * @property verification typed independent native acceptance requirement, distinct from documentation generation.
 */
internal enum class DocumentedScreen(
    val title: String,
    val slug: String,
    val verification: Verification,
) {
    /**
     * Native Social Interactions reconstruction with three-way pixel parity.
     */
    SocialInteractions("Social Interactions", "social", Verification.NativeFabricHeadless),

    /**
     * Loaded server-authoritative inventory interaction rendered through Fabric.
     */
    SynchronizedInventory("Synchronized inventory", "inventory", Verification.LoadedServerFabric),

    /**
     * Resource-pack-aware downstream industrial Mod composition.
     */
    IndustrialController("Industrial controller", "industrial", Verification.FabricHeadless),

    /**
     * Advancement-inspired downstream progression composition.
     */
    PowerMilestones("Power milestones", "progress", Verification.FabricHeadless),
    ;

    /**
     * Typed strength of independent native acceptance, not a request to launch a game during generation.
     */
    internal enum class Verification {
        /**
         * Requires exact native, Fabric, and headless pixel equality.
         */
        NativeFabricHeadless,

        /**
         * Requires a loaded logical server interaction and resulting Fabric frame.
         */
        LoadedServerFabric,

        /**
         * Requires exact Fabric and headless pixel equality with active resources.
         */
        FabricHeadless,
    }
}
