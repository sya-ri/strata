@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.ComponentStateSubscription
import dev.s7a.strata.component.CycleButton
import dev.s7a.strata.component.CycleButtonState
import dev.s7a.strata.component.Stack
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.onCycle
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.MinecraftUiHost
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.text.UiText
import dev.s7a.strata.ui.UiDefinition

/**
 * Client-thread state and explicit oracles for one independent loaded CycleButton adapter case.
 * Screen ownership stays with the surrounding test; observers are installed before or after attachment as named.
 * A fresh reference host uses literal expected selection, labels and visual input state instead of candidate output.
 */
internal class MinecraftCycleButtonSelectionScene(
    private val case: MinecraftCycleButtonSelectionCase,
) : AutoCloseable {
    private val values = List(1024) { Choice(it) }
    private val original = CycleButtonState(values, values.last())
    private val selection = mutableStateOf(original)
    private val revision = mutableStateOf(0)
    private var alternateLabel = false
    private var nested = false
    private val failure = IllegalArgumentException("earlier native CycleButton observer")
    private val events = ArrayList<Pair<Event, Int>>()
    private val actionValues = ArrayList<Int>()
    private val earlier =
        original.observe { value ->
            events.add(Event.Observer to value.id)
            if (case == MinecraftCycleButtonSelectionCase.EarlierObserverFailure) throw failure
        }
    private var later: ComponentStateSubscription? = null
    private val enabled = case != MinecraftCycleButtonSelectionCase.DisabledInputs

    /**
     * Literal final pointer keeping hover outside focused cases so focus has its own visible oracle.
     */
    internal val pointerPosition: IntOffset = if (case.focused) outside else inside

    /**
     * Transfers the live caller-owned selection and declaration revision into one fresh native screen.
     */
    internal fun definition(): UiDefinition =
        UiDefinition("Native CycleButton selection") {
            revision.value
            Stack(modifier = Modifier.size(viewport.width, viewport.height).background(ArgbColor(0xFF000000.toInt()))) {
                CycleButton(
                    selection.value,
                    width = 80,
                    enabled = enabled,
                    label = { value -> label(value, alternateLabel) },
                    modifier =
                        Modifier.onCycle<Choice> { value ->
                            events.add(Event.Action to value.id)
                            actionValues.add(value.id)
                            if (case == MinecraftCycleButtonSelectionCase.ActionReentry) selection.value.value = values[1]
                        },
                )
            }
        }

    /**
     * Installs the later observer only after the retained node subscribed during its first native frame.
     */
    internal fun afterAttachment() {
        if (case == MinecraftCycleButtonSelectionCase.LaterObserverReentry) {
            later =
                original.observe { value ->
                    events.add(Event.LaterObserver to value.id)
                    if (nested.not()) {
                        nested = true
                        original.value = values[1]
                    }
                }
        }
    }

    /**
     * Executes one native callback or owner-thread state transition after initial presentation.
     * This boundary does not claim operating-system keyboard or pointer-device delivery.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod") // All independent native adapter cases keep their callback consumption and lifecycle transition explicit.
    internal fun operate(
        context: MinecraftCanvasTestContext,
        screen: FabricMinecraftScreen,
    ) {
        when (case) {
            MinecraftCycleButtonSelectionCase.UnchangedHover -> {}

            MinecraftCycleButtonSelectionCase.PointerForwardWrap,
            MinecraftCycleButtonSelectionCase.LaterObserverReentry,
            MinecraftCycleButtonSelectionCase.ActionReentry,
            -> {
                check(context.pressPointer(screen, inside))
                context.releasePointer(screen, inside)
            }

            MinecraftCycleButtonSelectionCase.ScrollForwardWrap -> {
                // Native vertical scroll is negated into the common increasing-y input direction.
                check(scrollMinecraftScreen(screen, inside, -1.0))
            }

            MinecraftCycleButtonSelectionCase.ScrollBackward -> {
                check(scrollMinecraftScreen(screen, inside, 1.0))
            }

            MinecraftCycleButtonSelectionCase.KeyLeft,
            MinecraftCycleButtonSelectionCase.KeyRight,
            MinecraftCycleButtonSelectionCase.KeySpace,
            MinecraftCycleButtonSelectionCase.KeyEnter,
            -> {
                check(pressMinecraftTab(screen))
                check(pressMinecraftKey(screen, checkNotNull(case.key)))
            }

            MinecraftCycleButtonSelectionCase.DisabledInputs -> {
                check(context.pressPointer(screen, inside).not())
                context.releasePointer(screen, inside)
                check(scrollMinecraftScreen(screen, inside, 1.0).not())
                for (key in listOf(KeyCode.Left, KeyCode.Right, KeyCode.Space, KeyCode.Enter)) check(pressMinecraftKey(screen, key).not())
            }

            MinecraftCycleButtonSelectionCase.ZeroScroll -> {
                check(scrollMinecraftScreen(screen, inside, 0.0).not())
            }

            MinecraftCycleButtonSelectionCase.ExternalWrite -> {
                original.value = values[0]
            }

            MinecraftCycleButtonSelectionCase.EarlierObserverFailure -> {
                val thrown = runCatching { original.value = values[0] }.exceptionOrNull()
                check(thrown === failure)
            }

            MinecraftCycleButtonSelectionCase.StateAndLabelReplacement -> {
                selection.value = CycleButtonState(values.reversed(), values.last())
                alternateLabel = true
                revision.value += 1
            }

            MinecraftCycleButtonSelectionCase.DetachedMutationReattach -> {
                context.setScreen(null)
                original.value = values[0]
                context.setScreen(screen)
            }
        }
    }

    /**
     * Mutates the previous source only after its replacement committed through the native screen.
     */
    internal fun afterOperationFrame() {
        if (case == MinecraftCycleButtonSelectionCase.StateAndLabelReplacement) original.value = values[0]
    }

    /**
     * Checks literal live value, ordered notifications, exact action payloads and committed native semantics.
     */
    internal fun verify(frame: RuntimeUiFrame) {
        check(selection.value.value === values[case.expectedValue]) {
            "Native $case selection differs: ${selection.value.value.id}, expected ${case.expectedValue}"
        }
        val expected = expectedEvents()
        check(events == expected) { "Native $case notifications differ: $events, expected $expected" }
        check(actionValues == expected.filter { it.first == Event.Action }.map { it.second })
        val semantics = frame.semantics.single().semantics
        val semanticValue = if (case == MinecraftCycleButtonSelectionCase.EarlierObserverFailure) 1023 else case.expectedValue
        val expectedLabel = label(values[semanticValue], case == MinecraftCycleButtonSelectionCase.StateAndLabelReplacement)
        check(semantics.role == SemanticsRole.CycleButton)
        check(semantics.label == expectedLabel && semantics.value == expectedLabel)
        check(semantics.disabled == enabled.not())
    }

    @Suppress("CyclomaticComplexMethod") // The event oracle is independent and explicit for every loaded adapter transition, including nested notification order.
    private fun expectedEvents(): List<Pair<Event, Int>> =
        when (case) {
            MinecraftCycleButtonSelectionCase.UnchangedHover,
            MinecraftCycleButtonSelectionCase.DisabledInputs,
            MinecraftCycleButtonSelectionCase.ZeroScroll,
            -> emptyList()

            MinecraftCycleButtonSelectionCase.ExternalWrite,
            MinecraftCycleButtonSelectionCase.EarlierObserverFailure,
            MinecraftCycleButtonSelectionCase.StateAndLabelReplacement,
            MinecraftCycleButtonSelectionCase.DetachedMutationReattach,
            -> listOf(Event.Observer to 0)

            MinecraftCycleButtonSelectionCase.LaterObserverReentry -> listOf(Event.Observer to 0, Event.LaterObserver to 0, Event.Observer to 1, Event.LaterObserver to 1, Event.Action to 0)

            MinecraftCycleButtonSelectionCase.ActionReentry -> listOf(Event.Observer to 0, Event.Action to 0, Event.Observer to 1)

            else -> listOf(Event.Observer to case.expectedValue, Event.Action to case.expectedValue)
        }

    /**
     * Renders detached expected pixels from literal case inputs using a fresh public host and native resource snapshot.
     */
    internal fun expectedPixels(profile: MinecraftUiProfile): HeadlessImage {
        val alternate = case == MinecraftCycleButtonSelectionCase.StateAndLabelReplacement
        val order = if (alternate) values.reversed() else values
        val reference = CycleButtonState(order, values[case.expectedValue])
        val definition =
            UiDefinition("Independent native CycleButton reference") {
                Stack(modifier = Modifier.size(viewport.width, viewport.height).background(ArgbColor(0xFF000000.toInt()))) {
                    CycleButton(reference, width = 80, enabled = enabled, label = { value -> label(value, alternate) })
                }
            }
        return createMinecraftUiHost(definition, profile, fontBackend = LwjglMinecraftFontBackendFactory).use { host ->
            host.attach()
            host.frame(viewport, FrameTime(0L))
            if (case.focused) check(host.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Tab, 0)) == InputResult.Consumed)
            host.dispatchPointer(PointerEvent.Move(pointerPosition))
            val frame = host.frame(viewport, FrameTime(0L))
            rasterizeHeadless(frame.drawCommands, frame.size)
        }
    }

    /**
     * Borrows the native wrapper's existing public host after the presentation fence, without adding runtime SPI.
     * The extra retained frame has no animation or mutation and must match the already presented draw commands.
     */
    internal fun nativeFrame(screen: FabricMinecraftScreen): RuntimeUiFrame {
        val host = MinecraftCompositionParityInputs.member(screen, "host") as MinecraftUiHost
        val frame = host.frame(viewport)
        check(frame.drawCommands == screen.captureCanvasFrame())
        return frame
    }

    override fun close() {
        runCanvasTestCleanup(null, { later?.close() }, { earlier.close() })
        later = null
    }

    private enum class Event { Observer, LaterObserver, Action }

    // Preserve the exact immutable selected object when a different state reverses its option order.
    private class Choice(
        val id: Int,
    )

    private fun label(
        value: Choice,
        alternate: Boolean,
    ): UiText = UiText.Literal((if (alternate) "W" else "V") + value.id.toString().padStart(6, '0'))

    /**
     * Fixed logical scene extent with an opaque backdrop, independent of the CPU corpus's host viewport.
     */
    internal companion object {
        internal val viewport = IntSize(320, 240)
        internal val inside = IntOffset(2, 2)
        internal val outside = IntOffset(90, 30)
    }
}
