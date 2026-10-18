@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.component.PlayerSkinSource
import dev.s7a.strata.component.SlotBinding
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PlatformDrawCommand
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.minecraft.MinecraftInventorySlotBinding
import dev.s7a.strata.runtime.minecraft.MinecraftPlatformCommandRenderer
import dev.s7a.strata.runtime.minecraft.MinecraftPlayerSkinBinding
import dev.s7a.strata.runtime.minecraft.MinecraftUiPlatform
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftSkinBindingLifecycle.SkinFailed
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftSkinBindingLifecycle.SkinReady
import dev.s7a.strata.runtime.remote.RemoteFailure
import dev.s7a.strata.runtime.remote.RemoteProtocolException
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.function.BooleanSupplier

/**
 * Owns retained inventory and skin bindings for one client-thread host.
 * Frames poll copied item snapshots; mutations use the native game mode and never write inventory storage directly.
 * Native input, creative capability, click encoding and rendering differences live in [FabricMinecraftInventoryNative].
 */
@Suppress("TooManyFunctions") // One platform owns binding, input and presentation lifetimes on the client thread.
internal class FabricMinecraftInventoryBridge private constructor(
    minecraft: Minecraft,
    private val skinLookup: (Minecraft, PlayerSkinSource) -> CompletableFuture<Optional<FabricMinecraftSkinReference>>,
) : FabricMinecraftInventoryPointerInput<FabricMinecraftNativeMouseInput, FabricMinecraftInventoryBridge.Binding>(),
    MinecraftUiPlatform,
    MinecraftPlatformCommandRenderer<FabricMinecraftInventoryGraphics> {
    private val ownerThread = Thread.currentThread()
    private var minecraft: Minecraft? = minecraft
    private var boundContainer: AbstractContainerMenu? = null
    private val bindings = linkedSetOf<Binding>()
    private val skinBindings = linkedSetOf<SkinBinding>()
    private var refreshBatchDepth = 0
    private var refreshedInBatch = false

    private class ItemCommand(
        val stack: ItemStack,
        val seed: Int,
    ) : PlatformDrawCommand

    /**
     * Owns one copied slot snapshot and retained observer until binding or platform release.
     */
    inner class Binding internal constructor(
        private val locator: SlotBinding,
    ) : MinecraftInventorySlotBinding {
        private var snapshot = ItemStack.EMPTY
        private var observer: (() -> Unit)? = null
        private var released = false

        override fun drawCommand(): PlatformDrawCommand? {
            requireUsable()
            return if (snapshot.isEmpty) null else ItemCommand(snapshot.copy(), resolveSlot().index)
        }

        override fun observe(observer: () -> Unit): AutoCloseable {
            requireUsable()
            check(this.observer == null) { "Minecraft inventory Slot already has a retained observer." }
            this.observer = observer
            return minecraftOwnerSubscription(ownerThread) { if (this.observer === observer) this.observer = null }
        }

        override fun dispatchPointer(event: PointerEvent): InputResult {
            requireUsable()
            val current = input
            if ((event is PointerEvent.Move || event is PointerEvent.Drag) && offeredHover == null) offeredHover = this
            if (current == null) return InputResult.Ignored
            current.delivered = this
            return when (event) {
                is PointerEvent.Press -> handlePress(this)
                is PointerEvent.Drag -> handleDrag(this)
                is PointerEvent.Release -> handleRelease(this)
                else -> InputResult.Ignored
            }
        }

        override fun close() {
            requireOwnerThread()
            if (released) return
            releaseFromPlatform()
            bindings.remove(this)
            releasePointerTarget(this)
            if (offeredHover === this) offeredHover = null
        }

        /**
         * Polls this live binding once and notifies after replacing its copied snapshot.
         */
        internal fun refresh() {
            val current = resolveSlot().item
            if (ItemStack.matches(snapshot, current)) return
            snapshot = if (current.isEmpty) ItemStack.EMPTY else current.copy()
            observer?.invoke()
        }

        /**
         * Releases retained state without mutating the platform's binding traversal.
         */
        internal fun releaseFromPlatform() {
            released = true
            observer = null
            snapshot = ItemStack.EMPTY
        }

        /**
         * Borrows the current snapshot only while submitting an owner-thread overlay.
         */
        internal fun overlaySnapshot(): ItemStack = snapshot

        /**
         * Resolves the declarative coordinate system against the bound native container generation.
         */
        internal fun resolveSlot(): Slot {
            val player = requirePlayer()
            val menu = activeMenu()
            if (boundContainer == null) boundContainer = activeMenu()
            val index = locator.index
            return when (locator.source) {
                SlotBinding.Source.PlayerInventory -> {
                    val menuIndex =
                        menu.findSlot(player.inventory, index).orElseThrow {
                            IllegalArgumentException("Player inventory index is not exposed by the active menu: $index")
                        }
                    menu.getSlot(menuIndex)
                }

                SlotBinding.Source.Container -> {
                    resolveContainerSlot(menu, player.inventory, index)
                }

                SlotBinding.Source.ActiveMenu -> {
                    require(0 <= index && index < menu.slots.size) { "Active menu slot index is outside the current menu: $index" }
                    menu.getSlot(index)
                }
            }
        }

        private fun requireUsable() {
            requireOwnerThread()
            check(released.not()) { "Minecraft inventory Slot binding is closed." }
            this@FabricMinecraftInventoryBridge.requireUsable()
        }
    }

    private inner class SkinBinding(
        source: PlayerSkinSource,
    ) : MinecraftPlayerSkinBinding {
        private val lifecycle = FabricMinecraftSkinBindingLifecycle()
        private var observer: (() -> Unit)? = null

        init {
            // The asynchronous callback captures only the independently releasable completion target.
            val completionTarget = lifecycle
            skinLookup(requireMinecraft(), source).whenComplete { resolved, failure ->
                if (failure == null && resolved.isPresent) {
                    completionTarget.publish(SkinReady(resolved.get()))
                } else {
                    completionTarget.publish(SkinFailed.INSTANCE)
                }
            }
        }

        override fun snapshot(): MinecraftPlayerSkinBinding.Snapshot {
            requireUsable()
            return lifecycle.retainedSnapshot()
        }

        override fun observe(observer: () -> Unit): AutoCloseable {
            requireUsable()
            check(this.observer == null) { "Minecraft player skin already has a retained observer." }
            this.observer = observer
            return minecraftOwnerSubscription(ownerThread) { if (this.observer === observer) this.observer = null }
        }

        override fun close() {
            requireOwnerThread()
            if (releaseState()) skinBindings.remove(this)
        }

        fun refresh() {
            if (lifecycle.isClosed) return
            val completion = lifecycle.drainCompletion() ?: return
            val next =
                if (completion is SkinReady) {
                    try {
                        MinecraftPlayerSkinBinding.Snapshot.Ready(completion.skin.snapshot(requireMinecraft()))
                    } catch (_: RuntimeException) {
                        MinecraftPlayerSkinBinding.Snapshot.Failed
                    }
                } else {
                    MinecraftPlayerSkinBinding.Snapshot.Failed
                }
            if (lifecycle.commitSnapshot(next)) observer?.invoke()
        }

        fun releaseFromPlatform() {
            releaseState()
        }

        private fun releaseState(): Boolean {
            val released = lifecycle.close()
            observer = null
            return released
        }

        private fun requireUsable() {
            requireOwnerThread()
            check(lifecycle.isClosed.not()) { "Minecraft player skin binding is closed." }
            this@FabricMinecraftInventoryBridge.requireUsable()
        }
    }

    /**
     * Reports whether retained slots depend on the current container generation.
     */
    fun hasBindings(): Boolean = bindings.isNotEmpty()

    /**
     * Returns the first container used by a slot, without binding an unrelated HUD during observation.
     */
    fun boundContainer(): AbstractContainerMenu? = boundContainer

    override fun inventorySlot(binding: SlotBinding): MinecraftInventorySlotBinding {
        requireUsable()
        if (boundContainer == null) boundContainer = activeMenu()
        val index = binding.index
        require(0 <= index) { "Minecraft Slot binding index must be non-negative: $index" }
        when (binding.source) {
            SlotBinding.Source.PlayerInventory -> {
                require(index < requirePlayer().inventory.containerSize) {
                    "Player inventory index is outside the active inventory: $index"
                }
            }

            SlotBinding.Source.Container -> {
                resolveContainerSlot(activeMenu(), requirePlayer().inventory, index)
            }

            SlotBinding.Source.ActiveMenu -> {
                require(index < activeMenu().slots.size) {
                    "Active menu slot index is outside the current menu: $index"
                }
            }
        }
        return Binding(binding).also(bindings::add)
    }

    override fun image(resource: ResourceId): DrawImage {
        requireUsable()
        return loadMinecraftUiImage(resource)
    }

    override fun playerSkin(source: PlayerSkinSource): MinecraftPlayerSkinBinding {
        requireUsable()
        require((source is PlayerSkinSource.Pixels).not()) { "Direct player skin pixels do not require a platform lookup." }
        return SkinBinding(source).also(skinBindings::add)
    }

    override fun refresh() {
        requireUsable()
        if (0 < refreshBatchDepth && refreshedInBatch) return
        refreshMinecraftBindings(bindings, Binding::refresh)
        refreshMinecraftBindings(skinBindings, SkinBinding::refresh)
        if (0 < refreshBatchDepth) refreshedInBatch = true
    }

    override fun close() {
        requireOwnerThread()
        if (closed) return
        releasePointerState()
        input = null
        offeredHover = null
        bindings.forEach(Binding::releaseFromPlatform)
        bindings.clear()
        skinBindings.forEach(SkinBinding::releaseFromPlatform)
        skinBindings.clear()
        boundContainer = null
        minecraft = null
    }

    /**
     * Shares at most one inventory and skin refresh across nested render extraction, releasing batch state on every exit.
     */
    fun <T> withRefreshBatch(operation: () -> T): T {
        requireUsable()
        val outermost = refreshBatchDepth == 0
        if (outermost) refreshedInBatch = false
        refreshBatchDepth += 1
        try {
            return operation()
        } finally {
            refreshBatchDepth -= 1
            if (outermost) refreshedInBatch = false
        }
    }

    /**
     * Dispatches a pointer move while collecting the topmost synchronized slot as the keyboard target.
     */
    fun withPointerMove(operation: BooleanSupplier): Boolean = withInput(NativeInput(InputKind.MOVE, null, 0, shift = false, doubleClick = false), operation)

    /**
     * Dispatches one native press with its immutable modifiers and double-click marker.
     */
    fun withMousePress(
        event: FabricMinecraftNativeMouseInput,
        doubleClick: Boolean,
        operation: BooleanSupplier,
    ): Boolean = withInput(NativeInput(InputKind.PRESS, event, FabricMinecraftInventoryNative.containerButton(event), FabricMinecraftInventoryNative.shiftDown(event), doubleClick), operation)

    /**
     * Records eligible synchronized slots crossed by a native drag.
     */
    fun withMouseDrag(
        event: FabricMinecraftNativeMouseInput,
        operation: BooleanSupplier,
    ): Boolean = withInput(NativeInput(InputKind.DRAG, event, FabricMinecraftInventoryNative.containerButton(event), FabricMinecraftInventoryNative.shiftDown(event), doubleClick = false), operation)

    /**
     * Completes a native pickup, double-click or quick-craft transaction after successful host dispatch.
     */
    fun withMouseRelease(
        event: FabricMinecraftNativeMouseInput,
        operation: BooleanSupplier,
    ): Boolean = withInput(NativeInput(InputKind.RELEASE, event, FabricMinecraftInventoryNative.containerButton(event), FabricMinecraftInventoryNative.shiftDown(event), doubleClick = false), operation)

    /**
     * Applies authoritative hotbar, offhand, pick and drop actions for the last delivered hover target.
     * The version adapter's key matcher is borrowed synchronously and is never retained.
     */
    fun handleKeyPressed(
        controlDown: Boolean,
        matches: (KeyMapping) -> Boolean,
    ): Boolean {
        requireUsable()
        val target = hovered ?: return false
        val client = requireMinecraft()
        val player = requirePlayer()
        if (activeMenu().carried.isEmpty) {
            if (matches(client.options.keySwapOffhand)) {
                click(target, 40, FabricMinecraftInventoryAction.SWAP)
                return true
            }
            for ((index, mapping) in client.options.keyHotbarSlots.withIndex()) {
                if (matches(mapping)) {
                    click(target, index, FabricMinecraftInventoryAction.SWAP)
                    return true
                }
            }
        }
        val slot = target.resolveSlot()
        if (slot.hasItem() && matches(client.options.keyPickItem) && FabricMinecraftInventoryNative.hasInfiniteMaterials(player)) {
            click(target, 0, FabricMinecraftInventoryAction.CLONE)
            return true
        }
        if (slot.hasItem() && matches(client.options.keyDrop)) {
            click(target, if (controlDown) 1 else 0, FabricMinecraftInventoryAction.THROW)
            return true
        }
        return false
    }

    override fun accepts(command: PlatformDrawCommand): Boolean {
        requireOwnerThread()
        if ((command is ItemCommand).not()) return false
        requireUsable()
        return true
    }

    override fun render(
        target: FabricMinecraftInventoryGraphics,
        command: DrawCommand.Platform,
    ) {
        renderItem(target, requireMinecraft().font, command.command, command.bounds.left, command.bounds.top)
    }

    /**
     * Renders an owned copied item payload at its logical origin; foreign payloads fail before drawing.
     */
    fun renderItem(
        graphics: FabricMinecraftInventoryGraphics,
        font: Font,
        command: PlatformDrawCommand,
        x: Int,
        y: Int,
    ) {
        requireUsable()
        require(command is ItemCommand) { "Unsupported Fabric platform draw command: ${command.javaClass.name}" }
        FabricMinecraftInventoryNative.renderItem(graphics, font, command.stack, x, y, command.seed)
    }

    /**
     * Submits copied carried items before the current hovered tooltip on the client owner.
     */
    fun renderOverlays(
        graphics: FabricMinecraftInventoryGraphics,
        font: Font,
        mouseX: Int,
        mouseY: Int,
    ) {
        FabricMinecraftInventoryOverlays.render(
            { if (requireMinecraft().player == null) null else activeMenu().carried },
            { if (requireMinecraft().player == null) null else hovered?.overlaySnapshot() },
            { snapshot, carried -> FabricMinecraftSlotTooltipRenderer.render(graphics, font, snapshot, carried, mouseX, mouseY) },
            { snapshot -> FabricMinecraftInventoryNative.renderCarried(graphics, font, snapshot, Math.subtractExact(mouseX, 8), Math.subtractExact(mouseY, 8)) },
        )
    }

    override fun checkHotbarMouse(binding: Binding): Boolean {
        val event = checkNotNull(requireInput(InputKind.PRESS).mouse)
        val client = requireMinecraft()
        if (activeMenu().carried.isEmpty.not()) return false
        if (FabricMinecraftInventoryNative.matchesMouse(client.options.keySwapOffhand, event)) {
            click(binding, 40, FabricMinecraftInventoryAction.SWAP)
            return true
        }
        for ((index, mapping) in client.options.keyHotbarSlots.withIndex()) {
            if (FabricMinecraftInventoryNative.matchesMouse(mapping, event)) {
                click(binding, index, FabricMinecraftInventoryAction.SWAP)
                return true
            }
        }
        return false
    }

    override fun isCreativePick(mouse: FabricMinecraftNativeMouseInput): Boolean = isPickMouse(mouse) && FabricMinecraftInventoryNative.hasInfiniteMaterials(requirePlayer())

    override fun isPickMouse(mouse: FabricMinecraftNativeMouseInput): Boolean = FabricMinecraftInventoryNative.matchesMouse(requireMinecraft().options.keyPickItem, mouse)

    override fun resolveSlot(binding: Binding): Slot = binding.resolveSlot()

    override fun clickRaw(
        slot: Int,
        button: Int,
        action: FabricMinecraftInventoryAction,
    ) {
        val client = requireMinecraft()
        val player = requirePlayer()
        val gameMode = checkNotNull(client.gameMode) { "Minecraft inventory input requires an active game mode." }
        FabricMinecraftInventoryNative.click(gameMode, activeMenu().containerId, slot, button, action, player)
    }

    override fun requirePlayer(): LocalPlayer = requireMinecraft().player ?: throw NullPointerException("Minecraft inventory Slots require an active player.")

    override fun activeMenu(): AbstractContainerMenu {
        val menu = requirePlayer().containerMenu
        if (boundContainer != null && boundContainer !== menu) {
            throw RemoteProtocolException(RemoteFailure.ContainerChanged, "UI Slot container generation changed.")
        }
        return menu
    }

    private fun withInput(
        current: NativeInput<FabricMinecraftNativeMouseInput, Binding>,
        operation: BooleanSupplier,
    ): Boolean {
        requireUsable()
        return dispatchInput(current, operation)
    }

    private fun resolveContainerSlot(
        menu: AbstractContainerMenu,
        playerInventory: Inventory,
        index: Int,
    ): Slot {
        var resolved: Slot? = null
        for (slot in menu.slots) {
            if (slot.container === playerInventory || slot.containerSlot != index) continue
            require(resolved == null || resolved.container === slot.container) { "Container slot index is ambiguous across the active menu: $index" }
            resolved = slot
        }
        return requireNotNull(resolved) { "Container slot index is not exposed by the active menu: $index" }
    }

    private fun requireMinecraft(): Minecraft {
        requireUsable()
        return checkNotNull(minecraft)
    }

    private fun requireUsable() {
        requireOwnerThread()
        check(closed.not()) { "Minecraft inventory platform is closed." }
    }

    private fun requireOwnerThread() {
        check(Thread.currentThread() === ownerThread) { "Minecraft inventory platform requires its creator thread." }
    }

    /**
     * Validates client-thread ownership before creating a native platform bridge.
     */
    companion object {
        /**
         * Creates a client-thread bridge whose resolver completes with detached descriptors or pixels.
         */
        fun create(
            minecraft: Minecraft,
            skinLookup: (Minecraft, PlayerSkinSource) -> CompletableFuture<Optional<FabricMinecraftSkinReference>>,
        ): FabricMinecraftInventoryBridge {
            check(minecraft.isSameThread) { "Minecraft inventory Slots require the client thread." }
            return FabricMinecraftInventoryBridge(minecraft, skinLookup)
        }
    }
}
