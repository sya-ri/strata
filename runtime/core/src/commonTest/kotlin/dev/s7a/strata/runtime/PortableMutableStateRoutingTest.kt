@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.onKeyPress
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.semantics
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.runtime.spi.RuntimeUiSession
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.MutableState
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.text.UiText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Public retained-region fan-out, lifecycle and operation controls shared by JVM and JavaScript.
 */
internal class PortableMutableStateRoutingTest {
    @Test
    fun warmEqualAndBatchedUnequalAssignmentsKeepRootAndRegionCutoffsAndOldFrames() {
        Scene().use { scene ->
            val initial = scene.frame()
            repeat(64) { scene.value.value = Tone.First }
            assertSame(initial, scene.frame())
            assertEquals(1, scene.roots)
            assertTrue(scene.evaluations.all { it == 1 })
            repeat(63) { scene.value.value = if (it % 2 == 0) Tone.Second else Tone.First }
            scene.value.value = Tone.Second
            assertTrue(scene.evaluations.all { it == 1 })
            val changed = scene.frame()
            scene.verify(changed, List(128) { Tone.Second })
            assertTrue(scene.evaluations.all { it == 2 })
            assertEquals(1, scene.roots)
            scene.verify(initial, List(128) { Tone.First })
            assertNotSame(initial, changed)
            assertSame(changed, scene.frame())
        }
    }

