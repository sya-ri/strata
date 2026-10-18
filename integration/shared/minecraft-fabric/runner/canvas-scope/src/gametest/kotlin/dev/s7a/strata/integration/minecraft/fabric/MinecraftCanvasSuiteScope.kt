package dev.s7a.strata.integration.minecraft.fabric

/**
 * Identifies exactly which loaded suite completed before the actual client-shutdown proof is armed.
 *
 * Values contain immutable receipt metadata only and can be read on either the runner or client thread.
 * The default always selects the unchanged complete shared suite; bounded and terminal diagnostic scopes are explicit opt-in.
 *
 * @property argument exact external Gradle and JVM property spelling.
 * @property verifiedChecks comma-separated checks recorded only after the selected suite and native shutdown succeed.
 * @property excludedChecks explicit exclusions preventing a bounded backend run from representing full-suite acceptance.
 */
internal enum class MinecraftCanvasSuiteScope(
    internal val argument: String,
    internal val verifiedChecks: String,
    internal val excludedChecks: String,
) {
    /**
     * Executes all shared native, Fabric, headless, inventory, component-showcase, and Canvas acceptance.
     */
    Full("full", "shared-full-suite,canvas-suite,actual-device-shutdown", "none"),

    /**
     * Executes all Canvas cases, real server-seeded Slot ordering, and the identical actual-shutdown proof.
     */
    CanvasOnly(
        "canvas-only",
        "native-texture,custom-renderer,alpha,clip,gui-scale,resize,source-replacement,detach-reattach," +
            "target-capacity,queued-close,mixed-portable-consumption,same-generation-capture," +
            "pointer-window-reset,partial-producer-failure,server-seeded-slot-order,actual-device-shutdown",
        "unrelated-native-reference-screens,non-canvas-component-showcase,inventory-click-synchronization",
    ),

    /**
     * Executes only the actual device-shutdown proof so backend lifecycle failures can be reproduced independently.
     */
    TerminalOnly(
        "terminal-only",
        "actual-device-shutdown",
        "shared-full-suite,canvas-suite,unrelated-native-reference-screens,non-canvas-component-showcase," +
            "inventory-click-synchronization",
    ),
    ;

    /**
     * Decodes immutable runner configuration without accessing Minecraft or creating resources.
     */
    internal companion object {
        /**
         * Returns the explicitly requested scope, or full acceptance when the property is absent.
         *
         * This any-thread read fails on unknown values before the wrapper starts its selected suite.
         */
        internal fun current(): MinecraftCanvasSuiteScope {
            val argument = System.getProperty("strata.canvas.scope") ?: return Full
            return requireNotNull(entries.singleOrNull { scope -> scope.argument == argument }) {
                "strata.canvas.scope must be full, canvas-only, or terminal-only."
            }
        }
    }
}
