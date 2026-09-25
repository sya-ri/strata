@file:JvmName("FabricMinecraftScreens")
@file:Suppress("DEPRECATION", "MatchingDeclarationName", "ktlint:standard:filename")

package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.InputConstants
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.diagnostics.UiRenderMonitor
import dev.s7a.strata.runtime.minecraft.MinecraftUiHost
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.minecraft.fabric.mixin.lifecycle.FabricUiKeyMappingAccess
import dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.spi.RuntimeUiController
import dev.s7a.strata.runtime.spi.RuntimeUiDiagnosticsOwner
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiDefinition
import dev.s7a.strata.ui.UiInteractionMode
import dev.s7a.strata.ui.UiPresentation
import dev.s7a.strata.ui.UiSession
import net.minecraft.Util
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import org.lwjgl.glfw.GLFW

// Why: Minecraft 1.21.8 uses primitive screen callbacks; this boundary preserves the shared host and inventory contracts without reflective dispatch.

/**
 * Fabric client screen backed by one common Minecraft UI host on Minecraft 1.21.8 and compatible primitive-input releases.
 *
 * The host is confined to the client thread and is terminally owned by this screen.
 * Native primitive callbacks are detached into the same typed common input protocol used by newer adapters.
 * The optional parent is retained for navigation but is never owned or closed.
 *
 * @see createMinecraftScreen
 */
