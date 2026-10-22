package dev.s7a.strata.integration.minecraft.fabric

/**
 * Runs the shared loaded-client acceptance suite through this runner-owned [MinecraftLoadedTestContext].
 */
internal fun MinecraftLoadedTestContext.runLoadedSuite() {
    StrataMinecraftLegacyLoadedSuite().run(this)
    RemoteNativeGameTest.run(this)
    PaperRemoteGameTest.run(this)
}
