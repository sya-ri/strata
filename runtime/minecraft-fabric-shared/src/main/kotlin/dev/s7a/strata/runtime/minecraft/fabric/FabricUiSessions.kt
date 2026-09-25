@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.runtime.spi.RuntimeUiControl
import dev.s7a.strata.runtime.spi.RuntimeUiController
import dev.s7a.strata.screen.ScreenOpenThreadException
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiCategory
import dev.s7a.strata.ui.UiCloseReason
import dev.s7a.strata.ui.UiDefinition
import dev.s7a.strata.ui.UiInteractionMode
import dev.s7a.strata.ui.UiPresentation
import dev.s7a.strata.ui.UiRejection
import dev.s7a.strata.ui.UiScreenKind
import dev.s7a.strata.ui.UiSession
import dev.s7a.strata.ui.UiSessionStatus
import dev.s7a.strata.ui.UiVisibility
import dev.s7a.strata.ui.UiVisibilityPolicy
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen

/**
 * Client-thread ownership of native wrappers; every entry has one common retained host for its entire lifetime.
 */
@InternalStrataRuntimeApi
@Suppress("TooManyFunctions") // One registry owns native presentation, HUD drawing, input ownership, and terminal cleanup.
public object FabricUiSessions {
    private val state = Registry()
    private var drawingHud: FabricMinecraftScreen? = null

    /**
     * Opens a definition after validating the native thread, returning its stable event receiver.
     */
    @Suppress("TooGenericExceptionCaught") // Partial native ownership must close even when a user callback throws an Error.
    public fun open(
        definition: UiDefinition,
        eventSession: UiSession? = null,
    ): UiSession {
        val client = Minecraft.getInstance()
        if (client.isSameThread.not()) throw ScreenOpenThreadException("UI opening requires the Minecraft client thread.")
        FabricMinecraftCanvasHooks.requireRunning()
        val entry = Entry(state, client, definition.category, definition.visibility, definition.hudOrder)
        val controls = RuntimeUiController(definition.presentation, definition.inputPolicy, entry::apply, entry::close)
        entry.controls = controls
        try {
            val profile = cachedFabricMinecraftProfile(client.resourceManager, fabricMinecraftFontCompatibility(), fabricMinecraftFontOptions(client), ::extractMinecraftUiProfile)
            entry.screen = createMinecraftScreen(definition, profile, FabricMinecraftScreenAccess.currentScreen(client), controls, eventSession ?: controls)
            state.entries[controls] = entry
            controls.start()
            return controls
        } catch (failure: Throwable) {
            runCatching { controls.terminate(UiCloseReason.Failed) }.exceptionOrNull()?.let { if (it !== failure) failure.addSuppressed(it) }
            throw failure
        }
    }

    /**
     * Returns the native wrapper owned by one local presentation handle.
     */
    public fun nativeScreen(session: UiSession): FabricMinecraftScreen? = state.entries[session]?.screen

    /**
     * Services world/container lifetime and ordinary-screen visibility on the client thread.
     */
    public fun tick() {
        state.entries.values
            .toList()
            .forEach(Entry::refresh)
    }

    /**
     * Returns visible HUDs in stable draw order, after vanilla HUD and before native screens.
     */
    public fun visibleHuds(): List<FabricMinecraftScreen> {
        tick()
        val client = Minecraft.getInstance()
        if (FabricMinecraftScreenAccess.hudHidden(client)) return emptyList()
        return state.entries.values
            .filter { it.presentation == UiPresentation.Hud && it.visible }
            .sortedWith(Comparator { left, right -> left.order.compareTo(right.order) })
            .mapNotNull { it.screen }
    }

