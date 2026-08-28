package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.ScrollArea
import dev.s7a.strata.component.ScrollState
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.onPointerEvent
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Observes continuous native input against one real Fabric screen without retaining a host or native callback.
 *
 * Construct and use this probe on the client thread. The suite owns the screen and closes it after verification, including failures.
 * The probe retains only its current scroll state and the latest three local input positions.
 */
internal class MinecraftContinuousInputProbe {
    private val owner = Thread.currentThread()
    private val state = ScrollState()
    private var moved: IntOffset? = null
    private var pressed: IntOffset? = null
    private var released: IntOffset? = null

    /**
     * Stable logical screen position used by every native callback in the burst.
     */
    val position: IntOffset = IntOffset(20, 20)

    /**
     * Creates the public ScrollArea scene whose content is initially placed at logical position (10, 2).
     * The outer Stack keeps the child viewport at 100 by 50 under any larger native test window.
     */
    fun definition(): ScreenDefinition {
        requireOwner()
        return ScreenDefinition("Continuous native input") {
            Stack {
                ScrollArea(state, modifier = Modifier.Empty.size(100, 50)) {
                    Spacer(
                        modifier =
                            Modifier.Empty
                                .size(80, 180)
                                .background(ArgbColor(0xFFABCDEF.toInt()))
                                .onPointerEvent(::observe),
                    )
                }
            }
        }
    }

    /**
     * Reports completion of the initial retained geometry; the suite independently requires an actual native frame.
     */
    fun isReady(): Boolean {
        requireOwner()
        return state.metrics.viewportExtent == 50 && state.metrics.contentExtent == 184 && state.metrics.offset == 0.0
    }

    /**
     * Runs scroll/move/press/release and scroll/press/release in one uninterrupted client-thread action.
     *
     * The callbacks must call the active native screen directly and must not schedule work or request frames.
     * The frame counter is borrowed from the suite's existing native presentation observations.
     * A press resets scrolling, so its immediately following release must use the reset geometry as well.
     *
     * @param frameCount completed common frames requested by the native screen.
     * @param scroll native downward scroll of one wheel unit at [position], returning native consumption.
     * @param move native move at [position].
     * @param click native primary press followed immediately by release at [position].
     * @return detached verified receipt text; all callback and screen ownership remains with the caller.
     * @throws IllegalStateException for incorrect geometry, intermediate frames, missing consumption, or another thread.
     * @throws Throwable when any native callback fails; the caller must still close its owned screen.
     */
    fun verify(
        frameCount: () -> Long,
        scroll: () -> Boolean,
        move: () -> Unit,
        click: () -> Unit,
    ): String {
        requireOwner()
        check(isReady()) { "The continuous-input scene must complete its initial layout before native input." }
        val before = frameCount()
        check(0L < before) { "Continuous input requires a completed native screen frame." }
        check(scroll()) { "The native ScrollArea wheel event was not consumed." }
        check(state.metrics.offset == 9.0) { "The native wheel event did not produce the expected nine-pixel scroll." }
        move()
        check(moved == IntOffset(10, 27)) { "The immediate native move used stale scroll geometry: $moved" }
        click()
        verifyClick()
        pressed = null
        released = null
        check(scroll()) { "The second native ScrollArea wheel event was not consumed." }
        check(state.metrics.offset == 9.0) { "The second native wheel event did not update scrolling." }
        click()
        verifyClick()
        val after = frameCount()
        check(after == before) { "The input burst unexpectedly requested a host frame: before=$before, after=$after" }
        return buildString {
            appendLine("status=passed")
            appendLine("input=typed-native-screen-callbacks")
            appendLine("cases=scroll-move-press-release,scroll-press-release")
            appendLine("initialHostFrames=$before")
            appendLine("finalHostFrames=$after")
            appendLine("moveLocal=$moved")
            appendLine("pressLocal=$pressed")
            appendLine("releaseLocal=$released")
            appendLine("finalScrollOffset=${state.metrics.offset}")
        }
    }

    private fun observe(
        event: PointerEvent,
        local: IntOffset,
    ): InputResult {
        requireOwner()
        return when (event) {
            is PointerEvent.Move -> {
                moved = local
                InputResult.Consumed
            }

            is PointerEvent.Press -> {
                pressed = local
                state.scrollTo(0.0)
                InputResult.Consumed
            }

            is PointerEvent.Release -> {
                released = local
                InputResult.Consumed
            }

            else -> {
                InputResult.Ignored
            }
        }
    }

    private fun verifyClick() {
        check(pressed == IntOffset(10, 27)) { "The immediate native press used stale scroll geometry: $pressed" }
        check(released == IntOffset(10, 18)) { "The immediate native release ignored the press callback's scroll reset: $released" }
        check(state.metrics.offset == 0.0) { "The native press must preserve its scroll reset through release." }
    }

    private fun requireOwner() {
        check(Thread.currentThread() === owner) { "The continuous-input probe must remain on its client thread." }
    }
}
