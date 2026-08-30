@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.PlayerHead
import dev.s7a.strata.component.PlayerHeadScale
import dev.s7a.strata.component.PlayerSkinSource
import dev.s7a.strata.component.SlotBinding
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies asynchronous PlayerHead frame cutoffs, fallback roots, source replacement, and late-completion ownership.
 */
internal class MinecraftAsyncPlayerHeadTest {
    @Test
    fun lookupStartsAtLifecycleAttachmentAndContinuesAcrossEqualDescriptions() {
        val source = PlayerSkinSource.Name("StablePlayer")
        val platform = FakeSkinPlatform(source)
        val first = asyncElement(platform, source)
        assertEquals(0, platform.lookupCalls)

        val tree = UiTree()
        tree.update(first)
        assertEquals(1, platform.lookupCalls)
        tree.update(asyncElement(platform, source))
        assertEquals(1, platform.lookupCalls)
        assertTrue(platform.binding.closed.not())
        tree.close()
        assertTrue(platform.binding.closed)
    }

    @Test
    fun pendingReadyAndFailedStatesCommitOnlyDuringPlatformRefresh() {
        val platform = FakeSkinPlatform(PlayerSkinSource.Name("Player0"))
        val host =
            createMinecraftUiHost(
                definition(),
                MinecraftProfileFixture.create(),
                platform,
            )
        host.attach()

        val pending = host.frame(IntSize(8, 8))
        assertEquals(
            listOf(IntRect(2, 2, 6, 6)),
            pending.drawCommands.filterIsInstance<DrawCommand.FillRectangle>().map { command -> command.bounds },
        )

        val skin = skin()
        platform.binding.enqueue(MinecraftPlayerSkinBinding.Snapshot.Ready(skin))
        assertSame(MinecraftPlayerSkinBinding.Snapshot.Pending, platform.binding.snapshot())
        val ready = host.frame(IntSize(8, 8))
        assertEquals(2, ready.drawCommands.filterIsInstance<DrawCommand.BlitImage>().size)
        assertTrue(ready.drawCommands.filterIsInstance<DrawCommand.FillRectangle>().isEmpty())
        host.close()
        assertTrue(platform.binding.closed)

        val failedPlatform = FakeSkinPlatform(PlayerSkinSource.Name("MissingPlayer"))
        val failedHost =
            createMinecraftUiHost(
                definition(PlayerSkinSource.Name("MissingPlayer")),
                MinecraftProfileFixture.create(),
                failedPlatform,
            )
        failedHost.attach()
        failedPlatform.binding.enqueue(MinecraftPlayerSkinBinding.Snapshot.Failed)
        val failed = failedHost.frame(IntSize(8, 8))
        assertEquals(
            listOf(IntRect(1, 1, 7, 7)),
            failed.drawCommands.filterIsInstance<DrawCommand.FillRectangle>().map { command -> command.bounds },
        )
        failedHost.close()
    }

    @Test
    fun replacingABindingClosesTheOldSourceAndIgnoresItsLateCompletion() {
        val first = FakeSkinBinding()
        val second = FakeSkinBinding()
        val firstSource = PlayerSkinSource.Name("FirstPlayer")
        val secondSource = PlayerSkinSource.Name("SecondPlayer")
        val firstPlatform = FakeSkinPlatform(firstSource, first)
        val secondPlatform = FakeSkinPlatform(secondSource, second)
        val tree = UiTree()
        tree.update(asyncElement(firstPlatform, firstSource))
        tree.measure(Constraints.fixed(8, 8))
        tree.layout()

        tree.update(asyncElement(secondPlatform, secondSource))

        assertTrue(first.closed)
        first.enqueue(MinecraftPlayerSkinBinding.Snapshot.Ready(skin()))
        first.commit()
        assertSame(MinecraftPlayerSkinBinding.Snapshot.Pending, second.snapshot())
        second.enqueue(MinecraftPlayerSkinBinding.Snapshot.Failed)
        second.commit()
        assertSame(MinecraftPlayerSkinBinding.Snapshot.Failed, second.snapshot())
        tree.measure(Constraints.fixed(8, 8))
        tree.layout()
        tree.close()
        assertTrue(second.closed)
    }

    @Test
    fun anAsynchronouslyPublishedSkinMustStillBeNormalized() {
        val binding = FakeSkinBinding()
        val source = PlayerSkinSource.Name("InvalidPlayer")
        val platform = FakeSkinPlatform(source, binding)
        val tree = UiTree()
        tree.update(asyncElement(platform, source))
        val invalid = createDrawImage(IntSize(63, 64), IntArray(63 * 64))
        binding.enqueue(MinecraftPlayerSkinBinding.Snapshot.Ready(invalid))

        val failure = assertThrows(IllegalArgumentException::class.java) { binding.commit() }
        assertEquals("PlayerHead requires an exact 64 by 64 skin.", failure.message)
        tree.close()
    }

