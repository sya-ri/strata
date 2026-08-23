@file:JvmName("FabricMinecraftScreens")
@file:Suppress("MatchingDeclarationName", "ktlint:standard:filename")

package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.minecraft.MinecraftUiHost
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.Util
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
    private val parent: Screen?,
    private val minecraftClient: Minecraft,
) : Screen(mapMinecraftText(host.title)),
    AutoCloseable {
    private var closed = false
    private var attached = false
    private var lastClickTime = 0L
    private var lastClickButton = Int.MIN_VALUE
    private val presentation = FabricMinecraftFramePresenter(minecraftClient)
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
        check(closed.not()) { "A closed Fabric Minecraft screen cannot be added again." }
        if (lifecycle.isActive()) {
            super.added()
            lifecycle.requestAttach()
            return
        }
        try {
            lifecycle.run {
                super.added()
                lifecycle.requestAttach()
            }
        } catch (failure: Throwable) {
            terminalFailure(failure)
        }
    }

    override fun removed() {
        requireClientThread()
        if (lifecycle.isActive()) {
            super.removed()
            lifecycle.requestDetach()
            return
        }
        try {
            lifecycle.run {
                super.removed()
                lifecycle.requestDetach()
            }
        } catch (failure: Throwable) {
            terminalFailure(failure)
        }
    }

    override fun render(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        requireClientThread()
        try {
            presentation.recordRenderExtraction()
            inventory.withRefreshBatch {
                lifecycle.run {
                    val viewport = IntSize(width, height)
                    val frameTime = FrameTime(System.nanoTime())
                    presentation.recordHostFrame()
                    var frame = host.frame(viewport, frameTime)
                    if (lifecycle.hasPendingExit()) return@run
                    if (width == 0 || height == 0) {
                        presentation.release()
                        return@run
                    }
                    val currentPointer = IntOffset(mouseX, mouseY)
                    val pointerNeedsDispatch = presentation.needsPointerDispatch(currentPointer, frame.drawCommands)
                    if (pointerNeedsDispatch) {
                        presentation.recordExtractedPointerDispatch(currentPointer, frame.drawCommands)
                        inventory.withPointerMove { host.dispatchPointer(PointerEvent.Move(currentPointer)) == InputResult.Consumed }
                        if (lifecycle.hasPendingExit()) return@run
                        presentation.recordHostFrame()
                        frame = host.frame(viewport, frameTime)
                        if (lifecycle.hasPendingExit()) return@run
                    }
                    presentation.present(graphics, frame.drawCommands, frame.size) { target, command ->
                        inventory.renderItem(
                            target,
                            minecraftClient.font,
                            command.command,
                            command.bounds.left,
                            command.bounds.top,
                        )
                    }
                    inventory.renderCarried(graphics, minecraftClient.font, mouseX, mouseY)
                }
            }
        } catch (failure: Throwable) {
            terminalFailure(failure)
        }
    }

    override fun isPauseScreen(): Boolean = pausePolicy

    override fun mouseMoved(
        mouseX: Double,
        mouseY: Double,
    ) {
        requireClientThread()
        val position = positionOrNull(mouseX, mouseY) ?: return
        inventory.withPointerMove { dispatch(PointerEvent.Move(position)) }
        presentation.recordPointerInput(position)
    }

    override fun mouseClicked(
        mouseX: Double,
        mouseY: Double,
        buttonValue: Int,
    ): Boolean {
        requireClientThread()
        val position = positionOrNull(mouseX, mouseY) ?: return false
        val button = buttonOrNull(buttonValue) ?: return false
        val now = Util.getMillis()
        val doubleClick = buttonValue == lastClickButton && now - lastClickTime < DOUBLE_CLICK_THRESHOLD_MILLIS
        lastClickTime = now
        lastClickButton = buttonValue
        return inventory.withMousePress(buttonValue, currentModifierFlags(), doubleClick) {
            dispatch(PointerEvent.Press(position, button))
        }
    }

    override fun mouseReleased(
        mouseX: Double,
        mouseY: Double,
        buttonValue: Int,
    ): Boolean {
        requireClientThread()
        val position = positionOrNull(mouseX, mouseY) ?: return false
        val button = buttonOrNull(buttonValue) ?: return false
        return inventory.withMouseRelease(buttonValue, currentModifierFlags()) {
            dispatch(PointerEvent.Release(position, button))
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
        requireClientThread()
        val position = positionOrNull(mouseX, mouseY) ?: return false
        val scroll = mapMinecraftScroll(deltaX, deltaY) ?: return false
        return dispatch(PointerEvent.Scroll(position, scroll.first, scroll.second))
    }

    override fun keyPressed(
        keyValue: Int,
        scanCode: Int,
        modifierFlags: Int,
    ): Boolean {
        requireClientThread()
        if (inventory.handleKeyPressed(keyValue, scanCode, modifierFlags)) return true
        val mapped = mapMinecraftKeyPress(keyValue, scanCode, modifierFlags) ?: return false
        if (mapped.key == KeyCode.Escape) {
            return dispatchInherited { super.keyPressed(keyValue, scanCode, modifierFlags) }
        }
        return dispatchFocused(KeyboardInput(mapped)) { super.keyPressed(keyValue, scanCode, modifierFlags) }
    }

    override fun keyReleased(
        keyValue: Int,
        scanCode: Int,
        modifierFlags: Int,
    ): Boolean {
        requireClientThread()
        val mapped = mapMinecraftKeyRelease(keyValue, scanCode, modifierFlags) ?: return false
        return dispatchFocused(KeyboardInput(mapped)) { super.keyReleased(keyValue, scanCode, modifierFlags) }
    }

    override fun charTyped(
        character: Char,
        modifierFlags: Int,
    ): Boolean {
        requireClientThread()
        val mapped = mapMinecraftCharacter(character.code) ?: return false
        return dispatchFocused(TextInput(mapped)) { super.charTyped(character, modifierFlags) }
    }

    override fun onClose() {
        requireClientThread()
        if (lifecycle.isActive()) {
            lifecycle.requestCloseThenNavigate()
            return
        }
        lifecycle.run { lifecycle.requestCloseThenNavigate() }
    }

    override fun close() {
        requireClientThread()
        if (closed) return
        if (lifecycle.isActive()) {
            lifecycle.requestClose()
            return
        }
        lifecycle.run { lifecycle.requestClose() }
    }

    private fun dispatch(event: PointerEvent): Boolean =
        try {
            lifecycle.run {
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
            lifecycle.run {
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

    private fun terminalFailure(failure: Throwable): Nothing {
        try {
            close()
        } catch (cleanup: Throwable) {
            FabricMinecraftFailures.addSuppressed(failure, cleanup)
        }
        throw failure
    }

    private fun attachHost() {
        host.attach()
        attached = true
        presentation.resetPointer()
    }

    private fun detachHost() {
        if (attached) {
            host.detach()
            attached = false
            presentation.release()
        }
    }

    private fun closeHost() {
        if (closed) return
        closed = true
        attached = false
        var failure: Throwable? = null
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
        ): FabricMinecraftScreen = FabricMinecraftScreen(host, inventory, parent, minecraft)
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
    definition: ScreenDefinition,
    profile: MinecraftUiProfile,
    parent: Screen? = currentMinecraftScreen(),
): FabricMinecraftScreen {
    val minecraft = Minecraft.getInstance()
    check(minecraft.isSameThread) { "Fabric Minecraft screens must be created on the client thread." }
    val inventory = FabricMinecraftInventoryBridge.create(minecraft)
    val host =
        try {
            createMinecraftUiHost(definition, profile, inventory)
        } catch (failure: Throwable) {
            try {
                inventory.close()
            } catch (cleanup: Throwable) {
                FabricMinecraftFailures.addSuppressed(failure, cleanup)
            }
            throw failure
        }
    return try {
        FabricMinecraftScreen.create(host, inventory, parent, minecraft)
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
