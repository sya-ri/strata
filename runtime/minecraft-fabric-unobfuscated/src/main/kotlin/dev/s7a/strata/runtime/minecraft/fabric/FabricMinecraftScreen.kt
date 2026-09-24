@file:Suppress("DEPRECATION") // Compatibility overloads and regression coverage retain the deprecated screen entry points.

@file:JvmName("FabricMinecraftScreens")

package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.InputConstants
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.diagnostics.UiRenderMonitor
import dev.s7a.strata.runtime.minecraft.MinecraftPlatformCommands
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
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.ToggleKeyMapping
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.CharacterEvent as MinecraftCharacterEvent
import net.minecraft.client.input.KeyEvent as MinecraftKeyEvent
import net.minecraft.client.input.PreeditEvent as MinecraftPreeditEvent

// Why: the screen owns one cohesive set of native lifecycle/input hooks and must preserve arbitrary user failures exactly.

/**
 * Fabric client screen backed by one common Minecraft UI host.
 *
 * The host is created by transferring the one-shot [ScreenDefinition] and is confined to the Minecraft client thread.
 * Added and removed screen callbacks attach and detach the retained tree; removal is transient and never closes the screen.
 * Terminal close releases the host and screen-owned texture references; the independent device retires queued layers after their GUI-consumption fences. A caller that permanently abandons a removed or never-presented instance must close it on the client thread.
 * The optional parent is retained for navigation but is never owned or closed.
 * Rendering rasterizes portable command runs through the headless adapter and submits synchronized ItemStack commands through Minecraft's native extractor at their exact display-list positions.
 * Mouse coordinates are floored after finite and integer-range checks. Horizontal scroll is forwarded unchanged, while vertical scroll is negated to match the common increasing-y contract.
 *
 * @see createMinecraftScreen
 */