    @Test
    fun conditionalDependenciesAndKeyedMovementReplaceReadsWithoutLosingRetainedInputOrFocus() {
        Scene().use { scene ->
            scene.frame()
            scene.branch.value = Branch.MixedReverse
            scene.verify(scene.frame(), (127 downTo 0).map { if (it % 2 == 0) Tone.First else Tone.Second })
            val before = scene.evaluations.toList()
            scene.value.value = Tone.Second
            scene.frame()
            scene.evaluations.forEachIndexed { index, calls ->
                assertEquals(before[index] + if (index % 2 == 0) 1 else 0, calls)
            }
            assertEquals(InputResult.Consumed, scene.session.dispatchPointer(PointerEvent.Press(IntOffset.Zero, PointerButton.Primary)))
            assertEquals(0, scene.winner)
            assertEquals(Tone.First, scene.value.value)
            scene.frame()
            assertEquals(InputResult.Consumed, scene.session.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Enter, 0)))
            assertEquals(0, scene.keyboardWinner)
            assertEquals(Tone.Second, scene.value.value)
            scene.frame()
            scene.branch.value = Branch.Empty
            val empty = scene.frame()
            assertTrue(empty.drawCommands.isEmpty())
            val counts = scene.evaluations.toList()
            scene.value.value = Tone.First
            scene.other.value = Tone.First
            assertSame(empty, scene.frame())
            assertEquals(counts, scene.evaluations.toList())
        }
    }

    @Test
    fun independentScreensDetachRetainsDependenciesAndClosingOneKeepsTheOtherUsable() {
        val first = Scene()
        val second = Scene(first.value)
        try {
            first.frame()
            second.frame()
            first.value.value = Tone.First
            first.session.detach()
            first.value.value = Tone.Second
            second.verify(second.frame(), List(128) { Tone.Second })
            assertTrue(first.evaluations.all { it == 1 })
            first.session.attach()
            first.verify(first.frame(), List(128) { Tone.Second })
            assertTrue(first.evaluations.all { it == 2 })
            first.close()
            first.value.value = Tone.First
            second.verify(second.frame(), List(128) { Tone.First })
            assertTrue(second.evaluations.all { it == 3 })
        } finally {
            first.close()
            second.close()
        }
        first.value.value = Tone.Second
        assertEquals(Tone.Second, first.value.value)
    }

    @Test
    fun aLaterSourcePublicationFromContentWaitsForTheFollowingFrameCutoff() {
        Scene().use { scene ->
            scene.frame()
            scene.value.value = Tone.First
            scene.afterRead = {
                scene.afterRead = null
                scene.source.publish(2)
            }
            scene.source.publish(1)
            scene.verify(scene.frame(), List(128) { Tone.First })
            assertTrue(scene.sourceValues.all { it == 1 })
            scene.frame()
            assertTrue(scene.sourceValues.all { it == 2 })
            assertTrue(scene.evaluations.all { it == 3 })
            assertEquals(1, scene.roots)
            assertEquals(1, scene.source.acquired)
        }
    }

    @Test
    fun failedRegionEvaluationAndPipelineFailureReleaseDependenciesBeforeStateReuse() {
        val scene = Scene()
        val failure = IllegalArgumentException("Region failed")
        scene.frame()
        scene.value.value = Tone.First
        scene.afterRead = { throw failure }
        scene.value.value = Tone.Second
        assertSame(failure, assertFailsWith<IllegalArgumentException> { scene.frame() })
        assertEquals(scene.source.acquired, scene.source.released)
        scene.value.value = Tone.First
        scene.close()

        val state = mutableStateOf(0)
        val probe = TestProbe()
        val pipelineFailure = IllegalStateException("Paint failed")
        val session =
            createRuntimeUiSession {
                state.value
                probe.element(TestProbe.ProbeId("pipeline"), onPaint = { throw pipelineFailure })
            }
        session.attach()
        state.value = 0
        assertSame(pipelineFailure, assertFailsWith<IllegalStateException> { session.frame(Constraints.fixed(2, 1)) })
        state.value = 1
        session.close()
        assertEquals(1, state.value)
    }

    @Test
    fun aNestedIndependentActionCannotBypassTheOuterFramePhaseAndBothScreensRemainUsable() {
        val state = mutableStateOf(0)
        val outerProbe = TestProbe()
        val innerProbe = TestProbe()
        var nestedChecks = 0
        val inner =
            createRuntimeUiSession {
                state.value
                innerProbe.root(emptyList())
            }
        val outer =
            createRuntimeUiSession {
                state.value
                outerProbe.element(
                    TestProbe.ProbeId("outer"),
                    onPaint = {
                        inner.dispatchAction {
                            assertFailsWith<IllegalStateException> { state.value = 0 }
                            nestedChecks += 1
                        }
                    },
                )
            }
        try {
            inner.attach()
            outer.attach()
            inner.frame(Constraints.fixed(2, 1))
            state.value = 0
            outer.frame(Constraints.fixed(2, 1))
            assertEquals(1, nestedChecks)
            state.value = 1
            inner.frame(Constraints.fixed(2, 1))
            outer.frame(Constraints.fixed(3, 1))
            assertEquals(2, nestedChecks)
            assertEquals(1, state.value)
        } finally {
            outer.close()
            inner.close()
        }
        state.value = 2
    }

    @Test
    fun everyDeclarativeAndCleanupPhaseRejectsWarmAndUnobservedWritesWhileInputAllowsThem() {
        val observed = mutableStateOf(0)
        val unobserved = mutableStateOf(0)
        var rejections = 0
        val reject = {
            assertFailsWith<IllegalStateException> { observed.value = 0 }
            assertFailsWith<IllegalStateException> { unobserved.value = 0 }
            rejections += 1
        }
        val probe = TestProbe()
        val session =
            createRuntimeUiSession {
                observed.value
                reject()
                probe.element(
                    TestProbe.ProbeId("phases"),
                    onAttach = reject,
                    onDetach = reject,
                    onUpdate = reject,
                    onMeasure = reject,
                    onLayout = reject,
                    onPaint = reject,
                    onSemantics = reject,
                    onDispose = reject,
                    onInput = {
                        observed.value += 1
                        unobserved.value += 1
                    },
                )
            }
        session.attach()
        session.frame(Constraints.fixed(2, 1))
        observed.value = 0
        assertEquals(InputResult.Consumed, session.dispatchPointer(PointerEvent.Move(IntOffset.Zero)))
        session.frame(Constraints.fixed(2, 1))
        session.detach()
        session.attach()
        session.frame(Constraints.fixed(2, 1))
        session.close()
        assertTrue(10 <= rejections)
        assertEquals(1, observed.value)
        assertEquals(1, unobserved.value)
        observed.value = 2
        unobserved.value = 2
    }

    /**
     * Typed immutable visible values with literal geometry and color expectations.
     */
    private enum class Tone(
        val width: Int,
        val color: ArgbColor,
        val label: UiText,
    ) {
        First(16, ArgbColor(0xff234567.toInt()), UiText.literal("First")),
        Second(17, ArgbColor(0xff789abc.toInt()), UiText.literal("Second")),
    }

    /**
     * Declared membership controls, separate from the caller state whose routing is exercised.
     */
    private enum class Branch { All, MixedReverse, Empty }

    /**
     * Real independently observed regions; the fixed source supplies only Observe's required argument.
     */
    private class Scene(
        val value: MutableState<Tone> = mutableStateOf(Tone.First),
    ) : AutoCloseable {
        val other = mutableStateOf(Tone.Second)
        val branch = mutableStateOf(Branch.All)
        val source = Source()
        val evaluations = IntArray(128)
        val sourceValues = IntArray(128)
        var roots = 0
        var winner = -1
        var keyboardWinner = -1
        var afterRead: (() -> Unit)? = null
        val session: RuntimeUiSession =
            createRuntimeUiSession {
                roots += 1
                val currentBranch = branch.value
                val indices = if (currentBranch == Branch.MixedReverse) 127 downTo 0 else 0 until 128
                evaluateComponentTree {
                    Stack {
                        if (currentBranch != Branch.Empty) {
                            for (index in indices) {
                                Observe(source, key = ElementKey(index)) { revision ->
                                    evaluations[index] += 1
                                    sourceValues[index] = revision
                                    val tone = if (currentBranch == Branch.MixedReverse && index % 2 == 1) other.value else value.value
                                    afterRead?.invoke()
                                    Spacer(
                                        modifier =
                                            Modifier.Empty
                                                .size(tone.width, 16)
                                                .background(tone.color)
                                                .semantics(Semantics(label = tone.label))
                                                .onPress {
                                                    winner = index
                                                    value.value = if (value.value == Tone.First) Tone.Second else Tone.First
                                                }.onKeyPress {
                                                    keyboardWinner = index
                                                    value.value = if (value.value == Tone.First) Tone.Second else Tone.First
                                                    InputResult.Consumed
                                                },
                                    )
                                }
                            }
                        }
                    }
                }
            }

        init {
            session.attach()
        }

        fun frame(): RuntimeUiFrame = session.frame(Constraints.fixed(20, 16))

        fun verify(
            frame: RuntimeUiFrame,
            expected: List<Tone>,
        ) {
            assertEquals(expected.map { DrawCommand.FillRectangle(IntRect(0, 0, it.width, 16), it.color) }, frame.drawCommands)
            assertEquals(expected.map { it.label }, frame.semantics.map { it.semantics.label })
            assertEquals(expected.map { IntRect(0, 0, it.width, 16) }, frame.semantics.map { it.bounds })
        }

        override fun close() {
            afterRead = null
            session.close()
            assertEquals(source.acquired, source.released)
        }
    }

    /**
     * Owner-thread publisher with explicit subscription and revision accounting.
     */
    private class Source : StateSource<Int> {
        private var snapshot = StateSnapshot(StateRevision(0), 0)
        private val observers = LinkedHashSet<(StateSnapshot<Int>) -> Unit>()
        var acquired = 0
            private set
        var released = 0
            private set

        override fun subscribe(observer: (StateSnapshot<Int>) -> Unit): StateSubscription<Int> {
            check(observers.add(observer))
            acquired += 1
            return StateSubscription(snapshot) {
                check(observers.remove(observer))
                released += 1
            }
        }

        fun publish(value: Int) {
            snapshot = StateSnapshot(StateRevision(snapshot.revision.value + 1), value)
            observers.toList().forEach { it(snapshot) }
        }
    }
}