    /**
     * Draws every HUD in its specified order, including the current interaction owner.
     * Native Screen drawing skips HUD wrappers outside this scoped pass to avoid a second, reordered submission.
     */
    public fun renderHuds(render: (FabricMinecraftScreen, Int, Int) -> Unit) {
        val client = Minecraft.getInstance()
        val window = client.window
        val mouseX = (client.mouseHandler.xpos() * window.guiScaledWidth / maxOf(1, window.screenWidth)).toInt()
        val mouseY = (client.mouseHandler.ypos() * window.guiScaledHeight / maxOf(1, window.screenHeight)).toInt()
        visibleHuds().forEach { screen ->
            if (state.entries.values.none { it.screen === screen }) return@forEach
            drawingHud = screen
            try {
                render(screen, mouseX, mouseY)
            } finally {
                drawingHud = null
            }
        }
    }

    /**
     * Applies visibility and current viewport before either native HUD or ordinary-screen drawing.
     */
    public fun prepareRender(screen: FabricMinecraftScreen): Boolean {
        val entry = state.entries.values.find { it.screen === screen } ?: return true
        entry.refresh()
        if (entry.controls.status is UiSessionStatus.Closed || entry.visible.not()) return false
        if (entry.presentation == UiPresentation.Hud) {
            if (drawingHud !== screen) return false
            screen.prepareHud()
        }
        return true
    }

    /**
     * Passive overlays never synthesize pointer hover or consume gameplay input.
     */
    public fun acceptsPointer(screen: FabricMinecraftScreen): Boolean {
        val entry = state.entries.values.find { it.screen === screen } ?: return true
        return entry.visible && entry.controls.interactionMode == UiInteractionMode.Cursor && (entry.presentation == UiPresentation.Screen || state.interaction === entry)
    }

    /**
     * Current native input owner, excluding passive or hidden HUDs.
     */
    @JvmSynthetic
    internal fun inputOwner(screen: FabricMinecraftScreen): UiSession? =
        state.entries.values
            .find { it.screen === screen && it.visible && (it.presentation == UiPresentation.Screen || state.interaction === it) }
            ?.controls

    /**
     * Releases game and UI input on native window focus loss.
     */
    public fun resetInput() {
        FabricUiInput.reset()
        state.interaction?.controls?.setInteractionMode(UiInteractionMode.None)
        state.entries.values
            .toList()
            .forEach { it.screen?.releaseUiInput() }
    }

    /**
     * Closes every owned UI before world/device teardown; each entry removes itself before cleanup.
     */
    public fun closeAll(reason: UiCloseReason) {
        var failure: Throwable? = null
        state.entries.keys.toList().forEach { controls ->
            runCatching { controls.terminate(reason) }.exceptionOrNull()?.let { caught ->
                val primary = failure
                if (primary == null) {
                    failure = caught
                } else if (primary !== caught) {
                    primary.addSuppressed(caught)
                }
            }
        }
        failure?.let { throw it }
    }

    /**
     * Shared native ownership passed explicitly to entries, without a global session receiver.
     */
    private class Registry {
        val entries = linkedMapOf<RuntimeUiController, Entry>()
        var interaction: Entry? = null

        fun ordinaryScreen(client: Minecraft): Screen? {
            val current = FabricMinecraftScreenAccess.currentScreen(client)
            return if (interaction?.screen === current) null else current
        }

        fun classification(screen: Screen?): UiScreenKind? =
            when (screen) {
                null -> null
                is FabricMinecraftScreen -> UiScreenKind.Strata
                is AbstractContainerScreen<*> -> UiScreenKind.Inventory
                is ChatScreen -> UiScreenKind.Chat
                is PauseScreen -> UiScreenKind.Pause
                else -> UiScreenKind.Other
            }
    }

