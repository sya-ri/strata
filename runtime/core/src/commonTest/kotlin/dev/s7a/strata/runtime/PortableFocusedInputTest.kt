package dev.s7a.strata.runtime

import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.input.FocusEvent
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.KeyboardModifiers
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.ModifierElement
import dev.s7a.strata.modifier.ModifierNodeType
import dev.s7a.strata.modifier.initialFocus
import dev.s7a.strata.modifier.onFocusChanged
import dev.s7a.strata.modifier.onKeyEvent
import dev.s7a.strata.modifier.onKeyPress
import dev.s7a.strata.modifier.onTextInput
import dev.s7a.strata.modifier.size
import dev.s7a.strata.node.ClipChildrenNode
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.FocusTargetNode
import dev.s7a.strata.node.KeyboardInputNode
import dev.s7a.strata.node.ModifierNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.node.TextInputNode
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.mutableStateOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Checks focused dispatch snapshots and cyclic traversal independently of platform adapters on JVM and JavaScript.
 * Artificial retained mutations test snapshot membership even though public tree operations reject callback reentry.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class PortableFocusedInputTest {
    @Test
    fun everyProtocolPreservesInnerToOwnerOrderAndStopsAtFirstConsumption() {
        for (count in listOf(0, 4, 16)) {
            val order = (0 until count).reversed().toList() + count
            for (protocol in Protocol.entries) {
                for (stop in listOf(-1, 0, order.lastIndex).distinct()) {
                    val observed = ArrayList<Int>()
                    val fixture =
                        focused(count) { id ->
                            observed += id
                            if (stop == observed.lastIndex) InputResult.Consumed else InputResult.Ignored
                        }
                    val token = fixture.pipeline.textInputFocus
                    val expected = if (stop < 0) order else order.take(stop + 1)

                    assertEquals(if (stop < 0) InputResult.Ignored else InputResult.Consumed, dispatch(fixture, protocol))
                    assertEquals(expected, observed)
                    assertSame(token, fixture.pipeline.textInputFocus)
                    fixture.pipeline.releaseRetainedReferences()
                    assertEquals(InputResult.Ignored, dispatch(fixture, protocol))
                    assertNull(fixture.pipeline.textInputFocus)
                }
            }
        }
    }

    @Test
    fun callbackMutationDoesNotChangeTheCapturedChainMembership() {
        for (protocol in Protocol.entries) {
            val observed = ArrayList<Int>()
            lateinit var fixture: Fixture
            fixture =
                focused(4) { id ->
                    observed += id
                    fixture.root.modifiers.clear()
                    InputResult.Ignored
                }

            assertEquals(InputResult.Ignored, dispatch(fixture, protocol))
            assertEquals(listOf(3, 2, 1, 0, 4), observed)
            fixture.pipeline.releaseRetainedReferences()
        }
    }

    @Test
    fun callbackFailuresEscapeUnchangedWithoutCallingLaterMembers() {
        for (protocol in Protocol.entries) {
            val primary = IllegalArgumentException("focused callback")
            val observed = ArrayList<Int>()
            val fixture =
                focused(4) { id ->
                    observed += id
                    throw primary
                }

            assertSame(primary, assertFailsWith<IllegalArgumentException> { dispatch(fixture, protocol) })
            assertEquals(listOf(3), observed)
            fixture.pipeline.releaseRetainedReferences()
            assertNull(fixture.pipeline.textInputFocus)
        }
    }

    @Test
    fun traversalWrapsAtBothEndsAndKeepsDisabledOwnersAsAnchors() {
        for (reverse in listOf(false, true)) {
            for (start in listOf(0, 1, 2)) {
                val transitions = ArrayList<Pair<Int, Boolean>>()
                val nodes = List(3) { id -> ProbeNode(initial = id == start, changed = { transitions += id to it }) }
                val fixture = owners(nodes)
                nodes[start].accepting = false
                transitions.clear()
                val expected = if (reverse) (start + 2) % 3 else (start + 1) % 3

                assertEquals(InputResult.Consumed, tab(fixture, reverse))
                assertEquals(listOf(start to false, expected to true), transitions)
                fixture.pipeline.releaseRetainedReferences()
            }
        }
    }

    @Test
    fun missingFocusStartsAtTheDirectionalEndAndSingleTargetDoesNotRepeatTransitions() {
        for (reverse in listOf(false, true)) {
            val transitions = ArrayList<Pair<Int, Boolean>>()
            val fixture = owners(List(3) { id -> ProbeNode(changed = { transitions += id to it }) })
            val expected = if (reverse) 2 else 0
            assertEquals(InputResult.Consumed, tab(fixture, reverse))
            assertEquals(listOf(expected to true), transitions)
            fixture.pipeline.releaseRetainedReferences()

            val singleTransitions = ArrayList<Boolean>()
            val single = owners(listOf(ProbeNode(changed = singleTransitions::add)))
            repeat(3) { assertEquals(InputResult.Consumed, tab(single, reverse)) }
            assertEquals(listOf(true), singleTransitions)
            single.pipeline.clear()
            single.pipeline.clear()
            assertEquals(listOf(true, false), singleTransitions)
            assertEquals(InputResult.Ignored, single.pipeline.dispatchTextInput(character))
        }
    }

    @Test
    fun ignoredKeyboardCallbackChangesAcceptanceBeforeTraversalEvaluatesCandidates() {
        for (reverse in listOf(false, true)) {
            val transitions = ArrayList<Pair<Int, Boolean>>()
            val nodes = List(3) { id -> ProbeNode(initial = id == 0, changed = { transitions += id to it }) }
            val firstCandidate = if (reverse) 2 else 1
            val secondCandidate = if (reverse) 1 else 2
            val fixture = owners(nodes)
            nodes[0].callback = {
                nodes[firstCandidate].accepting = false
                InputResult.Ignored
            }
            transitions.clear()

            assertEquals(InputResult.Consumed, tab(fixture, reverse))
            assertEquals(listOf(0 to false, secondCandidate to true), transitions)
            fixture.pipeline.releaseRetainedReferences()
        }
    }

    @Test
    fun candidateAcceptanceIsReadLazilyAndOwnerDiscoveryRemainsASnapshot() {
        val transitions = ArrayList<Pair<Int, Boolean>>()
        val nodes = List(4) { id -> ProbeNode(initial = id == 0, changed = { transitions += id to it }) }
        val fixture = owners(nodes)
        var firstRead = true
        nodes[1].readAcceptance = {
            if (firstRead) {
                firstRead = false
                nodes[2].accepting = true
                fixture.root.children.removeAt(2)
            }
            false
        }
        nodes[2].accepting = false
        transitions.clear()

        assertEquals(InputResult.Consumed, tab(fixture, reverse = false))
        assertEquals(listOf(0 to false, 2 to true), transitions)
        fixture.pipeline.releaseRetainedReferences()
    }

    @Test
    fun traversalSkipsUnplacedOffscreenAndClippedTargetsButAllowsVisibleOverflow() {
        val transitions = ArrayList<Pair<Int, Boolean>>()
        val nodes = List(5) { id -> ProbeNode(initial = id == 0, changed = { transitions += id to it }) }
        val fixture = owners(nodes)
        val entries = fixture.root.children
        entries[1].placed = false
        entries[2].bounds = IntRect(Int.MAX_VALUE - 2, 0, Int.MAX_VALUE, 2)
        val clip = retained(ClipProbe()).also { it.bounds = IntRect(0, 0, 2, 2) }
        entries[3].parent = clip
        entries[3].bounds = IntRect(3, 0, 5, 2)
        val overflow = retained(ProbeNode(accepting = false)).also { it.bounds = IntRect(0, 0, 2, 2) }
        entries[4].parent = overflow
        entries[4].bounds = IntRect(3, 0, 5, 2)
        transitions.clear()

        assertEquals(InputResult.Consumed, tab(fixture, reverse = false))
        assertEquals(listOf(0 to false, 4 to true), transitions)
        fixture.pipeline.releaseRetainedReferences()
    }

    @Test
    fun reorderedAndRemovedOwnersUseTheCurrentSnapshotAndReleaseOldFocus() {
        val transitions = ArrayList<Pair<Int, Boolean>>()
        val nodes = List(3) { id -> ProbeNode(initial = id == 0, changed = { transitions += id to it }) }
        val fixture = owners(nodes)
        val first = fixture.root.children.removeAt(0)
        fixture.root.children.add(1, first)
        fixture.pipeline.layoutCommitted(fixture.root)
        transitions.clear()

        assertEquals(InputResult.Consumed, tab(fixture, reverse = false))
        assertEquals(listOf(0 to false, 2 to true), transitions)
        nodes[0].accepting = false
        fixture.root.children.removeAt(2)
        fixture.pipeline.layoutCommitted(fixture.root)
        assertNull(fixture.pipeline.textInputFocus)
        assertEquals(InputResult.Ignored, fixture.pipeline.dispatchTextInput(character))
        fixture.pipeline.releaseRetainedReferences()
    }

    @Test
    fun ignoredTabPublishesStateWithoutApplyingNewDeclarationsDuringDispatch() {
        val disabled = mutableStateOf(false)
        val observed = ArrayList<FocusEvent>()
        var evaluations = 0
        val session =
            createRuntimeUiSession {
                evaluations += 1
                evaluateComponentTree {
                    Row {
                        Spacer(
                            modifier =
                                Modifier.Empty
                                    .size(10, 10)
                                    .initialFocus()
                                    .onKeyPress {
                                        disabled.value = true
                                        InputResult.Ignored
                                    },
                        )
                        Spacer(
                            modifier =
                                if (disabled.value) {
                                    Modifier.Empty.size(10, 10)
                                } else {
                                    Modifier.Empty.size(10, 10).onFocusChanged { observed += it }
                                },
                        )
                    }
                }
            }
        try {
            session.attach()
            val constraints = Constraints.fixed(20, 10)
            session.frame(constraints)

            assertEquals(InputResult.Consumed, session.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Tab, 0)))
            assertEquals(1, evaluations)
            assertEquals(listOf(FocusEvent.Gained), observed)
            session.frame(constraints)
            assertEquals(2, evaluations)
            assertEquals(listOf(FocusEvent.Gained), observed)
            session.resetInputState()
        } finally {
            session.close()
        }
    }

    @Test
    fun realSessionFailuresPreserveIdentityAndDisposeBeforeReturningOnEveryProtocol() {
        for (protocol in Protocol.entries) {
            val primary = IllegalArgumentException("focused delivery")
            val probe = TestProbe()
            var disposals = 0
            val modifier =
                Modifier.Empty
                    .initialFocus()
                    .onKeyEvent { throw primary }
                    .onTextInput { throw primary }
            val session =
                createRuntimeUiSession {
                    probe.element(
                        TestProbe.ProbeId("focused"),
                        modifier = modifier,
                        onDispose = { disposals += 1 },
                    )
                }
            session.attach()
            session.frame(Constraints.fixed(10, 10))

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    when (protocol) {
                        Protocol.Press -> session.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Enter, 7))
                        Protocol.Release -> session.dispatchKeyboard(KeyboardEvent.Release(KeyCode.Enter, 7))
                        Protocol.Character -> session.dispatchTextInput(character)
                        Protocol.Preedit -> session.dispatchTextInput(TextInputEvent.Preedit("compose", 3, listOf("com", "pose"), 1))
                    }
                }
            assertSame(primary, failure)
            assertEquals(1, disposals)
            assertFailsWith<IllegalStateException> { session.dispatchTextInput(character) }
            session.close()
            assertEquals(1, disposals)
        }
    }

    private fun focused(
        modifierCount: Int,
        callback: (Int) -> InputResult,
    ): Fixture {
        val root = retained(ProbeNode(initial = true, callback = { callback(modifierCount) }))
        repeat(modifierCount) { id ->
            val node = InputModifier { callback(id) }
            val entry = RetainedModifier(InputElement(node), node).also { it.placed = true }
            root.modifiers += entry
        }
        root.modifiers.forEachIndexed { index, modifier ->
            modifier.virtualChild = root.modifiers.getOrNull(index + 1) ?: root
        }
        return Fixture(root, FocusedInputPipeline().also { it.layoutCommitted(root) })
    }

    private fun owners(nodes: List<ProbeNode>): Fixture {
        val root = retained(ProbeNode(accepting = false))
        nodes.forEach { node -> root.children += retained(node).also { it.parent = root } }
        return Fixture(root, FocusedInputPipeline().also { it.layoutCommitted(root) })
    }

    private fun retained(node: ProbeNode): RetainedNode =
        RetainedNode(ProbeElement(node), node, logicalParent = null).also {
            it.placed = true
            it.bounds = IntRect(0, 0, 10, 10)
        }

    private fun dispatch(
        fixture: Fixture,
        protocol: Protocol,
    ): InputResult =
        when (protocol) {
            Protocol.Press -> fixture.pipeline.dispatchKeyboard(fixture.root, KeyboardEvent.Press(KeyCode.Enter, 7))
            Protocol.Release -> fixture.pipeline.dispatchKeyboard(fixture.root, KeyboardEvent.Release(KeyCode.Enter, 7))
            Protocol.Character -> fixture.pipeline.dispatchTextInput(character)
            Protocol.Preedit -> fixture.pipeline.dispatchTextInput(TextInputEvent.Preedit("compose", 3, listOf("com", "pose"), 1))
        }

    private fun tab(
        fixture: Fixture,
        reverse: Boolean,
    ): InputResult = fixture.pipeline.dispatchKeyboard(fixture.root, KeyboardEvent.Press(KeyCode.Tab, 0, KeyboardModifiers(shift = reverse)))

    private enum class Protocol { Press, Release, Character, Preedit }

    private data class Fixture(
        val root: RetainedNode,
        val pipeline: FocusedInputPipeline,
    )

    private open class ProbeNode(
        var accepting: Boolean = true,
        private val initial: Boolean = false,
        var callback: () -> InputResult = { InputResult.Ignored },
        private val changed: (Boolean) -> Unit = {},
    ) : Node(),
        FocusTargetNode,
        KeyboardInputNode,
        TextInputNode {
        var readAcceptance: (() -> Boolean)? = null

        override val acceptsFocus: Boolean
            get() = readAcceptance?.invoke() ?: accepting

        override val requestsInitialFocus: Boolean
            get() = initial

        override val requiresTextInput: Boolean
            get() = true

        override fun onFocusChanged(focused: Boolean) = changed(focused)

        override fun onKeyboardEvent(event: KeyboardEvent): InputResult = callback()

        override fun onTextInput(event: TextInputEvent): InputResult = callback()
    }

    private class ClipProbe :
        ProbeNode(accepting = false),
        ClipChildrenNode

    private class InputModifier(
        private val callback: () -> InputResult,
    ) : ModifierNode(),
        KeyboardInputNode,
        TextInputNode {
        override fun onKeyboardEvent(event: KeyboardEvent): InputResult = callback()

        override fun onTextInput(event: TextInputEvent): InputResult = callback()
    }

    private class ProbeElement(
        val node: ProbeNode,
    ) : Element(ElementIdentity.Positional, probeType)

    private data class InputElement(
        val node: InputModifier,
    ) : ModifierElement {
        override val type: ModifierNodeType<*, *>
            get() = inputType
    }

    private companion object {
        val character = TextInputEvent.Character(0x1F642)
        val probeType =
            ElementType(
                elementClass = ProbeElement::class,
                nodeClass = ProbeNode::class,
                validateLocal = {},
                createNode = ProbeElement::node,
                updateNode = { _, _, _ -> DirtyMask.None },
            )
        val inputType =
            ModifierNodeType(
                elementClass = InputElement::class,
                nodeClass = InputModifier::class,
                validateLocal = {},
                createNode = InputElement::node,
                updateNode = { _, _, _ -> DirtyMask.None },
            )
    }
}
