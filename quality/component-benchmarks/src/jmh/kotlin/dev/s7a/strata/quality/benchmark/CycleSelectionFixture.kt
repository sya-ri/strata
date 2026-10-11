package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.ComponentStateSubscription
import dev.s7a.strata.component.CycleButton
import dev.s7a.strata.component.CycleButtonState
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.UiScope
import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onCycle
import dev.s7a.strata.performance.RuntimeWorkMonitor
import dev.s7a.strata.performance.WorkExpectation
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.MinecraftUiHost
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.MutableState
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.text.UiText
import dev.s7a.strata.ui.UiDefinition
import java.lang.ref.WeakReference

/**
 * One owner-confined public host operation with immutable producers prepared before measurement.
 * Optional admission records exact input, action/observer order, dirty work, commands and detached pixels outside timing.
 */
@OptIn(InternalStrataRuntimeApi::class)
@Suppress("TooManyFunctions") // The frozen independent transitions share one public-host ownership and parity implementation.
internal class CycleSelectionFixture(
    count: Int = 8,
    selected: Int = 0,
    private val control: CycleSelectionControl? = null,
    private val verify: Boolean = false,
) : AutoCloseable {
    private var values: List<Choice>? = List(if (control == CycleSelectionControl.OneOptionUnchangedFrame) 1 else count) { Choice(it) }
    private var selection: MutableState<CycleButtonState<Choice>>? = mutableStateOf(CycleButtonState(checkNotNull(values), initial(selected)))
    private var replacement: CycleButtonState<Choice>? =
        CycleButtonState(if (control == CycleSelectionControl.StateReplacementDifferentOrder) choices.reversed() else choices, state.value)
    private var settings: MutableState<Int>? = mutableStateOf(0)
    private var observer: ComponentStateSubscription? = null
    private var later: ComponentStateSubscription? = null
    private var aliasState: CycleButtonState<Choice>? = null
    private var aliasHost: MinecraftUiHost? = null
    private var profile: MinecraftUiProfile? =
        ComponentProfile.create(if (control in setOf(CycleSelectionControl.NormalInactiveGeometryParity, CycleSelectionControl.LabelAndGlyphReplacement)) CycleSelectionFontAssets.snapshot() else null)
    private var host: MinecraftUiHost? = null
    private var monitor: RuntimeWorkMonitor? = null
    private var inside = false
    private var focused = false
    private var enabled = control != CycleSelectionControl.DisabledIgnoredInput
    private var width = 80
    private var alternateLabel = false
    private var notifications = 0
    private var nested = false
    private var closed = false
    private var original: Choice? = state.value
    private var alternate: Choice? = choices[(selected + 1) % choices.size]
    private val equal = Choice(if (control == CycleSelectionControl.EqualDistinctNoOpWrite) choices.last().id else checkNotNull(original).id)
    private val outside = Choice(count + 1)
    private val aliasLater = Choice(2, setOf(1))
    private val aliasEarlier = Choice(3, setOf(0, 1))
    private val events = ArrayList<Pair<Event, Int>>()
    private val actionValues = ArrayList<Choice>()
    private val released = ArrayList<WeakReference<Any>>()
    private var lastFrame: RuntimeUiFrame? = null
    private val failure = ObserverFailure()
    private val viewport = IntSize(100, 20)
    private val time = FrameTime(0)
    private val state: CycleButtonState<Choice> get() = checkNotNull(selection).value
    private val choices: List<Choice> get() = checkNotNull(values)

    init {
        if (control in setOf(CycleSelectionControl.ForwardWrap, CycleSelectionControl.EqualDistinctNoOpWrite)) state.value = choices.last()
        observer =
            state.observe {
                notifications += 1
                if (verify) events.add(Event.Observer to it.id)
                if (control == CycleSelectionControl.EarlierObserverThrows) throw failure
            }
        host = if (control == CycleSelectionControl.MutationBeforeAttach) declaredHost() else freshHost { state }
        if (control != CycleSelectionControl.MutationBeforeAttach) {
            currentHost.attach()
            if (control in setOf(CycleSelectionControl.KeyLeft, CycleSelectionControl.KeyRight, CycleSelectionControl.KeySpace, CycleSelectionControl.KeyEnter)) {
                currentHost.frame(viewport, time)
                check(currentHost.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Tab, 0)) == InputResult.Consumed)
                focused = true
            }
            lastFrame = currentHost.frame(viewport, time)
        }
        if (control == CycleSelectionControl.LaterObserverReentersSelection) {
            later =
                state.observe {
                    if (verify) events.add(Event.LaterObserver to it.id)
                    if (nested.not()) {
                        nested = true
                        state.value = choices.last()
                    }
                }
        }
        if (control == CycleSelectionControl.EqualDistinctNoOpWrite) {
            aliasState = CycleButtonState(listOf(Choice(0), Choice(1)), aliasLater)
            aliasHost = freshHost { checkNotNull(aliasState) }
            checkNotNull(aliasHost).attach()
            checkNotNull(aliasHost).frame(viewport, time)
        }
        if (verify && control != CycleSelectionControl.MutationBeforeAttach) {
            monitor = RuntimeWorkMonitor(currentHost, maxNodeRecords = 16)
            checkNotNull(monitor).checkpoint()
        }
    }

    private val currentHost: MinecraftUiHost get() = checkNotNull(host)

    private fun initial(selected: Int): Choice = checkNotNull(values)[selected]

    /**
     * Alternates real hover, preserving selection and completing the resulting retained frame.
     */
    internal fun hover(): RuntimeUiFrame {
        inside = inside.not()
        val result = currentHost.dispatchPointer(PointerEvent.Move(if (inside) IntOffset(2, 2) else IntOffset(90, 2)))
        check(result == InputResult.Ignored)
        return complete()
    }

    /**
     * Alternates preconstructed adjacent valid values through validation, publication and both dirty phases.
     */
    internal fun write(): RuntimeUiFrame {
        state.value = checkNotNull(if (state.value === original) alternate else original)
        return complete()
    }

    /**
     * Executes the exact control sequence named by the frozen registry.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod") // Every frozen independent transition is explicit in this shared complete-operation boundary.
    internal fun control(): RuntimeUiFrame {
        when (checkNotNull(control)) {
            CycleSelectionControl.CleanRetainedFrame -> {
                Unit
            }

            CycleSelectionControl.OneOptionUnchangedFrame -> {
                return hover()
            }

            CycleSelectionControl.DisabledIgnoredInput -> {
                pointer(PointerEvent.Press(IntOffset(2, 2), PointerButton.Primary), InputResult.Ignored)
                for (key in listOf(KeyCode.Left, KeyCode.Right, KeyCode.Space, KeyCode.Enter)) key(key, InputResult.Ignored)
            }

            CycleSelectionControl.IgnoredPointerButton -> {
                pointer(PointerEvent.Press(IntOffset(2, 2), PointerButton.Secondary), InputResult.Ignored)
            }

            CycleSelectionControl.ZeroScroll -> {
                pointer(PointerEvent.Scroll(IntOffset(2, 2), 0.0, 0.0), InputResult.Ignored)
            }

            CycleSelectionControl.ForwardWrap, CycleSelectionControl.PointerPrimaryCycle -> {
                press()
            }

            CycleSelectionControl.BackwardWrap, CycleSelectionControl.ScrollBackward -> {
                scroll(-1.0)
            }

            CycleSelectionControl.ScrollForward -> {
                scroll(1.0)
            }

            CycleSelectionControl.KeyLeft -> {
                key(KeyCode.Left)
            }

            CycleSelectionControl.KeyRight -> {
                key(KeyCode.Right)
            }

            CycleSelectionControl.KeySpace -> {
                key(KeyCode.Space)
            }

            CycleSelectionControl.KeyEnter -> {
                key(KeyCode.Enter)
            }

            CycleSelectionControl.ExternalSelectedWrite -> {
                state.value = checkNotNull(alternate)
            }

            CycleSelectionControl.EqualDistinctNoOpWrite -> {
                return equalAndAliasSequence()
            }

            CycleSelectionControl.InvalidOutsideValueRejected -> {
                try {
                    state.value = outside
                    error("Outside selection was accepted")
                } catch (expected: IllegalArgumentException) {
                    check(state.value === original) { "Rejected outside selection mutated the state: $expected" }
                }
                return hover()
            }

            CycleSelectionControl.LabelsSameTextDifferentValues, CycleSelectionControl.EmptyLabels -> {
                state.value = checkNotNull(alternate)
            }

            CycleSelectionControl.NormalInactiveGeometryParity -> {
                enabled = false
                redeclare()
                complete()
                enabled = true
                redeclare()
            }

            CycleSelectionControl.WidthOnlyRedeclaration -> {
                width = 90
                redeclare()
            }

            CycleSelectionControl.LabelAndGlyphReplacement -> {
                alternateLabel = true
                redeclare()
            }

            CycleSelectionControl.StateReplacementSameSelection, CycleSelectionControl.StateReplacementDifferentOrder -> {
                val previous = state
                checkNotNull(selection).value = checkNotNull(replacement)
                replacement = null
                complete()
                previous.value = checkNotNull(alternate)
            }

            CycleSelectionControl.MutationBeforeAttach -> {
                state.value = choices.last()
                currentHost.attach()
            }

            CycleSelectionControl.MutationDuringDetachReattach -> {
                currentHost.detach()
                state.value = choices.last()
                currentHost.attach()
            }

            CycleSelectionControl.EarlierObserverThrows -> {
                try {
                    state.value = choices.last()
                    error("Earlier observer did not fail")
                } catch (expected: ObserverFailure) {
                    check(expected === failure)
                }
                return hover()
            }

            CycleSelectionControl.LaterObserverReentersSelection -> {
                state.value = checkNotNull(alternate)
            }

            CycleSelectionControl.ActionCallbackReentersSelection -> {
                press()
            }

            CycleSelectionControl.DisposeCloseRelease -> {
                released.add(WeakReference<Any>(state))
                choices.forEach { released.add(WeakReference<Any>(it)) }
                checkNotNull(selection).value = checkNotNull(replacement)
                replacement = null
                val frame = complete()
                close()
                return frame
            }
        }
        return complete()
    }

    private fun equalAndAliasSequence(): RuntimeUiFrame {
        val previous = state.value
        state.value = equal
        check(state.value === previous)
        complete()
        val aliases = checkNotNull(aliasState)
        aliases.value = aliasEarlier
        var frame = checkNotNull(aliasHost).frame(viewport, time)
        if (verify) verifyFrame(frame, aliases, aliasEarlier, expectedIndex = 0)
        aliases.value = aliasLater
        frame = checkNotNull(aliasHost).frame(viewport, time)
        if (verify) verifyFrame(frame, aliases, aliasLater, expectedIndex = 1)
        return frame
    }

    private fun press() {
        pointer(PointerEvent.Press(IntOffset(2, 2), PointerButton.Primary))
        focused = true
    }

    private fun scroll(delta: Double) {
        pointer(PointerEvent.Scroll(IntOffset(2, 2), 0.0, delta))
    }

    private fun pointer(
        event: PointerEvent,
        expected: InputResult = InputResult.Consumed,
    ) {
        check(currentHost.dispatchPointer(event) == expected)
    }

    private fun key(
        key: KeyCode,
        expected: InputResult = InputResult.Consumed,
    ) {
        check(currentHost.dispatchKeyboard(KeyboardEvent.Press(key, 0)) == expected)
    }

    private fun redeclare() {
        checkNotNull(settings).value += 1
    }

    private fun complete(): RuntimeUiFrame {
        val frame = currentHost.frame(viewport, time)
        if (verify) {
            val expectedLabel = if (control == CycleSelectionControl.EarlierObserverThrows) label(choices.first()) else null
            verifyFrame(frame, state, state.value, expectedSemantics = expectedLabel)
        }
        lastFrame = frame
        return frame
    }

    private fun freshHost(selected: () -> CycleButtonState<Choice>): MinecraftUiHost =
        createMinecraftUiHost(
            UiDefinition("Frozen cycle selection") {
                checkNotNull(settings).value
                Stack {
                    CycleButton(
                        selected(),
                        width = width,
                        enabled = enabled,
                        modifier =
                            Modifier.Empty.onCycle<Choice>({ value ->
                                if (verify) {
                                    events.add(Event.Action to value.id)
                                    actionValues.add(value)
                                }
                                if (control == CycleSelectionControl.ActionCallbackReentersSelection) state.value = choices.last()
                            }),
                        label = ::label,
                    )
                }
            },
            checkNotNull(profile),
            fontBackend = LwjglMinecraftFontBackendFactory,
        )

    private fun declaredHost(): MinecraftUiHost {
        var declared: Element? = null
        val fresh = createMinecraftUiHost(UiDefinition("Mutation before attachment") { element(checkNotNull(declared)) }, checkNotNull(profile))
        // The existing host evaluator installs its profile for declaration without attaching the retained tree.
        val field = fresh.javaClass.getDeclaredField("evaluator")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val evaluator = field.get(fresh) as (UiScope.() -> Unit) -> Element
        declared = evaluator { Stack { CycleButton(state, width = width, label = ::label) } }
        return fresh
    }

    private fun label(value: Choice): UiText {
        val literal =
            UiText.Literal(
                when (control) {
                    CycleSelectionControl.LabelsSameTextDifferentValues -> "Same"
                    CycleSelectionControl.EmptyLabels -> ""
                    else -> (if (alternateLabel) "W" else "V") + value.id.toString().padStart(6, '0')
                },
            )
        return if (alternateLabel) UiText.WithFont(literal, ResourceId("strata_benchmark", "alternate")) else literal
    }

    private fun verifyFrame(
        frame: RuntimeUiFrame,
        selected: CycleButtonState<Choice>,
        value: Choice,
        expectedIndex: Int = selected.values.indexOf(value),
        expectedSemantics: UiText? = null,
    ) {
        val expected = label(selected.values[expectedIndex])
        val semantics = frame.semantics.single().semantics
        check(semantics.label == (expectedSemantics ?: expected))
        check(semantics.value == (expectedSemantics ?: expected))
        check(semantics.disabled == enabled.not())
        val reference = CycleButtonState(selected.values, selected.values[expectedIndex])
        freshHost { reference }.use { host ->
            host.attach()
            host.frame(viewport, time)
            if (focused) host.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Tab, 0))
            if (inside) host.dispatchPointer(PointerEvent.Move(IntOffset(2, 2)))
            val independent = host.frame(viewport, time)
            check(frame.drawCommands.size == independent.drawCommands.size)
            frame.drawCommands.zip(independent.drawCommands).forEach { (actual, expectedCommand) ->
                val normalized =
                    when {
                        actual is DrawCommand.BlitImage && expectedCommand is DrawCommand.BlitImage -> {
                            check(actual.image.copyArgb().contentEquals(expectedCommand.image.copyArgb()))
                            actual.copy(image = expectedCommand.image)
                        }

                        actual is DrawCommand.SampledImage && expectedCommand is DrawCommand.SampledImage -> {
                            check(actual.image.copyArgb().contentEquals(expectedCommand.image.copyArgb()))
                            actual.copy(image = expectedCommand.image)
                        }

                        else -> {
                            actual
                        }
                    }
                check(normalized == expectedCommand) { "Cycle selection command order or payload changed" }
            }
            check(rasterizeHeadless(frame.drawCommands, viewport).copyArgb().contentEquals(rasterizeHeadless(independent.drawCommands, viewport).copyArgb())) { "Cycle selection detached pixels changed" }
        }
    }

    /**
     * Requires actual dirty hover painting and retained semantics for an unchanged selection.
     */
    internal fun verifyHover() {
        val previous = checkNotNull(lastFrame)
        val next = hover()
        check(next.semantics.single().semantics === previous.semantics.single().semantics)
        check(state.value === original)
        checkNotNull(monitor).verify(WorkExpectation(exact = mapOf(UiRenderMetric.Paint.name to 1L, UiRenderMetric.Semantics.name to 0L)))
    }

    /**
     * Requires both selected-label consumers after a real validated publication.
     */
    internal fun verifyWrite() {
        write()
        check(state.value === alternate)
        check(notifications == 1)
        checkNotNull(monitor).verify(WorkExpectation(exact = mapOf(UiRenderMetric.Paint.name to 1L, UiRenderMetric.Semantics.name to 1L)))
    }

    /**
     * Admits the independent sequence, including no-benefit, failure, reentry and terminal controls.
     */
    @Suppress("CyclomaticComplexMethod") // Every frozen independent control keeps its typed state, action, work and terminal oracle explicit.
    internal fun verifyControl() {
        val previous = lastFrame
        val frame = control()
        if (control == CycleSelectionControl.CleanRetainedFrame) {
            check(frame === previous)
            checkNotNull(monitor).verify(WorkExpectation(exact = mapOf(UiRenderMetric.Paint.name to 0L, UiRenderMetric.Semantics.name to 0L)))
        }
        if (control in setOf(CycleSelectionControl.DisabledIgnoredInput, CycleSelectionControl.IgnoredPointerButton, CycleSelectionControl.ZeroScroll, CycleSelectionControl.EqualDistinctNoOpWrite, CycleSelectionControl.InvalidOutsideValueRejected)) {
            check(notifications == 0 && actionValues.isEmpty())
        }
        if (control == CycleSelectionControl.ForwardWrap) check(state.value === choices.first())
        if (control == CycleSelectionControl.BackwardWrap) check(state.value === choices.last())
        val actionIndex =
            when (control) {
                CycleSelectionControl.ForwardWrap -> 0

                CycleSelectionControl.BackwardWrap, CycleSelectionControl.ScrollBackward, CycleSelectionControl.KeyLeft -> choices.lastIndex

                CycleSelectionControl.PointerPrimaryCycle, CycleSelectionControl.ScrollForward,
                CycleSelectionControl.KeyRight, CycleSelectionControl.KeySpace, CycleSelectionControl.KeyEnter,
                -> 1

                else -> null
            }
        if (actionIndex != null) {
            check(state.value === choices[actionIndex])
            check(actionValues.single() === choices[actionIndex])
            check(notifications == 1 && events.map { it.first } == listOf(Event.Observer, Event.Action))
        }
        if (control in setOf(CycleSelectionControl.ExternalSelectedWrite, CycleSelectionControl.LabelsSameTextDifferentValues, CycleSelectionControl.EmptyLabels)) {
            check(state.value === alternate && notifications == 1 && actionValues.isEmpty())
        }
        if (control == CycleSelectionControl.LaterObserverReentersSelection) {
            check(state.value === choices.last() && notifications == 2)
            check(events.map { it.first } == listOf(Event.Observer, Event.LaterObserver, Event.Observer, Event.LaterObserver))
        }
        if (control == CycleSelectionControl.ActionCallbackReentersSelection) {
            check(state.value === choices.last())
            check(actionValues.single() === alternate)
            check(events.map { it.first } == listOf(Event.Observer, Event.Action, Event.Observer))
        }
        if (control == CycleSelectionControl.DisposeCloseRelease) {
            check(closed)
            check(released.isNotEmpty())
            repeat(16) { System.gc() }
            check(released.all { it.get() == null }) { "A closed reachable host retained old state or option values" }
        }
    }

    /**
     * Releases subscriptions, hosts and fixture-owned references; repeated close has no additional effect.
     */
    override fun close() {
        if (closed) return
        closed = true
        try {
            monitor?.close()
            currentHost.close()
            currentHost.close()
            aliasHost?.close()
            later?.close()
            observer?.close()
        } finally {
            monitor = null
            observer = null
            later = null
            aliasHost = null
            aliasState = null
            selection = null
            replacement = null
            settings = null
            values = null
            original = null
            alternate = null
            profile = null
            events.clear()
            actionValues.clear()
        }
    }

    private class Choice(
        val id: Int,
        private val matches: Set<Int> = setOf(id),
    ) {
        override fun equals(other: Any?): Boolean = this === other || (other is Choice && other.id in matches)

        override fun hashCode(): Int = id
    }

    private enum class Event { Observer, LaterObserver, Action }

    private class ObserverFailure : IllegalArgumentException("frozen earlier observer")
}
