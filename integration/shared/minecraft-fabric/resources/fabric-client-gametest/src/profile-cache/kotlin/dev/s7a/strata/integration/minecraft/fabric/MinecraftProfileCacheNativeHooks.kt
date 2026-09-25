package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.server.packs.PackResources
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.resources.ReloadableResourceManager
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.SimplePreparableReloadListener
import net.minecraft.util.profiling.ProfilerFiller
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import net.minecraft.util.Unit as MinecraftUnit

/**
 * Exercises the real native reload and close entrypoints against test-owned empty resource managers.
 *
 * No game-owned pack is closed or mutated. A failing pack-list read proves invalidation before any reload listener can prepare.
 * The same callbacks are required in development and production-remapped clients.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftProfileCacheNativeHooks {
    /**
     * Verifies foreign-manager isolation, failed pre-prepare reload invalidation, and owned-manager close eviction on the client thread.
     *
     * The supplied immutable profile is borrowed only to seed the cache for each owned-manager probe.
     * All owned managers close in finally, and successful return leaves the adapter cache empty.
     */
    fun verify(profile: MinecraftUiProfile) {
        ReloadableResourceManager(PackType.SERVER_DATA).use { foreign ->
            foreign.close()
            check(MinecraftProfileCacheInspection.profile() === profile) { "A foreign server manager cleared the client profile." }
        }
        verifyFailedReload(profile)
        ReloadableResourceManager(PackType.CLIENT_RESOURCES).use { owned ->
            MinecraftProfileCacheInspection.seed(owned, profile)
            owned.close()
            check(MinecraftProfileCacheInspection.profile() == null) { "Native resource-manager close retained its cached profile." }
        }
    }

    private fun verifyFailedReload(profile: MinecraftUiProfile) {
        val failure = IllegalStateException("Expected profile-cache pre-prepare resource failure")
        var preparations = 0
        ReloadableResourceManager(PackType.CLIENT_RESOURCES).use { manager ->
            manager.registerReloadListener(
                object : SimplePreparableReloadListener<Unit>() {
                    override fun prepare(
                        resources: ResourceManager,
                        profiler: ProfilerFiller,
                    ) {
                        preparations++
                    }

                    override fun apply(
                        prepared: Unit,
                        resources: ResourceManager,
                        profiler: ProfilerFiller,
                    ) = Unit
                },
            )
            MinecraftProfileCacheInspection.seed(manager, profile)
            val packs =
                object : AbstractList<PackResources>() {
                    override val size: Int = 1

                    override fun get(index: Int): PackResources = throw failure
                }
            val thrown =
                runCatching {
                    val immediate = Executor(Runnable::run)
                    manager.createReload(immediate, immediate, CompletableFuture.completedFuture(MinecraftUnit.INSTANCE), packs)
                }.exceptionOrNull()
            check(thrown === failure) { "The native pre-prepare failure did not propagate unchanged: $thrown" }
            check(preparations == 0) { "The failure must precede every native listener's prepare callback." }
            check(MinecraftProfileCacheInspection.profile() == null) { "Failed native reload retained the previous resource generation." }
        }
    }
}
