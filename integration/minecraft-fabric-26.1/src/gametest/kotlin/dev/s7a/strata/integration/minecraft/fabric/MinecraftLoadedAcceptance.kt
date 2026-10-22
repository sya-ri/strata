package dev.s7a.strata.integration.minecraft.fabric

/**
 * Runs this target's screen, Canvas and native transport acceptance.
 */
internal object MinecraftLoadedAcceptance {
    /**
     * Keeps world and presentation ownership inside the supplied verification invocation.
     */
    fun run(context: MinecraftLoadedTestContext) {
        StrataMinecraftClientGameTest().runTest(context)
    }
}
