@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.InputConstants
import dev.s7a.strata.runtime.minecraft.fabric.mixin.lifecycle.FabricUiKeyMappingAccess
import dev.s7a.strata.runtime.minecraft.fabric.mixin.lifecycle.FabricUiMinecraftAccess
import dev.s7a.strata.runtime.minecraft.fabric.mixin.lifecycle.FabricUiMouseAccess
import dev.s7a.strata.runtime.spi.RuntimeUiInput
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiGameAction
import dev.s7a.strata.ui.UiInputPolicy
import dev.s7a.strata.ui.UiInteractionMode
import dev.s7a.strata.ui.UiSession
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft

/**
 * Client-thread input bridge using current native mappings and vanilla gameplay handlers.
 */
@InternalStrataRuntimeApi
@Suppress("TooManyFunctions") // Native keyboard, mouse, focus, and tick hooks share one bounded input gate.
public object FabricUiInput {
    private val client: Minecraft get() = Minecraft.getInstance()
    private val bindings: Map<KeyMapping, UiGameAction> by lazy {
        val options = client.options
        buildMap {
            listOf(options.keyUp, options.keyDown, options.keyLeft, options.keyRight).forEach { put(it, UiGameAction.Movement) }
            put(options.keyJump, UiGameAction.Jump)
            put(options.keyShift, UiGameAction.Sneak)
            put(options.keySprint, UiGameAction.Sprint)
            put(options.keyAttack, UiGameAction.Attack)
            put(options.keyUse, UiGameAction.Use)
            put(options.keyDrop, UiGameAction.Drop)
            put(options.keySwapOffhand, UiGameAction.SwapHands)
            put(options.keyPickItem, UiGameAction.Pick)
            options.keyHotbarSlots.forEach { put(it, UiGameAction.Hotbar) }
        }
    }
    private val gate: RuntimeUiInput<KeyMapping> by lazy {
        RuntimeUiInput(bindings, press = { mapping ->
            val access = mapping as FabricUiKeyMappingAccess
            mapping.isDown = true
            access.strataClicks(minOf(Int.MAX_VALUE - 1, access.strataClicks()) + 1)
        }, release = ::releaseMinecraftUiBinding)
    }

    /**
     * True only while calling the original gameplay dispatcher for the current retained owner.
     */
    public var dispatching: Boolean = false
        private set

    /**
     * True only while native mouse capture must preserve the current UI wrapper.
     */
    public var capturing: Boolean = false
        private set

    /**
     * True only during a second native scroll call after the UI declined the first event.
     */
    public var scrolling: Boolean = false
        private set

    /**
     * Forwards an actual key binding after synchronous UI handling, never after that handling replaces its owner.
     */
    @JvmSynthetic
    internal fun key(
        screen: FabricMinecraftScreen,
        key: InputConstants.Key,
        down: Boolean,
        operation: () -> Boolean,
    ): Boolean {
        val consumed = (key.value == InputConstants.KEY_ESCAPE || FabricUiSessions.acceptsPointer(screen)) && operation()
        forward(screen, key, down, consumed)
        return consumed
    }

    /**
     * Mouse bindings use their remapped physical button rather than fixed attack/use button numbers.
     */
    @JvmSynthetic
    internal fun button(
        screen: FabricMinecraftScreen,
        button: Int,
        down: Boolean,
        operation: () -> Boolean,
    ): Boolean {
        val consumed = FabricUiSessions.acceptsPointer(screen) && operation()
        forward(screen, InputConstants.Type.MOUSE.getOrCreate(button), down, consumed)
        return consumed
    }

    /**
     * Unconsumed scroll follows vanilla wheel accumulation and selected-slot behavior.
     */
    @JvmSynthetic
    internal fun scroll(
        screen: FabricMinecraftScreen,
        operation: () -> Boolean,
    ): Boolean {
        val consumed = FabricUiSessions.acceptsPointer(screen) && operation()
        val owner = configure(screen)
        val window = scrollWindow
        val permitted = owner?.inputPolicy?.hotbar == true && screen.isEditingText().not()
        if (consumed.not() && window != null && permitted) {
            scrolling = true
            try {
                (client.mouseHandler as FabricUiMouseAccess).strataScroll(
                    window,
                    scrollHorizontal,
                    scrollVertical,
                )
            } finally {
                scrolling = false
            }
        }
        return consumed
    }

