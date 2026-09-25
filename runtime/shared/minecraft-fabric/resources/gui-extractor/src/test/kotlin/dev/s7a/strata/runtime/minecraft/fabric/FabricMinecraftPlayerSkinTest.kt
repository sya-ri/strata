package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.minecraft.MinecraftPlayerSkinBinding
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Verifies detached normalized player-skin snapshots without requiring a loaded client.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class FabricMinecraftPlayerSkinTest {
    @Test
    fun nativeArgbPixelsAreCopiedAndDetached() {
        NativeImage(64, 64, false).use { image ->
            image.setPixel(8, 8, 0xFF123456.toInt())
            val snapshot = createPlayerSkinSnapshot(image)
            assertEquals(0xFF123456.toInt(), snapshot.argbAt(8, 8))
            image.setPixel(8, 8, 0xFFABCDEF.toInt())
            assertEquals(0xFF123456.toInt(), snapshot.argbAt(8, 8))
        }
    }

    @Test
    fun onlyNormalizedSixtyFourPixelSkinsAreAccepted() {
        NativeImage(64, 32, false).use { image ->
            assertThrows(IllegalArgumentException::class.java) { createPlayerSkinSnapshot(image) }
        }
        NativeImage(63, 64, false).use { image ->
            assertThrows(IllegalArgumentException::class.java) { createPlayerSkinSnapshot(image) }
        }
    }

    @Test
    fun closeDropsQueuedCompletionAndRejectsLateAsyncPublication() {
        val lifecycle = FabricMinecraftInventoryBridge.SkinBindingLifecycle()
        assertTrue(lifecycle.publish(FabricMinecraftInventoryBridge.SkinFailed.INSTANCE))
        assertFalse(lifecycle.publish(FabricMinecraftInventoryBridge.SkinFailed.INSTANCE))
        assertTrue(lifecycle.close())
        assertNull(lifecycle.drainCompletion())
        assertSame(MinecraftPlayerSkinBinding.Snapshot.Pending, lifecycle.retainedSnapshot())

        val releaseCompletion = CompletableFuture<Unit>()
        val latePublication =
            CompletableFuture.supplyAsync {
                releaseCompletion.join()
                lifecycle.publish(FabricMinecraftInventoryBridge.SkinFailed.INSTANCE)
            }
        releaseCompletion.complete(Unit)
        assertFalse(latePublication.get(5, TimeUnit.SECONDS))
        assertFalse(lifecycle.close())
    }

    @Test
    fun closeReleasesCommittedReadySnapshotReference() {
        val lifecycle = FabricMinecraftInventoryBridge.SkinBindingLifecycle()
        assertTrue(lifecycle.publish(FabricMinecraftInventoryBridge.SkinFailed.INSTANCE))
        assertSame(FabricMinecraftInventoryBridge.SkinFailed.INSTANCE, lifecycle.drainCompletion())
        val skin = createDrawImage(IntSize(64, 64), IntArray(64 * 64))
        val ready = MinecraftPlayerSkinBinding.Snapshot.Ready(skin)
        assertTrue(lifecycle.commitSnapshot(ready))
        assertSame(ready, lifecycle.retainedSnapshot())

        assertTrue(lifecycle.close())
        assertSame(MinecraftPlayerSkinBinding.Snapshot.Pending, lifecycle.retainedSnapshot())
        assertFalse(lifecycle.commitSnapshot(ready))
    }
}