@OptIn(InternalStrataRuntimeApi::class)
@Suppress("TooManyFunctions", "TooGenericExceptionCaught", "LargeClass") // Native frame, resource, and input callbacks share this wrapper's lifetime.
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

    private val canvasPresentation = FabricMinecraftCanvasPresentation()
    private var attached = false
    private val portableFrames = FabricMinecraftPortableFrames()
    private val sampledImages = FabricMinecraftSampledImageCache()
    private var preparedCommands: List<DrawCommand>? = null
    private var preparedViewport: IntSize? = null
    private var preparedScale: Int? = null
    private var preparedLayers: List<FabricMinecraftFrameLayer> = emptyList()
    private var pointerPosition: IntOffset? = null
    private var pointerFrameCommands: List<DrawCommand>? = null
    private var renderExtractionCount: Long = 0L
    private var hostFrameCount: Long = 0L
    private var extractedPointerDispatchCount: Long = 0L
    private var framePreparationCount: Long = 0L
    private var portableRasterizationCount: Long = 0L
    private var textureUploadCount: Long = 0L
    private var sampledImageDirectHitCount: Long = 0L
    private var sampledImageDirectMissCount: Long = 0L
    private var sampledImageUploadCount: Long = 0L
    private var sampledImageDrawCount: Long = 0L
    private var sampledImageEvictionCount: Long = 0L
    private var sampledImageIneligibleFallbackCount: Long = 0L
    private var sampledImageCapacityFallbackCount: Long = 0L
    private var sampledImageRetainedEntryCount: Long = 0L
    private var sampledImageRetainedByteCount: Long = 0L
    private val pausePolicy = host.pausesGame
    private val textInputFocus = FabricMinecraftTextInputFocus { focused -> minecraftClient.onTextInputFocusChange(this, focused) }
    private val lifecycle =
        FabricScreenLifecycleTransaction.create(
            { attachHost() },
            { detachHost() },
            { closeHost() },
            { FabricMinecraftScreenAccess.setScreen(minecraftClient, parent) },
        )

    /**
     * Attaches the common retained tree when Minecraft adds this screen.
     *
     * @throws Throwable when the common host rejects attachment or content evaluation fails; the host preserves the exact primary failure.
     * @throws IllegalStateException when invoked away from the Minecraft client thread or after terminal close.
     */
    override fun added() {
        requireClientThread()
        FabricMinecraftCanvasHooks.requireRunning()
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
            synchronizeTextInputFocus()
        } catch (failure: Throwable) {
            terminalFailure(failure)
        }
    }

    /**
     * Detaches the common retained tree while retaining it for a later add callback.
     * Detached screens release their prepared display list and texture references so a parent screen cannot retain presentation-only data while hidden.
     * The independent device preserves already queued native layers through their actual consumption fences.
     *
     * @throws Throwable when the common host rejects detachment or cleanup fails.
     * @throws IllegalStateException when invoked away from the Minecraft client thread.
     */
    override fun removed() {
        requireClientThread()
        if (lifecycle.isActive()) {
            textInputFocus.clear()
            super.removed()
            lifecycle.requestDetach()
            return
        }
        try {
            textInputFocus.clear()
            runHost {
                super.removed()
                lifecycle.requestDetach()
            }
        } catch (failure: Throwable) {
            terminalFailure(failure)
        }
    }

    /**
     * Suppresses Minecraft's default panorama, blur, and menu-background extraction.
     *
     * The complete common frame is submitted later by [extractRenderState], so this callback intentionally emits no visual state.
     *
     * @param graphics ignored native extraction target.
     * @param mouseX ignored current mouse x coordinate.
     * @param mouseY ignored current mouse y coordinate.
     * @param partialTick ignored current frame fraction.
     * @throws IllegalStateException when invoked away from the Minecraft client thread.
     */
    override fun extractBackground(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        requireClientThread()
    }

    /**
     * Extracts one render state from the common frame and presents it at the logical GUI origin.
     *
     * Geometry is committed first, and a native pointer move is delivered only when its position or the committed display list changed.
     * A second frame captures resulting hover state only after that dispatch.
     * A zero-axis viewport still commits geometry and releases any prior texture without submitting a native blit.
     *
     * @param graphics the native extraction target.
     * @param mouseX the current native mouse x coordinate.
     * @param mouseY the current native mouse y coordinate.
     * @param partialTick the current native frame fraction.
     * @throws Throwable when common frame work, headless rasterization, texture upload, or terminal cleanup fails.
     * @throws IllegalStateException when invoked away from the Minecraft client thread.
     */
    override fun extractRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        requireClientThread()
        if (FabricUiSessions.prepareRender(this).not()) return
        var guiFailure: Throwable? = null
        try {
            renderExtractionCount += 1L
            val frameTime = FrameTime(System.nanoTime())
            val frame =
                inventory.withRefreshBatch {
                    runHost {
                        val viewport = IntSize(width, height)
                        hostFrameCount += 1L
                        var frame = host.frame(viewport, frameTime)
                        if (lifecycle.hasPendingExit()) return@runHost null
                        if (width == 0 || height == 0) {
                            releaseTextures()
                            return@runHost null
                        }
                        val currentPointer = IntOffset(mouseX, mouseY)
                        val pointerNeedsDispatch = needsPointerDispatch(currentPointer, frame.drawCommands)
                        if (pointerNeedsDispatch && FabricUiSessions.acceptsPointer(this)) {
                            extractedPointerDispatchCount += 1L
                            inventory.withPointerMove { host.dispatchPointer(PointerEvent.Move(currentPointer)) == InputResult.Consumed }
                            pointerPosition = currentPointer
                            pointerFrameCommands = frame.drawCommands
                            if (lifecycle.hasPendingExit()) return@runHost null
                            hostFrameCount += 1L
                            frame = host.frame(viewport, frameTime)
                            if (lifecycle.hasPendingExit()) return@runHost null
                        }
                        frame
                    }
                }
            synchronizeTextInputFocus()
            if (frame == null) return
            if (attached.not()) return
            canvasPresentation.present(
                frame.drawCommands,
                frameTime,
                minecraftClient.window.guiScale,
                inventory,
                FabricNativeCanvasDriver::draw,
            ) { commands, dispatch ->
                extractFrame(graphics, commands, frame.size, dispatch)
            }
            if (attached.not()) return
            renderCarried(graphics, mouseX, mouseY)
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

    /**
     * Reports whether this screen pauses the game according to its transferred definition.
     *
     * @return the common pause policy.
     */
    override fun isPauseScreen(): Boolean = pausePolicy && controls?.presentation != UiPresentation.Hud

    /**
     * Delivers a finite mouse movement to the common host.
     *
     * @param mouseX native logical x coordinate.
     * @param mouseY native logical y coordinate.
     * @throws Throwable when common input, its callback, or terminal cleanup fails.
     * @throws IllegalStateException when invoked away from the Minecraft client thread.
     */
    override fun mouseMoved(
        mouseX: Double,
        mouseY: Double,
    ) {
        requireClientThread()
        if (FabricUiSessions.acceptsPointer(this).not()) return
        val position = positionOrNull(mouseX, mouseY) ?: return
        inventory.withPointerMove { dispatch(PointerEvent.Move(position)) }
        pointerPosition = position
        pointerFrameCommands = preparedCommands
    }

    /**
     * Delivers a native mouse press to the common host.
     *
     * @param event native mouse-button event.
     * @param doubleClick native double-click marker, which is not part of the common pointer protocol.
     * @return true only when the common host consumes the press.
     * @throws Throwable when common input, its callback, or terminal cleanup fails.
     * @throws IllegalStateException when invoked away from the Minecraft client thread.
     */
    override fun mouseClicked(
        event: MouseButtonEvent,
        doubleClick: Boolean,
    ): Boolean {
        return FabricUiInput.button(this, event.button(), true) {
            requireClientThread()
            val position = positionOrNull(event.x(), event.y()) ?: return@button false
            val button = buttonOrNull(event.button()) ?: return@button false
            return@button inventory.withMousePress(event, doubleClick) { dispatch(PointerEvent.Press(position, button)) }
        }
    }

    /**
     * Delivers a native mouse release to the common host.
     *
     * @param event native mouse-button event.
     * @return true only when the common host consumes the release.
     * @throws Throwable when common input, its callback, or terminal cleanup fails.
     * @throws IllegalStateException when invoked away from the Minecraft client thread.
     */
    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        return FabricUiInput.button(this, event.button(), false) {
            requireClientThread()
            val position = positionOrNull(event.x(), event.y()) ?: return@button false
            val button = buttonOrNull(event.button()) ?: return@button false
            return@button inventory.withMouseRelease(event) { dispatch(PointerEvent.Release(position, button)) }
        }
    }

    /**
     * Delivers native movement while a mouse button is held as one typed common drag event.
     *
     * @param event native mouse-button event carrying the current logical position.
     * @param deltaX native drag displacement along x.
     * @param deltaY native drag displacement along y.
     * @return true only when the common host consumes the drag.
     * @throws Throwable when common input, its callback, or terminal cleanup fails.
     * @throws IllegalStateException when invoked away from the Minecraft client thread.
     */
    override fun mouseDragged(
        event: MouseButtonEvent,
        deltaX: Double,
        deltaY: Double,
    ): Boolean {
        requireClientThread()
        if (FabricUiSessions.acceptsPointer(this).not()) return false
        val position = positionOrNull(event.x(), event.y()) ?: return false
        val button = buttonOrNull(event.button()) ?: return false
        val displacement = mapMinecraftDrag(deltaX, deltaY) ?: return false
        return inventory.withMouseDrag(event) { dispatch(PointerEvent.Drag(position, button, displacement.first, displacement.second)) }
    }

    /**
     * Delivers native scroll displacement after mapping vertical sign into the common coordinate contract.
     *
     * @param mouseX native logical x coordinate.
     * @param mouseY native logical y coordinate.
     * @param deltaX native horizontal displacement.
     * @param deltaY native vertical displacement, negated at the common boundary.
     * @return true only when the common host consumes the scroll.
     * @throws Throwable when common input, its callback, or terminal cleanup fails.
     * @throws IllegalStateException when invoked away from the Minecraft client thread.
     */
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

    /**
     * Preserves Minecraft's screen-level Escape handling before delivering other key presses to the focused retained component and then inherited focus-navigation behavior.
     *
     * @param event native immutable key record.
     * @return true when common focused behavior or inherited screen behavior consumes the event.
     * @throws Throwable when focused behavior, inherited navigation, or terminal cleanup fails.
     * @throws IllegalStateException when invoked away from the Minecraft client thread.
     */
    override fun keyPressed(event: MinecraftKeyEvent): Boolean {
        return FabricUiInput.key(this, InputConstants.getKey(event), true) {
            requireClientThread()
            if (inventory.handleKeyPressed(event)) return@key true
            val mapped = mapMinecraftKeyPress(event) ?: return@key false
            if (mapped.key == KeyCode.Escape) {
                return@key dispatchInherited { super.keyPressed(event) }
            }
            return@key dispatchFocused(KeyboardInput(mapped)) { super.keyPressed(event) }
        }
    }

    /**
     * Delivers a native key release to the focused retained component before inherited screen behavior.
     *
     * @param event native immutable key record.
     * @return true when common focused behavior or inherited screen behavior consumes the event.
     * @throws Throwable when focused behavior or terminal cleanup fails.
     * @throws IllegalStateException when invoked away from the Minecraft client thread.
     */
    override fun keyReleased(event: MinecraftKeyEvent): Boolean {
        return FabricUiInput.key(this, InputConstants.getKey(event), false) {
            requireClientThread()
            val mapped = mapMinecraftKeyRelease(event) ?: return@key false
            return@key dispatchFocused(KeyboardInput(mapped)) { super.keyReleased(event) }
        }
    }

    /**
     * Delivers one committed Unicode character to the focused retained component before inherited screen behavior.
     *
     * @param event native immutable character record.
     * @return true when common focused behavior or inherited screen behavior consumes the event.
     * @throws Throwable when focused behavior or terminal cleanup fails.
     * @throws IllegalStateException when invoked away from the Minecraft client thread.
     */
    override fun charTyped(event: MinecraftCharacterEvent): Boolean {
        requireClientThread()
        val mapped = mapMinecraftCharacter(event) ?: return false
        return dispatchFocused(TextInput(mapped)) { super.charTyped(event) }
    }

    /**
     * Delivers one input-method preedit snapshot to the focused retained component before inherited screen behavior.
     *
     * @param event native immutable preedit record, or null to clear the active composition.
     * @return true when common focused behavior or inherited screen behavior consumes the event.
     * @throws Throwable when focused behavior or terminal cleanup fails.
     * @throws IllegalStateException when invoked away from the Minecraft client thread.
     */
    override fun preeditUpdated(event: MinecraftPreeditEvent?): Boolean {
        requireClientThread()
        if (closed || attached.not() || textInputFocus.isActive.not()) return false
        val mapped = mapMinecraftPreedit(event) ?: return false
        return dispatchFocused(TextInput(mapped)) { super.preeditUpdated(event) }
    }

    /**
     * Closes the host and always navigates to the transferred parent screen.
     *
     * Cleanup and parent navigation are each attempted at most once. If both fail, the cleanup failure remains primary and the navigation failure is suppressed.
     * A close requested synchronously from a common pointer callback is completed when that input transaction exits, before another common operation can begin.
     *
     * @throws Throwable when host cleanup or navigation fails.
     * @throws IllegalStateException when invoked away from the Minecraft client thread.
     */
    override fun onClose() {
        requireClientThread()
        if (controls?.presentation == UiPresentation.Hud) {
            controls.setInteractionMode(UiInteractionMode.None)
            return
        }
        if (lifecycle.isActive()) {
            lifecycle.requestCloseThenNavigate()
            return
        }
        runHost { lifecycle.requestCloseThenNavigate() }
    }

    /**
     * Releases host and texture ownership exactly once.
     * A close requested synchronously from a common frame or input callback is deferred until that operation exits on the same client thread.
     * This method does not navigate; a displayed screen must be replaced by Minecraft or closed through [onClose], while a later [onClose] may still perform its one parent-navigation attempt.
     *
     * @throws Throwable when host or texture cleanup fails; the first cleanup failure remains primary.
     * @throws IllegalStateException when invoked away from the Minecraft client thread.
     */
    override fun close() {
        requireClientThread()
        if (closed) return
        if (lifecycle.isActive()) {
            lifecycle.requestClose()
            return
        }
        runHost { lifecycle.requestClose() }
    }

    private fun needsPointerDispatch(
        position: IntOffset,
        commands: List<DrawCommand>,
    ): Boolean = position != pointerPosition || commands !== pointerFrameCommands

    private fun renderCarried(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
    ) {
        if (FabricUiSessions.acceptsPointer(this)) inventory.renderCarried(graphics, minecraftClient.font, mouseX, mouseY)
    }

    private fun dispatch(event: PointerEvent): Boolean =
        try {
            runHost {
                host.dispatchPointer(event) == InputResult.Consumed
            }.also { synchronizeTextInputFocus() }
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
            }.also { synchronizeTextInputFocus() }
        } catch (failure: Throwable) {
            terminalFailure(failure)
        }

    private fun dispatchInherited(inherited: () -> Boolean): Boolean =
        try {
            lifecycle.run(inherited).also { synchronizeTextInputFocus() }
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
        try {
            runHost { host.resetInputState() }
            synchronizeTextInputFocus()
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
        host.attach()
        attached = true
        pointerPosition = null
        pointerFrameCommands = null
    }

    private fun detachHost() {
        if (attached) {
            textInputFocus.clear()
            host.detach()
            attached = false
            releaseTextures()
        }
    }

    private fun closeHost() {
        if (closed) return
        closed = true
        attached = false
        var failure: Throwable? = runCatching { controls?.close() }.exceptionOrNull()
        try {
            textInputFocus.clear()
        } catch (caught: Throwable) {
            if (failure == null) failure = caught else FabricMinecraftFailures.addSuppressed(failure, caught)
        }
        try {
            host.close()
        } catch (caught: Throwable) {
            if (failure == null) {
                failure = caught
            } else {
                FabricMinecraftFailures.addSuppressed(failure, caught)
            }
        }
        try {
            releaseTextures()
        } catch (caught: Throwable) {
            if (failure == null) {
                failure = caught
            } else {
                FabricMinecraftFailures.addSuppressed(failure, caught)
            }
        }
        failure?.let { throw it }
    }

    private fun synchronizeTextInputFocus() {
        val displayed = FabricMinecraftScreenAccess.currentScreen(minecraftClient) === this
        val focus = if (closed.not() && attached && displayed) host.textInputFocus else null
        textInputFocus.synchronize(focus)
    }

    @Suppress("LongMethod") // The nested direct-image and portable borrows must remain visibly pinned through one ordered native submission.
    private fun extractFrame(
        graphics: GuiGraphicsExtractor,
        commands: List<DrawCommand>,
        viewport: IntSize,
        platformCommands: MinecraftPlatformCommands<GuiGraphicsExtractor>,
    ) {
        val scale = minecraftClient.getWindow().getGuiScale()
        require(0 < scale) { "Minecraft GUI scale must be positive." }
        val reusePreparedFrame = commands === preparedCommands && viewport == preparedViewport && scale == preparedScale
        val generation = portableFrames.releaseGeneration
        val layers =
            if (reusePreparedFrame) {
                preparedLayers
            } else {
                framePreparationCount += 1L
                partitionFabricMinecraftFrame(commands, viewport)
            }
        val sampled = layers.filterIsInstance<FabricMinecraftFrameLayer.Sampled>().map { it.command.image }
        try {
            sampledImages.present(
                sampled,
                { sampledImageDirectHitCount += 1L },
                { sampledImageDirectMissCount += 1L },
                { sampledImageUploadCount += 1L },
                { sampledImageEvictionCount += 1L },
            ) { textureFor, sampledQueued ->
                val resolvedTextures = mutableMapOf<FabricMinecraftFrameLayer.Sampled, FabricMinecraftPortableTexture>()
                val resolved =
                    layers.map { layer ->
                        if (layer is FabricMinecraftFrameLayer.Sampled) {
                            val texture = textureFor(layer.command.image)
                            if (texture == null) {
                                if (sampledImages.supports(layer.command.image)) {
                                    sampledImageCapacityFallbackCount += 1L
                                } else {
                                    sampledImageIneligibleFallbackCount += 1L
                                }
                                portableFabricSampledFallback(layer)
                            } else {
                                resolvedTextures[layer] = texture
                                layer
                            }
                        } else {
                            layer
                        }
                    }
                resolved.filterIsInstance<FabricMinecraftFrameLayer.Portable>().forEach { layer ->
                    sampledImageIneligibleFallbackCount = Math.addExact(sampledImageIneligibleFallbackCount, layer.ineligibleSampledImages.toLong())
                }
                val images = resolved.filterIsInstance<FabricMinecraftFrameLayer.Portable>().map { FabricMinecraftPortableImage(it.commands, it.bounds.size, scale) }
                portableFrames.present(
                    images,
                    { portableRasterizationCount += 1L },
                    { textureUploadCount += 1L },
                ) { textures, portableQueued ->
                    var textureIndex = 0
                    submitFabricMinecraftFrameLayers(resolved, graphics::nextStratum) { layer ->
                        when (layer) {
                            is FabricMinecraftFrameLayer.Portable -> {
                                val texture = textures[textureIndex++].texture
                                portableQueued()
                                submitFabricMinecraftGuiCorners(layer.bounds) { x0, y0, x1, y1 ->
                                    graphics.blit(
                                        texture.getTextureView(),
                                        texture.getSampler(),
                                        x0,
                                        y0,
                                        x1,
                                        y1,
                                        0f,
                                        1f,
                                        0f,
                                        1f,
                                    )
                                }
                            }

                            is FabricMinecraftFrameLayer.Sampled -> {
                                val texture = resolvedTextures.getValue(layer)
                                sampledQueued(layer.command.image)
                                sampledImageDrawCount += 1L
                                extractSampledLayer(graphics, layer, texture)
                            }

                            is FabricMinecraftFrameLayer.Platform -> {
                                extractPlatformLayer(graphics, layer, viewport, platformCommands)
                            }
                        }
                    }
                }
            }
        } finally {
            sampledImageRetainedEntryCount = sampledImages.retainedEntryCount().toLong()
            sampledImageRetainedByteCount = sampledImages.retainedByteCount()
        }
        if (reusePreparedFrame.not() && portableFrames.releaseGeneration == generation) {
            preparedCommands = commands
            preparedViewport = viewport
            preparedScale = scale
            preparedLayers = layers
        }
    }

    private fun extractSampledLayer(
        graphics: GuiGraphicsExtractor,
        layer: FabricMinecraftFrameLayer.Sampled,
        texture: FabricMinecraftPortableTexture,
    ) {
        val clip = layer.clip
        if (clip != null) graphics.enableScissor(clip.left, clip.top, clip.right, clip.bottom)
        FabricMinecraftFailures.runWithCleanup(
            { drawFabricMinecraftSampledImage(graphics, texture, layer.command) },
            { if (clip != null) graphics.disableScissor() },
        )
    }

    private fun extractPlatformLayer(
        graphics: GuiGraphicsExtractor,
        layer: FabricMinecraftFrameLayer.Platform,
        viewport: IntSize,
        platformCommands: MinecraftPlatformCommands<GuiGraphicsExtractor>,
    ) {
        val visible = intersection(IntRect(0, 0, viewport.width, viewport.height), layer.command.bounds)
        if (visible.width <= 0 || visible.height <= 0) return
        val clip = layer.clip
        if (clip != null) graphics.enableScissor(clip.left, clip.top, clip.right, clip.bottom)
        FabricMinecraftFailures.runWithCleanup(
            { platformCommands.render(graphics, layer.command) },
            { if (clip != null) graphics.disableScissor() },
        )
    }

    private fun releaseTextures() {
        canvasPresentation.release()
        preparedCommands = null
        preparedViewport = null
        preparedScale = null
        preparedLayers = emptyList()
        pointerPosition = null
        pointerFrameCommands = null
        FabricMinecraftFailures.runWithCleanup(portableFrames::release, sampledImages::release)
    }

    private fun positionOrNull(
        mouseX: Double,
        mouseY: Double,
    ): IntOffset? = mapMinecraftPosition(mouseX, mouseY)

    private fun buttonOrNull(button: Int): PointerButton? = mapMinecraftButton(button)

    private fun intersection(
        first: IntRect,
        second: IntRect,
    ): IntRect {
        val left = maxOf(first.left, second.left)
        val top = maxOf(first.top, second.top)
        val right = maxOf(left, minOf(first.right, second.right))
        val bottom = maxOf(top, minOf(first.bottom, second.bottom))
        return IntRect(left, top, right, bottom)
    }

    private fun requireClientThread() {
        check(minecraftClient.isSameThread()) { "Fabric Minecraft screens are confined to the client thread." }
    }

    /**
     * Owns the private implementation constructor used by the public factory.
     * The entry point is Kotlin-internal, JVM-synthetic, and confined to the Minecraft client thread.
     */
    internal companion object {
        /**
         * Creates one private screen implementation after host construction has transferred its definition.
         *
         * @param host transferred common host.
         * @param inventory borrowed platform bridge owned by [host].
         * @param parent screen restored after terminal close.
         * @param minecraft client used for parent navigation.
         * @return a private screen implementation.
         * @throws Throwable when implementation construction fails; the caller owns cleanup of [host].
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
 * Construction does not evaluate content. The parent defaults to the active Minecraft GUI screen through the version-specific screen access boundary.
 * Construction and every screen lifecycle and input callback belong on the Minecraft client thread; wrong-thread host operations preserve the common host failure contract.
 * The returned screen retains but does not own [parent]; permanent abandonment requires explicit client-thread close.
 *
 * @param definition the one-shot common screen definition.
 * @param profile the immutable asset profile.
 * @param parent the screen restored by [FabricMinecraftScreen.onClose].
 * @return a client-thread screen with terminal close ownership.
 * @throws IllegalStateException when called away from the Minecraft client thread or when [definition] was already transferred or closed.
 * @throws Throwable when title conversion, host construction, or private screen construction fails; transferred ownership is released before failure escapes.
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
    check(minecraft.isSameThread()) { "Fabric Minecraft screens must be created on the client thread." }
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
    check(minecraft.isSameThread()) { "Fabric Minecraft screens must be created on the client thread." }
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
    if (mapping is ToggleKeyMapping) mapping.shouldRestoreStateOnScreenClosed()
}