@OptIn(InternalStrataRuntimeApi::class)
@Suppress("TooManyFunctions", "TooGenericExceptionCaught")
public class FabricMinecraftScreen private constructor(
    private val host: MinecraftUiHost,
    private val inventory: FabricMinecraftInventoryBridge,
    private var parent: Screen?,
    private val minecraftClient: Minecraft,
    private val controls: RuntimeUiController?,
) : Screen(mapMinecraftText(host.title)),
    AutoCloseable,
    RuntimeUiDiagnosticsOwner,
    FabricMinecraftInputReset {
    private var closed = false

    @InternalStrataRuntimeApi
    override fun startRenderMonitoring(): UiRenderMonitor = host.startRenderMonitoring()

    private var attached = false
    private var lastClickTime = 0L
    private var lastClickButton = Int.MIN_VALUE
    private val characterInput = FabricMinecraftCharacterInput()
    private val presentation = FabricMinecraftFramePresenter(minecraftClient)
    private val canvasPresentation = FabricMinecraftCanvasPresentation()
    private val pausePolicy = host.pausesGame
    private val lifecycle =
        FabricScreenLifecycleTransaction.create(
            { attachHost() },
            { detachHost() },
            { closeHost() },
            { FabricMinecraftScreenAccess.setScreen(minecraftClient, parent) },
        )

    override fun added() {
        requireClientThread()
        FabricMinecraftCanvasHooks.requireRunning()
        characterInput.reset()
        check(closed.not()) { "A closed Fabric Minecraft screen cannot be added again." }
        if (lifecycle.isActive()) {
            super.added()
            lifecycle.requestAttach()
            return
        }
        try {
            runHost {
                super.added()
                lifecycle.requestAttach()
            }
        } catch (failure: Throwable) {
            terminalFailure(failure)
        }
    }

    override fun removed() {
        requireClientThread()
        characterInput.reset()
        if (lifecycle.isActive()) {
            super.removed()
            lifecycle.requestDetach()
            return
        }
        try {
            runHost {
                super.removed()
                lifecycle.requestDetach()
            }
        } catch (failure: Throwable) {
            terminalFailure(failure)
        }
    }

    override fun renderBackground(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        requireClientThread()
        if (controls?.presentation != UiPresentation.Hud) super.renderBackground(graphics, mouseX, mouseY, partialTick)
    }

    override fun render(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        requireClientThread()
        if (FabricUiSessions.prepareRender(this).not()) return
        characterInput.reset()
        var guiFailure: Throwable? = null
        try {
            presentation.recordRenderExtraction()
            val frameTime = FrameTime(System.nanoTime())
            val frame =
                inventory.withRefreshBatch {
                    runHost {
                        val viewport = IntSize(width, height)
                        presentation.recordHostFrame()
                        var frame = host.frame(viewport, frameTime)
                        if (lifecycle.hasPendingExit()) return@runHost null
                        if (width == 0 || height == 0) {
                            presentation.release()
                            canvasPresentation.release()
                            return@runHost null
                        }
                        val currentPointer = IntOffset(mouseX, mouseY)
                        val pointerNeedsDispatch = presentation.needsPointerDispatch(currentPointer, frame.drawCommands)
                        if (pointerNeedsDispatch && FabricUiSessions.acceptsPointer(this)) {
                            presentation.recordExtractedPointerDispatch(currentPointer, frame.drawCommands)
                            inventory.withPointerMove { host.dispatchPointer(PointerEvent.Move(currentPointer)) == InputResult.Consumed }
                            if (lifecycle.hasPendingExit()) return@runHost null
                            presentation.recordHostFrame()
                            frame = host.frame(viewport, frameTime)
                            if (lifecycle.hasPendingExit()) return@runHost null
                        }
                        frame
                    }
                } ?: return
            if (attached.not()) return
            canvasPresentation.present(
                frame.drawCommands,
                frameTime,
                minecraftClient.window.guiScale,
                inventory,
                FabricNativeCanvasDriver::draw,
            ) { commands, dispatch ->
                presentation.present(graphics, commands, frame.size, dispatch::render)
            }
            if (attached.not()) return
            if (FabricUiSessions.acceptsPointer(this)) inventory.renderCarried(graphics, minecraftClient.font, mouseX, mouseY)
        } catch (failure: Throwable) {
            guiFailure = failure
            terminalFailure(failure)
        } finally {
            try {
                finishCanvasGui(graphics, guiFailure)
            } catch (failure: Throwable) {
                terminalFailure(failure)
            }
        }
    }

    override fun isPauseScreen(): Boolean = pausePolicy && controls?.presentation != UiPresentation.Hud

    override fun mouseMoved(
        mouseX: Double,
        mouseY: Double,
    ) {
        requireClientThread()
        if (FabricUiSessions.acceptsPointer(this).not()) return
        val position = positionOrNull(mouseX, mouseY) ?: return
        inventory.withPointerMove { dispatch(PointerEvent.Move(position)) }
        presentation.recordPointerInput(position)
    }

    override fun mouseClicked(
        mouseX: Double,
        mouseY: Double,
        buttonValue: Int,
    ): Boolean {
        return FabricUiInput.button(this, buttonValue, true) {
            requireClientThread()
            val position = positionOrNull(mouseX, mouseY) ?: return@button false
            val button = buttonOrNull(buttonValue) ?: return@button false
            val now = Util.getMillis()
            val doubleClick = buttonValue == lastClickButton && now - lastClickTime < DOUBLE_CLICK_THRESHOLD_MILLIS
            lastClickTime = now
            lastClickButton = buttonValue
            return@button inventory.withMousePress(buttonValue, currentModifierFlags(), doubleClick) {
                dispatch(PointerEvent.Press(position, button))
            }
        }
    }

    override fun mouseReleased(
        mouseX: Double,
        mouseY: Double,
        buttonValue: Int,
    ): Boolean {
        return FabricUiInput.button(this, buttonValue, false) {
            requireClientThread()
            val position = positionOrNull(mouseX, mouseY) ?: return@button false
            val button = buttonOrNull(buttonValue) ?: return@button false
            return@button inventory.withMouseRelease(buttonValue, currentModifierFlags()) {
                dispatch(PointerEvent.Release(position, button))
            }
        }
    }

    override fun mouseDragged(
        mouseX: Double,
        mouseY: Double,
        buttonValue: Int,
        deltaX: Double,
        deltaY: Double,
    ): Boolean {
        requireClientThread()
        if (FabricUiSessions.acceptsPointer(this).not()) return false
        val position = positionOrNull(mouseX, mouseY) ?: return false
        val button = buttonOrNull(buttonValue) ?: return false
        val displacement = mapMinecraftDrag(deltaX, deltaY) ?: return false
        return inventory.withMouseDrag(buttonValue, currentModifierFlags()) {
            dispatch(PointerEvent.Drag(position, button, displacement.first, displacement.second))
        }
    }

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        deltaX: Double,
        deltaY: Double,
    ): Boolean {
        return FabricUiInput.scroll(this) {
            requireClientThread()
            val position = positionOrNull(mouseX, mouseY) ?: return@scroll false
            val scroll = mapMinecraftScroll(deltaX, deltaY) ?: return@scroll false
            return@scroll dispatch(PointerEvent.Scroll(position, scroll.first, scroll.second))
        }
    }

    override fun keyPressed(
        keyValue: Int,
        scanCode: Int,
        modifierFlags: Int,
    ): Boolean {
        return FabricUiInput.key(this, InputConstants.getKey(keyValue, scanCode), true) {
            requireClientThread()
            characterInput.reset()
            if (inventory.handleKeyPressed(keyValue, scanCode, modifierFlags)) return@key true
            val mapped = mapMinecraftKeyPress(keyValue, scanCode, modifierFlags) ?: return@key false
            if (mapped.key == KeyCode.Escape) {
                return@key dispatchInherited { super.keyPressed(keyValue, scanCode, modifierFlags) }
            }
            return@key dispatchFocused(KeyboardInput(mapped)) { super.keyPressed(keyValue, scanCode, modifierFlags) }
        }
    }

    override fun keyReleased(
        keyValue: Int,
        scanCode: Int,
        modifierFlags: Int,
    ): Boolean {
        return FabricUiInput.key(this, InputConstants.getKey(keyValue, scanCode), false) {
            requireClientThread()
            characterInput.reset()
            val mapped = mapMinecraftKeyRelease(keyValue, scanCode, modifierFlags) ?: return@key false
            return@key dispatchFocused(KeyboardInput(mapped)) { super.keyReleased(keyValue, scanCode, modifierFlags) }
        }
    }

    override fun charTyped(
        character: Char,
        modifierFlags: Int,
    ): Boolean {
        requireClientThread()
        val mapped = characterInput.accept(character) ?: return false
        return dispatchFocused(TextInput(mapped)) {
            if (mapped.codePoint <= 0xFFFF) {
                super.charTyped(character, modifierFlags)
            } else {
                val highConsumed = super.charTyped(Character.highSurrogate(mapped.codePoint), modifierFlags)
                val lowConsumed = super.charTyped(character, modifierFlags)
                highConsumed || lowConsumed
            }
        }
    }

    override fun onClose() {
        requireClientThread()
        if (controls?.presentation == UiPresentation.Hud) {
            controls.setInteractionMode(UiInteractionMode.None)
            return
        }
        characterInput.reset()
        if (lifecycle.isActive()) {
            lifecycle.requestCloseThenNavigate()
            return
        }
        runHost { lifecycle.requestCloseThenNavigate() }
    }

    override fun close() {
        requireClientThread()
        characterInput.reset()
        if (closed) return
        if (lifecycle.isActive()) {
            lifecycle.requestClose()
            return
        }
        runHost { lifecycle.requestClose() }
    }

    private fun dispatch(event: PointerEvent): Boolean =
        try {
            characterInput.reset()
            runHost {
                host.dispatchPointer(event) == InputResult.Consumed
            }
        } catch (failure: Throwable) {
            terminalFailure(failure)
        }

    private fun dispatchFocused(
        input: FocusedInput,
        inherited: () -> Boolean,
    ): Boolean =
        try {
            runHost {
                val result =
                    when (input) {
                        is KeyboardInput -> host.dispatchKeyboard(input.event)
                        is TextInput -> host.dispatchTextInput(input.event)
                    }
                when (result) {
                    InputResult.Consumed -> true
                    InputResult.Ignored -> inherited()
                }
            }
        } catch (failure: Throwable) {
            terminalFailure(failure)
        }

    private fun dispatchInherited(inherited: () -> Boolean): Boolean =
        try {
            lifecycle.run(inherited)
        } catch (failure: Throwable) {
            terminalFailure(failure)
        }

    private fun terminalFailure(failure: Throwable): Boolean {
        try {
            close()
        } catch (cleanup: Throwable) {
            FabricMinecraftFailures.addSuppressed(failure, cleanup)
        }
        if (FabricRemoteScreens.fail(this, failure)) return true
        throw failure
    }

    /**
     * Captures the last complete presentation from immutable CPU images and exact native-generation snapshots only.
     *
     * No GPU readback is performed and no live native token is resolved by Headless.
     *
     * @return detached portable commands retaining the original logical destinations, clips, and order.
     * @throws IllegalStateException when called off the client thread, before a frame, after removal, or without a matching native snapshot.
     */
    public fun captureCanvasFrame(): List<DrawCommand> {
        requireClientThread()
        return canvasPresentation.capture()
    }

    /**
     * Cancels captured pointer, hover, and focus state after a native window/input reset.
     *
     * The native bridge invokes this synchronously on the client thread; callbacks may fail and trigger terminal cleanup.
     *
     * @throws Throwable when input cancellation or cleanup fails, preserving the primary failure.
     */
    @InternalStrataRuntimeApi
    @JvmSynthetic
    override fun resetInputFromNative() {
        requireClientThread()
        if (closed || attached.not()) return
        characterInput.reset()
        try {
            runHost { host.resetInputState() }
        } catch (failure: Throwable) {
            terminalFailure(failure)
        }
    }

    /**
     * Prepares the existing retained host for HUD drawing without replacing it.
     */
    @JvmSynthetic
    internal fun prepareHud() {
        val window = minecraftClient.window
        if (width != window.guiScaledWidth || height != window.guiScaledHeight) FabricMinecraftScreenAccess.initialize(minecraftClient, this)
        if (attached.not()) added()
    }

    /**
     * Pauses HUD drawing/input while preserving retained state.
     */
    @JvmSynthetic
    internal fun hideHud() {
        if (attached) removed()
    }

    /**
     * Captures the current native screen when a HUD becomes a foreground screen.
     */
    @JvmSynthetic
    internal fun navigationParent(screen: Screen?) {
        parent = screen
    }

    /**
     * Whether retained text editing currently blocks every game action.
     */
    @JvmSynthetic
    internal fun isEditingText(): Boolean = closed.not() && attached && host.textInputFocus != null

    /**
     * Whether a native inventory binding belongs to this retained host.
     */
    @JvmSynthetic
    internal fun usesNativeSlots(): Boolean = inventory.hasBindings()

    /**
     * Native container first used by this retained host's Slots, released on terminal close.
     */
    @JvmSynthetic
    internal fun boundContainer(): Any? = inventory.boundContainer()

    /**
     * Clears native and retained input at a presentation/permission boundary.
     */
    @JvmSynthetic
    internal fun releaseUiInput() {
        if (attached && closed.not()) resetInputFromNative()
    }

    private fun <T> runHost(operation: () -> T): T {
        val controller = controls
        return (if (controller == null) lifecycle.run { operation() } else controller.transaction { lifecycle.run { operation() } }).also { FabricUiInput.synchronize(this) }
    }

    private fun attachHost() {
        if (attached) return
        FabricMinecraftCanvasHooks.requireRunning()
        characterInput.reset()
        host.attach()
        attached = true
        presentation.resetPointer()
    }

    private fun detachHost() {
        characterInput.reset()
        canvasPresentation.release()
        if (attached) {
            host.detach()
            attached = false
            presentation.release()
        }
    }

    private fun closeHost() {
        characterInput.reset()
        canvasPresentation.release()
        if (closed) return
        closed = true
        attached = false
        var failure: Throwable? = runCatching { controls?.close() }.exceptionOrNull()
        try {
            host.close()
        } catch (caught: Throwable) {
            failure = caught
        }
        try {
            presentation.release()
        } catch (caught: Throwable) {
            if (failure == null) {
                failure = caught
            } else {
                FabricMinecraftFailures.addSuppressed(failure, caught)
            }
        }
        failure?.let { throw it }
    }

    private fun currentModifierFlags(): Int {
        var flags = 0
        if (hasShiftDown()) flags = flags or GLFW.GLFW_MOD_SHIFT
        if (hasControlDown()) flags = flags or GLFW.GLFW_MOD_CONTROL
        if (hasAltDown()) flags = flags or GLFW.GLFW_MOD_ALT
        return flags
    }

    private fun positionOrNull(
        mouseX: Double,
        mouseY: Double,
    ): IntOffset? = mapMinecraftPosition(mouseX, mouseY)

    private fun buttonOrNull(button: Int): PointerButton? = mapMinecraftButton(button)

    private fun requireClientThread() {
        check(minecraftClient.isSameThread) { "Fabric Minecraft screens are confined to the client thread." }
    }

    /**
     * Owns the private implementation constructor used by the public factory.
     * The entry point is Kotlin-internal, JVM-synthetic, and confined to the Minecraft client thread.
     */
    internal companion object {
        private const val DOUBLE_CLICK_THRESHOLD_MILLIS = 250L

        /**
         * Creates one primitive-input screen after host construction has transferred its definition.
         *
         * @param host transferred common host.
         * @param inventory borrowed platform bridge owned by [host].
         * @param parent screen restored after terminal close.
         * @param minecraft active client used for parent navigation.
         * @return private screen implementation.
         * @throws Throwable when construction fails; the caller retains cleanup ownership of [host].
         */
        @JvmSynthetic
        internal fun create(
            host: MinecraftUiHost,
            inventory: FabricMinecraftInventoryBridge,
            parent: Screen?,
            minecraft: Minecraft,
            controls: RuntimeUiController?,
        ): FabricMinecraftScreen = FabricMinecraftScreen(host, inventory, parent, minecraft, controls)
    }

    private sealed interface FocusedInput

    private data class KeyboardInput(
        val event: KeyboardEvent,
    ) : FocusedInput

    private data class TextInput(
        val event: TextInputEvent,
    ) : FocusedInput
}

