package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.minecraft.MinecraftPlayerSkinBinding
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Verifies completion cutoff and snapshot release for every exact native adapter without loading a client.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class FabricMinecraftSkinBindingLifecycleTest {
    @Test
    fun closeDropsQueuedCompletionAndRejectsLateAsyncPublication() {
        val lifecycle = FabricMinecraftSkinBindingLifecycle()
        assertTrue(lifecycle.publish(FabricMinecraftSkinBindingLifecycle.SkinFailed.INSTANCE))
        assertFalse(lifecycle.publish(FabricMinecraftSkinBindingLifecycle.SkinFailed.INSTANCE))
        assertTrue(lifecycle.close())
        assertNull(lifecycle.drainCompletion())
        assertSame(MinecraftPlayerSkinBinding.Snapshot.Pending, lifecycle.retainedSnapshot())

        val releaseCompletion = CompletableFuture<Unit>()
        val latePublication =
            CompletableFuture.supplyAsync {
                releaseCompletion.join()
                lifecycle.publish(FabricMinecraftSkinBindingLifecycle.SkinFailed.INSTANCE)
            }
        releaseCompletion.complete(Unit)
        assertFalse(latePublication.get(5, TimeUnit.SECONDS))
        assertFalse(lifecycle.close())
    }

    @Test
    fun closeReleasesCommittedReadySnapshotReference() {
        val lifecycle = FabricMinecraftSkinBindingLifecycle()
        assertTrue(lifecycle.publish(FabricMinecraftSkinBindingLifecycle.SkinFailed.INSTANCE))
        assertSame(FabricMinecraftSkinBindingLifecycle.SkinFailed.INSTANCE, lifecycle.drainCompletion())
        val skin = createDrawImage(IntSize(64, 64), IntArray(64 * 64))
        val ready = MinecraftPlayerSkinBinding.Snapshot.Ready(skin)
        assertTrue(lifecycle.commitSnapshot(ready))
        assertSame(ready, lifecycle.retainedSnapshot())

        assertTrue(lifecycle.close())
        assertSame(MinecraftPlayerSkinBinding.Snapshot.Pending, lifecycle.retainedSnapshot())
        assertFalse(lifecycle.commitSnapshot(ready))
    }
}
