package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.render.PlatformDrawCommand
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies synchronized Slot ownership, invalidation, command order, and input delegation without a loaded game.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftSynchronizedSlotTest {
    @Test
    fun synchronizedSlotRefreshesAndKeepsPlatformDrawingBetweenHighlightLayers() {
        val platform = FakePlatform()
        platform.binding.command = ItemCommand.First
        val host = createHost(platform)
        host.attach()

        val initial = host.frame(IntSize(18, 18))
        val initialItem = initial.drawCommands.single() as DrawCommand.Platform
        assertSame(ItemCommand.First, initialItem.command)
        assertEquals(IntRect(1, 1, 17, 17), initialItem.bounds)
        assertEquals(1, platform.refreshCount)

        assertSame(InputResult.Ignored, host.dispatchPointer(PointerEvent.Move(IntOffset(2, 2))))
        val hovered = host.frame(IntSize(18, 18))
        assertEquals(3, hovered.drawCommands.size)
        assertTrue(hovered.drawCommands.first() is DrawCommand.BlitImage)
        assertSame(ItemCommand.First, (hovered.drawCommands[1] as DrawCommand.Platform).command)
        assertTrue(hovered.drawCommands.last() is DrawCommand.BlitImage)

        platform.binding.command = ItemCommand.Second
        platform.binding.changed = true
        val changed = host.frame(IntSize(18, 18))
        assertSame(ItemCommand.Second, (changed.drawCommands[1] as DrawCommand.Platform).command)
        assertEquals(3, platform.refreshCount)

        platform.binding.command = null
        platform.binding.changed = true
        val empty = host.frame(IntSize(18, 18))
        assertEquals(2, empty.drawCommands.size)
        assertTrue(empty.drawCommands.all { command -> command is DrawCommand.BlitImage })

        host.close()
        assertTrue(platform.closed)
        assertNull(platform.binding.observer)
    }

    @Test
    fun synchronizedSlotDelegatesHitInputAndRejectsPortableOnlyHosts() {
        val platform = FakePlatform()
        val host = createHost(platform)
        host.attach()
        host.frame(IntSize(18, 18))
        val press = PointerEvent.Press(IntOffset(4, 5), PointerButton.Primary)
        assertSame(InputResult.Consumed, host.dispatchPointer(press))
        assertSame(press, platform.binding.lastEvent)
        host.close()

        val portable =
            createMinecraftUiHost(
                createMinecraftScreenDefinition("portable") { Slot(bind = MinecraftSlots.playerInventory(0)) },
                MinecraftProfileFixture.create(),
            )
        val failure = assertThrows(IllegalStateException::class.java) { portable.attach() }
        assertTrue(failure.message.orEmpty().contains("versioned Minecraft platform"))
        portable.close()
    }

    @Test
    fun slotLocatorsAreTypedImmutableValuesAndActiveMenuBindingsReachThePlatform() {
        val player = MinecraftSlots.playerInventory(7)
        assertEquals(player, MinecraftSlots.playerInventory(7))
        assertSame(MinecraftSlotSource.PlayerInventory, player.source)
        assertEquals(7, player.index)

        val active = MinecraftSlots.activeMenu(3)
        assertEquals(active, MinecraftSlots.activeMenu(3))
        assertSame(MinecraftSlotSource.ActiveMenu, active.source)
        assertEquals(3, active.index)
        assertThrows(IllegalArgumentException::class.java) { MinecraftSlots.playerInventory(-1) }
        assertThrows(IllegalArgumentException::class.java) { MinecraftSlots.activeMenu(-1) }

        val platform = FakePlatform(active)
        val host = createHost(platform, active)
        host.attach()
        host.frame(IntSize(18, 18))
        host.close()
        assertEquals(active, platform.requestedBinding)
    }

    @Test
    fun platformRefreshAndCloseFailuresPreserveIdentityAndReleaseOnce() {
        val refreshFailure = IllegalStateException("refresh")
        val cleanupFailure = IllegalStateException("cleanup")
        val failingRefresh = FakePlatform(refreshFailure = refreshFailure, closeFailure = cleanupFailure)
        val refreshHost = createHost(failingRefresh)
        refreshHost.attach()

        val thrown = assertThrows(IllegalStateException::class.java) { refreshHost.frame(IntSize(18, 18)) }
        assertSame(refreshFailure, thrown)
        assertEquals(listOf(cleanupFailure), thrown.suppressed.toList())
        assertEquals(1, failingRefresh.closeCount)
        refreshHost.close()
        assertEquals(1, failingRefresh.closeCount)

        val closeFailure = IllegalStateException("close")
        val failingClose = FakePlatform(closeFailure = closeFailure)
        val closeHost = createHost(failingClose)
        val closeThrown = assertThrows(IllegalStateException::class.java) { closeHost.close() }
        assertSame(closeFailure, closeThrown)
        assertEquals(1, failingClose.closeCount)
        closeHost.close()
        assertEquals(1, failingClose.closeCount)
    }

    private fun createHost(
        platform: FakePlatform,
        binding: MinecraftSlotBinding = MinecraftSlots.playerInventory(7),
    ): MinecraftUiHost =
        createMinecraftUiHost(
            createMinecraftScreenDefinition("inventory") { Slot(bind = binding) },
            MinecraftProfileFixture.create(),
            platform,
        )

    private enum class ItemCommand : PlatformDrawCommand {
        First,
        Second,
    }

    private class FakePlatform(
        private val expectedBinding: MinecraftSlotBinding = MinecraftSlots.playerInventory(7),
        private val refreshFailure: Throwable? = null,
        private val closeFailure: Throwable? = null,
    ) : MinecraftUiPlatform {
        val binding = FakeBinding()
        var refreshCount = 0
        var closeCount = 0
        var closed = false
        var requestedBinding: MinecraftSlotBinding? = null

        override fun inventorySlot(binding: MinecraftSlotBinding): MinecraftInventorySlotBinding {
            assertEquals(expectedBinding, binding)
            requestedBinding = binding
            return this.binding
        }

        override fun refresh() {
            check(closed.not())
            refreshCount += 1
            binding.refresh()
            refreshFailure?.let { throw it }
        }

        override fun close() {
            if (closed) return
            closed = true
            closeCount += 1
            binding.observer = null
            closeFailure?.let { throw it }
        }
    }

    private class FakeBinding : MinecraftInventorySlotBinding {
        var command: PlatformDrawCommand? = null
        var observer: (() -> Unit)? = null
        var changed = false
        var lastEvent: PointerEvent? = null

        override fun drawCommand(): PlatformDrawCommand? = command

        override fun observe(observer: () -> Unit): AutoCloseable {
            check(this.observer == null)
            this.observer = observer
            return AutoCloseable {
                if (this.observer === observer) this.observer = null
            }
        }

        override fun dispatchPointer(event: PointerEvent): InputResult {
            lastEvent = event
            return if (event is PointerEvent.Press) InputResult.Consumed else InputResult.Ignored
        }

        fun refresh() {
            if (changed) {
                changed = false
                observer?.invoke()
            }
        }
    }
}