/**
 * Creates a Fabric client screen by transferring [definition] into one independent common host.
 *
 * Construction and every lifecycle and input callback belong on the Minecraft client thread.
 * The returned screen retains but does not own [parent]; permanent abandonment requires explicit client-thread close.
 *
 * @param definition one-shot common screen definition.
 * @param profile immutable asset profile.
 * @param parent screen restored after terminal close.
 * @return client-thread screen with terminal close ownership.
 * @throws IllegalStateException when called away from the client thread or when [definition] cannot be transferred.
 * @throws Throwable when host or screen construction fails; transferred ownership is released before failure escapes.
 */
@OptIn(InternalStrataRuntimeApi::class)
@Suppress("TooGenericExceptionCaught")
public fun createMinecraftScreen(
    definition: UiDefinition,
    profile: MinecraftUiProfile,
    parent: Screen? = currentMinecraftScreen(),
    controls: RuntimeUiController? = null,
    eventSession: UiSession? = controls,
): FabricMinecraftScreen {
    val minecraft = Minecraft.getInstance()
    check(minecraft.isSameThread) { "Fabric Minecraft screens must be created on the client thread." }
    val inventory = FabricMinecraftInventoryBridge.create(minecraft)
    val host =
        try {
            createMinecraftUiHost(definition, profile, inventory, LwjglMinecraftFontBackendFactory, eventSession)
        } catch (failure: Throwable) {
            try {
                inventory.close()
            } catch (cleanup: Throwable) {
                FabricMinecraftFailures.addSuppressed(failure, cleanup)
            }
            throw failure
        }
    return try {
        FabricMinecraftScreen.create(host, inventory, parent, minecraft, controls)
    } catch (failure: Throwable) {
        try {
            host.close()
        } catch (cleanup: Throwable) {
            FabricMinecraftFailures.addSuppressed(failure, cleanup)
        }
        throw failure
    }
}

private fun currentMinecraftScreen(): Screen? {
    val minecraft = Minecraft.getInstance()
    check(minecraft.isSameThread) { "Fabric Minecraft screens must be created on the client thread." }
    return FabricMinecraftScreenAccess.currentScreen(minecraft)
}

/**
 * Compatibility factory sharing the common UI host path.
 */
@OptIn(InternalStrataRuntimeApi::class)
public fun createMinecraftScreen(
    definition: ScreenDefinition,
    profile: MinecraftUiProfile,
    parent: Screen? = currentMinecraftScreen(),
): FabricMinecraftScreen = createMinecraftScreen(definition.asUiDefinition(), profile, parent)

/**
 * Releases forwarded native state, including any version-owned toggle restoration on a later screen close.
 */
@JvmSynthetic
internal fun releaseMinecraftUiBinding(mapping: KeyMapping) {
    val access = mapping as FabricUiKeyMappingAccess
    access.strataDown(false)
    access.strataClicks(0)
}