    /**
     * Services forwarded presses once per native tick; no custom movement or interaction packets are emitted.
     */
    public fun tick() {
        val screen = FabricMinecraftScreenAccess.currentScreen(client) as? FabricMinecraftScreen
        val owner = configure(screen) ?: return
        if (screen == null || client.isPaused) return
        if (owner.inputPolicy == UiInputPolicy.BlockAll || screen.isEditingText()) return
        val access = client as FabricUiMinecraftAccess
        dispatching = true
        try {
            access.strataHandleKeys()
            if (0 < access.strataMissTime()) access.strataMissTime(access.strataMissTime() - 1)
        } finally {
            dispatching = false
        }
    }

    /**
     * Replaces only the screen-imposed delay, preserving native attack cooldowns.
     */
    public fun attackDelay(original: Int): Int {
        val screen = FabricMinecraftScreenAccess.currentScreen(client) as? FabricMinecraftScreen ?: return original
        return if (FabricUiSessions.inputOwner(screen) != null) (client as FabricUiMinecraftAccess).strataMissTime() else original
    }

    /**
     * Captures raw wheel values before native sensitivity/accumulation, for one event only.
     */
    public fun beginScroll(
        window: Long,
        horizontal: Double,
        vertical: Double,
    ) {
        if (scrolling) return
        scrollWindow = window
        scrollHorizontal = horizontal
        scrollVertical = vertical
    }

    /**
     * Releases the raw wheel event after native dispatch returns.
     */
    public fun endScroll() {
        if (scrolling.not()) scrollWindow = null
    }

    private var scrollWindow: Long? = null
    private var scrollHorizontal = 0.0
    private var scrollVertical = 0.0

    /**
     * Camera turning is available only in explicit look mode with the matching permission.
     */
    public fun blocksLook(): Boolean {
        val screen = FabricMinecraftScreenAccess.currentScreen(client) as? FabricMinecraftScreen ?: return false
        val owner = FabricUiSessions.inputOwner(screen) ?: return false
        return owner.interactionMode != UiInteractionMode.Look || owner.inputPolicy.look.not() || screen.isEditingText()
    }

    /**
     * Changes native capture after the presentation is installed, preserving the same retained screen.
     */
    @JvmSynthetic
    internal fun applyMode(
        screen: FabricMinecraftScreen,
        mode: UiInteractionMode,
    ) {
        if (FabricMinecraftScreenAccess.currentScreen(client) !== screen) return
        reset()
        if (mode == UiInteractionMode.Look) {
            capturing = true
            try {
                client.mouseHandler.grabMouse()
            } finally {
                capturing = false
            }
        } else {
            client.mouseHandler.releaseMouse()
        }
        (client as FabricUiMinecraftAccess).strataMissTime(0)
    }

    /**
     * Clears held keys, toggle state, queued clicks, and retained input ownership at every boundary.
     */
    public fun reset() {
        gate.configure(null, UiInputPolicy.BlockAll, false)
        bindings.keys.forEach(::releaseMinecraftUiBinding)
    }

    /**
     * Releases forwarded presses immediately when a retained frame changes editable focus.
     */
    @JvmSynthetic
    internal fun synchronize(screen: FabricMinecraftScreen) {
        if (FabricMinecraftScreenAccess.currentScreen(client) === screen) configure(screen)
    }

    private fun forward(
        screen: FabricMinecraftScreen,
        key: InputConstants.Key,
        down: Boolean,
        consumed: Boolean,
    ) {
        configure(screen)
        gate.event(bindings.keys.filter { (it as FabricUiKeyMappingAccess).strataKey() == key }, down, consumed)
    }

    private fun configure(screen: FabricMinecraftScreen?): UiSession? {
        val current = FabricMinecraftScreenAccess.currentScreen(client)
        val gameplayAvailable = client.isWindowActive && client.player != null && FabricMinecraftScreenAccess.hasOverlay(client).not()
        val owner = if (screen != null && current === screen && gameplayAvailable) FabricUiSessions.inputOwner(screen) else null
        gate.configure(owner, owner?.inputPolicy ?: UiInputPolicy.BlockAll, screen?.isEditingText() == true)
        return owner
    }
}
