package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.Canvas
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.ScrollArea
import dev.s7a.strata.component.ScrollState
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.canvasSource
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.FocusEvent
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.PointerHoverEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onCapturedPointerEvent
import dev.s7a.strata.modifier.onFocusChanged
import dev.s7a.strata.modifier.onHover
import dev.s7a.strata.modifier.onPointerEvent
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import java.nio.file.Files

/**
 * Exercises ordinary Canvas input modifiers through real screen callbacks and the installed native window-focus hook.
 *
 * CPU images deliberately keep these input checks independent of the separate GPU producer and framebuffer assertions.
 * Mutable traces, scrolling, callbacks, and screen ownership stay on the client thread; the runner owns the receipt file.
 * Focus is changed by invoking the native callback with its real handle, not by moving operating-system focus.
 * When Fabric cancels test-window input, an exact one-callback scope permits this invocation and checks ordinary cancellation outside it.
 * Every screen and the original native focus state are cleaned independently while preserving the first failure.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftCanvasPointerGameTest {
    /**
     * Verifies capture, current-layout coordinates, blur, detach, close, and failing cancellation in the loaded client.
     *
     * The caller owns viewport restoration. Native callback, assertion, scheduling, and file failures propagate unchanged.
     */
    internal fun run(
        context: MinecraftCanvasTestContext,
        profile: MinecraftUiProfile,
    ) {
        val previousFocus = context.onClient { Minecraft.getInstance().isWindowActive }
        val outcome =
            runCatching {
                context.configureViewport(IntSize(640, 480), 2)
                context.waitFor { context.hasOverlay().not() }
                withScene(context, profile) { scene, screen ->
                    verifyPassiveAndIgnoredPress(context, scene, screen)
                    verifyCapturedCoordinates(context, scene, screen)
                    verifyWindowReset(context, scene, screen)
                    verifyClose(context, scene, screen)
                }
                withScene(context, profile) { scene, screen -> verifyDetach(context, scene, screen) }
                withScene(context, profile) { scene, screen -> verifyFailingReset(context, scene, screen) }
            }
        runCanvasTestCleanup(outcome.exceptionOrNull(), { context.onClient { context.setWindowFocused(previousFocus) } })
        outcome.getOrThrow()
        Files.createDirectories(context.outputDirectory)
        Files.writeString(
            context.outputDirectory.resolve("strata-canvas-pointer.txt"),
            "case=strata-canvas-pointer\nphysical=640x480\nguiScale=2\ninputBridge=native-screen-callbacks\n" +
                "focusBridge=Window.onFocus\nfocusHarnessCancellation=${MinecraftCanvasWindowTestScope.hasHarnessCancellation()}\n" +
                "focusHarnessScope=when-active-one-explicit-callback-with-unscoped-negative-control\n" +
                "osFocusTransition=not-exercised\n" +
                "checked=passive,ignored-press,consumed-press,ancestor-clip,outside-bounds,current-layout,exclusive-delivery," +
                "normal-release,blur,repeat-blur,late-release,detach,close,cancel-failure,cleanup-suppression\n",
        )
    }

    private fun withScene(
        context: MinecraftCanvasTestContext,
        profile: MinecraftUiProfile,
        action: (Scene, FabricMinecraftScreen) -> Unit,
    ) {
        val scene = context.onClient { Scene() }
        val screen = context.onClient { createMinecraftScreen(scene.definition(), profile, parent = null) }
        val outcome =
            runCatching {
                context.onClient { context.setScreen(screen) }
                context.waitFor { scene.hasCommittedLayout(screen, 0) && context.hasOverlay().not() }
                action(scene, screen)
            }
        runCanvasTestCleanup(
            outcome.exceptionOrNull(),
            { context.onClient { context.setScreen(null) } },
            { context.onClient { screen.close() } },
        )
        outcome.getOrThrow()
    }

    private fun verifyPassiveAndIgnoredPress(
        context: MinecraftCanvasTestContext,
        scene: Scene,
        screen: FabricMinecraftScreen,
    ) {
        context.onClient {
            screen.movePointer(passivePosition)
            scene.clearPointerTrace()
            check(context.pressPointer(screen, passivePosition))
            check(scene.observed.isEmpty() && scene.focusEvents.isEmpty()) { "A display-only Canvas must not acquire input or focus." }
            check(scene.fallback == listOf(PointerEvent.Press(passivePosition, PointerButton.Primary)))
            context.releasePointer(screen, passivePosition)

            screen.movePointer(inside)
            scene.clearPointerTrace()
            check(context.pressPointer(screen, inside)) { "The ordinary ancestor must consume the ignored press." }
            val press = PointerEvent.Press(inside, PointerButton.Primary)
            check(scene.observed == listOf(Observation(press, IntOffset(4, 6))))
            check(scene.fallback == listOf(press))
            scene.clearPointerTrace()
            screen.movePointer(outsideClip)
            check(scene.observed.isEmpty()) { "An ignored captured-handler press must not start capture." }
            check(scene.fallback == listOf(PointerEvent.Move(outsideClip)))
            check(context.releasePointer(screen, outsideClip))
            check(scene.cancellations.isEmpty())
        }
    }

    private fun verifyCapturedCoordinates(
        context: MinecraftCanvasTestContext,
        scene: Scene,
        screen: FabricMinecraftScreen,
    ) {
        context.onClient {
            scene.acceptPress = true
            screen.movePointer(inside)
            scene.clearPointerTrace()
            check(context.pressPointer(screen, inside))
            check(scene.observed == listOf(Observation(PointerEvent.Press(inside, PointerButton.Primary), IntOffset(4, 6))))
            check(scene.fallback.isEmpty())
            check(scene.scroll.scrollTo(8.0) == 8.0)
        }
        context.waitFor { scene.hasCommittedLayout(screen, 8) }
        context.onClient {
            scene.clearPointerTrace()
            screen.movePointer(inside)
            check(scene.hovered)
            screen.movePointer(outsideClip)
            check(scene.hovered.not()) { "Captured movement must retain actual clipped hover hit testing." }
            screen.movePointer(outsideBounds)
            check(context.dragPointer(screen, dragPosition, dragDelta)) { "An ignored captured drag must still stop propagation." }
            check(context.releasePointer(screen, dragPosition)) { "An ignored captured release must still stop propagation." }
            val expected =
                listOf(
                    Observation(PointerEvent.Move(inside), IntOffset(4, 14)),
                    Observation(PointerEvent.Move(outsideClip), IntOffset(8, 54)),
                    Observation(PointerEvent.Move(outsideBounds), IntOffset(-9, 98)),
                    Observation(PointerEvent.Drag(dragPosition, PointerButton.Primary, 89.0, -36.0), IntOffset(80, 62)),
                    Observation(PointerEvent.Release(dragPosition, PointerButton.Primary), IntOffset(80, 62)),
                )
            check(scene.observed == expected) { "Native capture must use current unclamped logical coordinates after scrolling: ${scene.observed}" }
            check(scene.fallback.isEmpty()) { "Captured events must not fall through to an ordinary ancestor." }
            check(scene.cancellations.isEmpty()) { "A normal matching release must not cancel capture." }
            scene.clearPointerTrace()
            check(context.releasePointer(screen, dragPosition))
            check(scene.observed.isEmpty() && scene.fallback == listOf(PointerEvent.Release(dragPosition, PointerButton.Primary)))
        }
    }

    private fun verifyWindowReset(
        context: MinecraftCanvasTestContext,
        scene: Scene,
        screen: FabricMinecraftScreen,
    ) {
        context.onClient {
            context.setWindowFocused(true)
            screen.movePointer(inside)
            check(context.pressPointer(screen, inside))
            check(scene.focused && scene.hovered)
            scene.clearPointerTrace()
            context.setWindowFocused(false)
            check(Minecraft.getInstance().isWindowActive.not()) { "The real native focus callback must commit its focus state before reset." }
            check(scene.cancellations == listOf(PointerButton.Primary))
            check(scene.focused.not() && scene.hovered.not()) { "Native blur must clear both retained focus and hover." }
            val focusEvents = scene.focusEvents.toList()
            val hoverEvents = scene.hoverEvents.toList()
            context.setWindowFocused(false)
            check(context.releasePointer(screen, dragPosition))
            check(scene.cancellations == listOf(PointerButton.Primary)) { "Repeated blur and a later release must not cancel twice." }
            check(scene.focusEvents == focusEvents && scene.hoverEvents == hoverEvents)
            check(scene.observed.isEmpty()) { "A release after blur must no longer go to the capture owner outside its bounds." }
            context.setWindowFocused(true)
        }
    }

    private fun verifyClose(
        context: MinecraftCanvasTestContext,
        scene: Scene,
        screen: FabricMinecraftScreen,
    ) {
        context.onClient {
            screen.movePointer(inside)
            check(context.pressPointer(screen, inside))
            val previous = scene.cancellations.size
            val focusEvents = scene.focusEvents.toList()
            val hoverEvents = scene.hoverEvents.toList()
            screen.close()
            check(scene.cancellations.size == previous + 1)
            check(scene.focusEvents == focusEvents && scene.hoverEvents == hoverEvents) {
                "Terminal close must release ownership without synthesizing focus or hover transitions."
            }
            requireNoCallbacks(context, scene, screen)
            context.setScreen(null)
        }
    }

    private fun verifyDetach(
        context: MinecraftCanvasTestContext,
        scene: Scene,
        screen: FabricMinecraftScreen,
    ) {
        context.onClient {
            scene.acceptPress = true
            screen.movePointer(inside)
            check(context.pressPointer(screen, inside))
            context.setScreen(null)
            check(scene.cancellations == listOf(PointerButton.Primary)) { "Native removal must cancel capture even though the retained tree survives detach." }
            check(scene.focused.not() && scene.hovered.not())
            requireNoCallbacks(context, scene, screen)
        }
    }

    private fun verifyFailingReset(
        context: MinecraftCanvasTestContext,
        scene: Scene,
        screen: FabricMinecraftScreen,
    ) {
        context.onClient {
            context.setWindowFocused(true)
            scene.acceptPress = true
            screen.movePointer(outsideClip)
            screen.movePointer(inside)
            check(context.pressPointer(screen, inside))
            val primary = IllegalStateException("Loaded pointer cancellation failure")
            val secondary = IllegalStateException("Loaded hover cleanup failure")
            scene.cancellationFailure = primary
            scene.hoverExitFailure = secondary
            val observed = runCatching { context.setWindowFocused(false) }.exceptionOrNull()
            check(observed === primary) { "Native blur must preserve the exact cancellation failure." }
            check(primary.suppressed.toList() == listOf(secondary)) { "Independent hover cleanup must be attempted and suppressed on the original failure." }
            check(scene.cancellations == listOf(PointerButton.Primary))
            check(scene.focused.not() && scene.hovered.not()) { "A failing cancellation must not skip focus or hover cleanup." }
            val counts = scene.traceCounts()
            context.setWindowFocused(false)
            requireNoCallbacks(context, scene, screen)
            check(scene.traceCounts() == counts) { "A failed native reset must stop all later input and must not repeat cancellation." }
            context.setScreen(null)
            context.setWindowFocused(true)
        }
    }

    private fun requireNoCallbacks(
        context: MinecraftCanvasTestContext,
        scene: Scene,
        screen: FabricMinecraftScreen,
    ) {
        val before = scene.traceCounts()
        val events =
            listOf<() -> Any>(
                { screen.movePointer(inside) },
                { context.pressPointer(screen, inside) },
                { context.dragPointer(screen, dragPosition, dragDelta) },
                { context.releasePointer(screen, dragPosition) },
            )
        for (event in events) {
            val failure = runCatching(event).exceptionOrNull()
            check(failure == null || failure is IllegalStateException) { "Detached or closed input must reject at the lifecycle boundary, not invoke a user callback." }
            check(scene.traceCounts() == before) { "Detached, closed, or failed screens must not run another input callback." }
        }
    }

    private fun Screen.movePointer(position: IntOffset) {
        mouseMoved(position.x.toDouble(), position.y.toDouble())
    }

    private class Scene {
        val image = createDrawImage(IntSize(1, 1), intArrayOf(0xFF44AAFF.toInt()))
        private val source = canvasSource(image)
        private val passiveSource = canvasSource(createDrawImage(IntSize(1, 1), intArrayOf(0xFFDD8844.toInt())))
        val scroll = ScrollState()
        val observed: MutableList<Observation> = ArrayList()
        val fallback: MutableList<PointerEvent> = ArrayList()
        val cancellations: MutableList<PointerButton> = ArrayList()
        val focusEvents: MutableList<FocusEvent> = ArrayList()
        val hoverEvents: MutableList<PointerHoverEvent> = ArrayList()
        var acceptPress = false
        var cancellationFailure: Throwable? = null
        var hoverExitFailure: Throwable? = null
        val focused: Boolean
            get() = focusEvents.lastOrNull() == FocusEvent.Gained
        val hovered: Boolean
            get() = hoverEvents.lastOrNull() == PointerHoverEvent.Enter

        fun definition(): ScreenDefinition =
            ScreenDefinition("Canvas pointer capture acceptance") {
                Stack(
                    Modifier.Empty.onPointerEvent { event, _ ->
                        fallback += event
                        InputResult.Consumed
                    },
                ) {
                    Row(spacing = 16) {
                        ScrollArea(scroll, Modifier.Empty.size(40, 40)) {
                            Canvas(
                                source,
                                IntSize(32, 80),
                                Modifier.Empty
                                    .onHover { event ->
                                        hoverEvents += event
                                        if (event == PointerHoverEvent.Exit) hoverExitFailure?.let { throw it }
                                    }.onFocusChanged { focusEvents += it }
                                    .onCapturedPointerEvent(
                                        onCancel = { button ->
                                            cancellations += button
                                            cancellationFailure?.let { throw it }
                                        },
                                    ) { event, local ->
                                        observed += Observation(event, local)
                                        if (acceptPress && event is PointerEvent.Press) InputResult.Consumed else InputResult.Ignored
                                    },
                            )
                        }
                        Canvas(passiveSource, IntSize(32, 16))
                    }
                }
            }

        fun hasCommittedLayout(
            screen: FabricMinecraftScreen,
            offset: Int,
        ): Boolean =
            runCatching {
                val commands = screen.captureCanvasFrame()
                val canvas = commands.filterIsInstance<DrawCommand.BlitImage>().single { it.image === image }
                // Minecraft ScrollArea centers its child horizontally and places it two pixels below the viewport origin before scrolling.
                canvas.destination == IntRect(4, 2 - offset, 36, 82 - offset) &&
                    commands.any { it is DrawCommand.PushClip && it.bounds == IntRect(0, 0, 40, 40) }
            }.getOrDefault(false)

        fun clearPointerTrace() {
            observed.clear()
            fallback.clear()
        }

        fun traceCounts(): List<Int> = listOf(observed.size, fallback.size, cancellations.size, focusEvents.size, hoverEvents.size)
    }

    private data class Observation(
        val event: PointerEvent,
        val local: IntOffset,
    )

    private val inside = IntOffset(8, 8)
    private val passivePosition = IntOffset(60, 8)
    private val outsideClip = IntOffset(12, 48)
    private val outsideBounds = IntOffset(-5, 92)
    private val dragPosition = IntOffset(84, 56)
    private val dragDelta = IntOffset(89, -36)
}