    private class Entry(
        private val state: Registry,
        val client: Minecraft,
        val category: UiCategory?,
        val visibility: UiVisibilityPolicy,
        val order: Int,
    ) {
        lateinit var controls: RuntimeUiController
        var screen: FabricMinecraftScreen? = null
        var presentation: UiPresentation? = null
        var visible = true
        private val world = client.level

        fun apply(request: RuntimeUiControl) {
            val owned = checkNotNull(screen)
            val current = FabricMinecraftScreenAccess.currentScreen(client)
            val ordinary = state.ordinaryScreen(client)
            val rejection = interactionRejection(request, ordinary, owned)
            if (rejection != null) {
                controls.rejected(request.sequence, rejection)
                return
            }
            if (current === owned || request.presentation == UiPresentation.Screen || request.interactionMode != UiInteractionMode.None) FabricUiInput.reset()
            owned.releaseUiInput()
            if (controls.status is UiSessionStatus.Closed) return
            if (state.interaction === this) state.interaction = null
            when (request.presentation) {
                UiPresentation.Screen -> {
                    if (presentation == UiPresentation.Hud) owned.navigationParent(ordinary)
                    presentation = UiPresentation.Screen
                    visible = true
                    if (current !== owned) FabricMinecraftScreenAccess.setScreen(client, owned)
                }

                UiPresentation.Hud -> {
                    presentation = UiPresentation.Hud
                    visible = true
                    if (request.interactionMode == UiInteractionMode.None) {
                        if (current === owned) FabricMinecraftScreenAccess.setScreen(client, null)
                        owned.prepareHud()
                    } else {
                        state.interaction?.controls?.setInteractionMode(UiInteractionMode.None)
                        state.interaction = this
                        if (current !== owned) FabricMinecraftScreenAccess.setScreen(client, owned)
                    }
                }
            }
            controls.applied(request.sequence)
            FabricUiInput.applyMode(owned, request.interactionMode)
            refresh()
        }

        private fun interactionRejection(
            request: RuntimeUiControl,
            ordinary: Screen?,
            owned: Screen,
        ): UiRejection? {
            if (request.presentation != UiPresentation.Hud || request.interactionMode == UiInteractionMode.None) return null
            if (ordinary != null && ordinary !== owned) return UiRejection.ScreenOpen
            return if (FabricMinecraftScreenAccess.hudHidden(client)) UiRejection.Hidden else null
        }

        fun refresh() {
            if (controls.status is UiSessionStatus.Closed) return
            if (client.level !== world) {
                controls.terminate(UiCloseReason.WorldExited)
                return
            }
            val owned = screen ?: return
            if (presentation != UiPresentation.Hud) return
            val bound = owned.boundContainer()
            if (bound != null && bound !== client.player?.containerMenu) {
                controls.terminate(UiCloseReason.ContainerChanged)
                return
            }
            val ordinary = state.ordinaryScreen(client)
            val category =
                state.entries.values
                    .find { it.screen === ordinary }
                    ?.category
            val action = if (FabricMinecraftScreenAccess.hudHidden(client)) UiVisibility.Hide else visibility.resolve(state.classification(ordinary), category)
            when (action) {
                UiVisibility.Close -> {
                    controls.terminate(UiCloseReason.Visibility)
                }

                UiVisibility.Hide -> {
                    if (visible) {
                        visible = false
                        if (state.interaction === this) controls.setInteractionMode(UiInteractionMode.None)
                        owned.hideHud()
                    }
                }

                UiVisibility.KeepVisible -> {
                    if (visible.not()) {
                        visible = true
                        owned.prepareHud()
                    }
                }
            }
        }

        fun close(reason: UiCloseReason) {
            if (FabricMinecraftScreenAccess.currentScreen(client) === screen) FabricUiInput.reset()
            state.entries.remove(controls)
            if (state.interaction === this) state.interaction = null
            val owned = screen
            screen = null
            if (owned == null) return
            if (FabricMinecraftScreenAccess.currentScreen(client) === owned) {
                if (presentation == UiPresentation.Screen && reason == UiCloseReason.Closed) {
                    owned.onClose()
                } else {
                    try {
                        owned.close()
                    } finally {
                        if (FabricMinecraftScreenAccess.currentScreen(client) === owned) FabricMinecraftScreenAccess.setScreen(client, null)
                    }
                }
            } else {
                owned.close()
            }
        }
    }
}