    @Test
    fun arbitraryLegacySizeFiltersEachAsynchronouslyPublishedSkin() {
        val source = PlayerSkinSource.Name("FilteredPlayer")
        val platform = FakeSkinPlatform(source)
        val host = createMinecraftUiHost(legacyDefinition(source, 10), MinecraftProfileFixture.create(), platform)
        try {
            host.attach()
            val firstSkin = skin(0xFFFF0000.toInt())
            platform.binding.enqueue(MinecraftPlayerSkinBinding.Snapshot.Ready(firstSkin))
            val first = host.frame(IntSize(10, 10)).drawCommands.filterIsInstance<DrawCommand.BlitImage>()
            assertEquals(2, first.size)
            first.forEach { command ->
                assertNotSame(firstSkin, command.image)
                assertEquals(IntSize(10, 10), command.image.size)
                assertEquals(IntRect(0, 0, 10, 10), command.source)
            }

            val secondSkin = skin(0xFF0000FF.toInt())
            platform.binding.enqueue(MinecraftPlayerSkinBinding.Snapshot.Ready(secondSkin))
            val second = host.frame(IntSize(10, 10)).drawCommands.filterIsInstance<DrawCommand.BlitImage>()
            assertNotSame(first.first().image, second.first().image)
            assertEquals(0xFF0000FF.toInt(), second.first().image.argbAt(5, 5))
        } finally {
            host.close()
        }
    }

    private fun definition(
        source: PlayerSkinSource = PlayerSkinSource.Name("Player0"),
    ): ScreenDefinition =
        ScreenDefinition("Player") {
            PlayerHead(
                source = source,
                scale = PlayerHeadScale(1),
                loadingContent = {
                    Spacer(modifier = Modifier.Empty.size(4, 4).background(loadingColor))
                },
                failureContent = {
                    Spacer(modifier = Modifier.Empty.size(6, 6).background(failureColor))
                },
            )
        }

    @Suppress("DEPRECATION")
    private fun legacyDefinition(
        source: PlayerSkinSource,
        size: Int,
    ): ScreenDefinition =
        ScreenDefinition("Player") {
            PlayerHead(source = source, size = size)
        }

    private fun asyncElement(
        platform: MinecraftUiPlatform,
        source: PlayerSkinSource,
    ) = createMinecraftAsyncPlayerHeadElement(
        platform = platform,
        source = source,
        size = 8,
        showHat = true,
        loading = null,
        failure = null,
        modifier = Modifier.Empty,
        key = null,
    )

    private fun skin(color: Int = 0xFFFFFFFF.toInt()): DrawImage = createDrawImage(IntSize(64, 64), IntArray(64 * 64) { color })

    private class FakeSkinPlatform(
        private val expectedSource: PlayerSkinSource,
        val binding: FakeSkinBinding = FakeSkinBinding(),
    ) : MinecraftUiPlatform {
        private var closed = false
        var lookupCalls: Int = 0
            private set

        override fun inventorySlot(binding: SlotBinding): MinecraftInventorySlotBinding = throw UnsupportedOperationException("This PlayerHead fixture has no inventory: $binding")

        override fun image(resource: ResourceId): DrawImage = throw UnsupportedOperationException("This PlayerHead fixture has no image resources: $resource")

        override fun playerSkin(source: PlayerSkinSource): MinecraftPlayerSkinBinding {
            assertEquals(expectedSource, source)
            lookupCalls += 1
            return binding
        }

        override fun refresh() {
            check(closed.not())
            binding.commit()
        }

        override fun close() {
            if (closed) return
            closed = true
            binding.close()
        }
    }

    private class FakeSkinBinding : MinecraftPlayerSkinBinding {
        private var current: MinecraftPlayerSkinBinding.Snapshot = MinecraftPlayerSkinBinding.Snapshot.Pending
        private var pending: MinecraftPlayerSkinBinding.Snapshot? = null
        private var observer: (() -> Unit)? = null
        var closed: Boolean = false
            private set

        override fun snapshot(): MinecraftPlayerSkinBinding.Snapshot {
            check(closed.not())
            return current
        }

        override fun observe(observer: () -> Unit): AutoCloseable {
            check(closed.not())
            check(this.observer == null)
            this.observer = observer
            return AutoCloseable {
                if (this.observer === observer) {
                    this.observer = null
                }
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            pending = null
            observer = null
        }

        fun enqueue(snapshot: MinecraftPlayerSkinBinding.Snapshot) {
            if (closed) return
            pending = snapshot
        }

        fun commit() {
            if (closed) return
            val next = pending ?: return
            pending = null
            current = next
            observer?.invoke()
        }
    }

    private companion object {
        private val loadingColor = ArgbColor(0xFFFFAA00.toInt())
        private val failureColor = ArgbColor(0xFFAA0000.toInt())
    }
}
