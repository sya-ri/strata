package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.Image
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.PlayerSkinSource
import dev.s7a.strata.component.SlotBinding
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.VirtualList
import dev.s7a.strata.component.VirtualListState
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies bounded host-lifetime resource-image retention and deferred identity reuse.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftResourceImageRetentionTest {
    @Test
    fun admissionStopsAtTheFixedIdentifierBoundWithoutEviction() {
        val admitted = List(512) { index -> ResourceId("example", "textures/gui/bounded_$index.png") }
        val overBound = ResourceId("example", "textures/gui/bounded_overflow.png")
        val resolved = solidImage(0xFF556677.toInt())
        val platform = FakeImagePlatform { resolved }
        val host =
            createMinecraftUiHost(
                ScreenDefinition("Bounded resource image identifiers") {
                    Stack {
                        admitted.forEach { id -> Image(ImageSource.Resource(id)) }
                        Image(ImageSource.Resource(overBound))
                        Image(ImageSource.Resource(overBound))
                        Image(ImageSource.Resource(admitted.first()))
                    }
                },
                MinecraftProfileFixture.create(),
                platform,
            )
        try {
            host.attach()
            assertEquals(514, platform.imageCalls.size)
            assertEquals(1, platform.imageCalls.count { id -> id == admitted.first() })
            assertEquals(2, platform.imageCalls.count { id -> id == overBound })
        } finally {
            host.close()
        }
    }

    @Test
    fun admissionStopsAtTheFixedPayloadBoundWithoutEviction() {
        val admitted = List(32) { index -> ResourceId("example", "textures/gui/large_$index.png") }
        val overBound = ResourceId("example", "textures/gui/large_overflow.png")
        val resolved = createDrawImage(IntSize(1024, 1024), IntArray(1024 * 1024) { 0xFF334455.toInt() })
        val platform = FakeImagePlatform { resolved }
        val host =
            createMinecraftUiHost(
                ScreenDefinition("Bounded resource image payload") {
                    Stack {
                        admitted.forEach { id -> Image(ImageSource.Resource(id)) }
                        Image(ImageSource.Resource(overBound))
                        Image(ImageSource.Resource(overBound))
                        Image(ImageSource.Resource(admitted.last()))
                    }
                },
                MinecraftProfileFixture.create(),
                platform,
            )
        try {
            host.attach()
            assertEquals(34, platform.imageCalls.size)
            assertEquals(1, platform.imageCalls.count { id -> id == admitted.last() })
            assertEquals(2, platform.imageCalls.count { id -> id == overBound })
        } finally {
            host.close()
        }
    }

    @Test
    fun deferredRowsReuseResourceImageIdentityAcrossFarJumps() {
        val id = ResourceId("example", "textures/gui/deferred_jump.png")
        val resolved = createDrawImage(IntSize(1, 1), intArrayOf(0xFF789ABC.toInt()))
        val platform = FakeImagePlatform { resolved }
        val state = VirtualListState<Int>()
        val materializedItems = ArrayList<Int>()
        val host =
            createMinecraftUiHost(
                ScreenDefinition("Deferred resource image jumps") {
                    VirtualList(
                        itemCount = 10_000,
                        itemAt = { index -> index },
                        keyAt = { index -> index },
                        state = state,
                        viewportSize = IntSize(1, 1),
                        rowHeight = 1,
                    ) { item ->
                        materializedItems += item
                        Image(ImageSource.Resource(ResourceId(id.namespace, id.path)))
                    }
                },
                MinecraftProfileFixture.create(),
                platform,
            )
        try {
            host.attach()
            val firstImages = sampledImages(host)
            assertTrue(materializedItems.contains(0))
            materializedItems.clear()
            assertEquals(true, state.jumpToIndex(5_000))
            val middleImages = sampledImages(host)
            assertTrue(materializedItems.contains(5_000))
            materializedItems.clear()
            assertEquals(true, state.jumpToIndex(9_000))
            val finalImages = sampledImages(host)
            assertTrue(materializedItems.contains(9_000))

            assertEquals(listOf(id), platform.imageCalls)
            assertTrue(firstImages.isNotEmpty())
            assertTrue(middleImages.isNotEmpty())
            assertTrue(finalImages.isNotEmpty())
            (firstImages + middleImages + finalImages).forEach { image -> assertSame(resolved, image) }
        } finally {
            host.close()
        }
    }

    private fun sampledImages(host: MinecraftUiHost): List<DrawImage> =
        host
            .frame(IntSize(1, 1))
            .drawCommands
            .filterIsInstance<DrawCommand.SampledImage>()
            .map { command -> command.image }

    private fun solidImage(color: Int): DrawImage = createDrawImage(IntSize(2, 2), IntArray(4) { color })

    private class FakeImagePlatform(
        private val resolver: (ResourceId) -> DrawImage,
    ) : MinecraftUiPlatform {
        private val ownerThread = Thread.currentThread()
        private var closed = false
        val imageCalls: MutableList<ResourceId> = ArrayList()

        override fun inventorySlot(binding: SlotBinding): MinecraftInventorySlotBinding = error("No inventory Slot was expected: $binding")

        override fun image(resource: ResourceId): DrawImage {
            requireUsable()
            imageCalls += resource
            return resolver(resource)
        }

        override fun playerSkin(source: PlayerSkinSource): MinecraftPlayerSkinBinding = error("No player skin was expected: $source")

        override fun refresh() {
            requireUsable()
        }

        override fun close() {
            checkOwner()
            if (closed) return
            closed = true
        }

        private fun requireUsable() {
            checkOwner()
            check(closed.not()) { "Image platform is closed." }
        }

        private fun checkOwner() {
            check(Thread.currentThread() === ownerThread) { "Image platform requires its creator thread." }
        }
    }
}
