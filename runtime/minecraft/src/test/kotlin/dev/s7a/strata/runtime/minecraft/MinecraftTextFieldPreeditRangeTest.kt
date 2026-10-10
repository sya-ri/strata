package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.TextField
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.initialFocus
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.FocusTargetNode
import dev.s7a.strata.node.KeyboardInputNode
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.PointerInputNode
import dev.s7a.strata.node.TextInputNode
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.minecraft.font.FontTestReferences
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiDefinition
import java.lang.ref.Reference
import java.lang.ref.WeakReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Proves input-time relative metadata, atomic current-input ownership and every existing composition cutoff.
 * Reflection observes private retained state without adding a production API or observer.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftTextFieldPreeditRangeTest {
    @Test
    fun derivesBeforePaintAndRetainsExactlyOneRangeAcrossAppearancePaints() {
        Fixture().use { fixture ->
            val event = TextInputEvent.Preedit("BCDE", 4, listOf("", "BC", "", "DE", ""), 3)
            assertEquals(InputResult.Consumed, fixture.input(event))
            val composition = checkNotNull(fixture.composition())
            val range = checkNotNull(fixture.member(composition, "focusedRange"))
            assertEquals(2, fixture.member(range, "start"))
            assertEquals(2, fixture.member(range, "length"))
            repeat(100) {
                fixture.paint()
                assertSame(composition, fixture.composition())
                assertSame(range, fixture.member(composition, "focusedRange"))
            }
            assertEquals(DirtyMask.of(DirtyPhase.Paint), fixture.update(appearanceColor = 0xFF203020.toInt()))
            fixture.paint()
            assertSame(composition, fixture.composition())
            fixture.dirty.clear()
            assertEquals(InputResult.Consumed, fixture.input(TextInputEvent.Preedit("BCDE", 4, listOf("", "BC", "", "DE", ""), 3)))
            assertSame(composition, fixture.composition())
            assertEquals(emptyList<DirtyMask>(), fixture.dirty)
        }
    }

    @Test
    fun validatesEveryBlockBeforePublishingAndRequiresCompleteExactAgreement() {
        Fixture().use { fixture ->
            val valid = TextInputEvent.Preedit("B", 1, listOf("B"), 0)
            fixture.input(valid)
            val previous = fixture.composition()
            listOf(
                TextInputEvent.Preedit("B", 1, listOf("\n"), 0),
                TextInputEvent.Preedit("B", 1, listOf("\uD800"), 0),
                TextInputEvent.Preedit("\uD800", 1, listOf("B"), 0),
                TextInputEvent.Preedit("🙂", 1, listOf("🙂"), 0),
                TextInputEvent.Preedit("B", 1, listOf("B", "\n"), -1),
            ).forEach { rejected ->
                fixture.dirty.clear()
                assertEquals(InputResult.Ignored, fixture.input(rejected))
                assertSame(previous, fixture.composition())
                assertEquals(emptyList<DirtyMask>(), fixture.dirty)
            }
            listOf(
                TextInputEvent.Preedit("BC", 2, listOf("B"), 0),
                TextInputEvent.Preedit("BC", 2, listOf("BD"), 0),
                TextInputEvent.Preedit("BC", 2, listOf("BC", ""), -1),
            ).forEach { noUnderline ->
                assertEquals(InputResult.Consumed, fixture.input(noUnderline))
                assertNull(fixture.member(checkNotNull(fixture.composition()), "focusedRange"))
                fixture.paint()
            }
            assertEquals(InputResult.Consumed, fixture.input(TextInputEvent.Preedit("BC", 2, listOf("B", "", "C"), 1)))
            val emptyRange = checkNotNull(fixture.member(checkNotNull(fixture.composition()), "focusedRange"))
            assertEquals(1, fixture.member(emptyRange, "start"))
            assertEquals(0, fixture.member(emptyRange, "length"))
        }
    }

    @Test
    fun replacementAndAllCutoffsReleasePreviousEventAndDetachedBlockStrings() {
        Cutoff.entries.forEach { cutoff ->
            Fixture().use { fixture ->
                val references = ephemeralInput(fixture::input)
                assertNotNull(fixture.composition())
                when (cutoff) {
                    Cutoff.Replacement -> fixture.input(TextInputEvent.Preedit("C", 1, listOf("C"), 0))
                    Cutoff.Empty -> fixture.input(TextInputEvent.Preedit("", 0, emptyList(), -1))
                    Cutoff.Focus -> (fixture.node as FocusTargetNode).onFocusChanged(false)
                    Cutoff.Disable -> fixture.update(enabled = false)
                    Cutoff.State -> fixture.update(state = TextFieldState("D"))
                    Cutoff.ExternalState -> fixture.state.value = "D"
                    Cutoff.Cursor -> (fixture.node as KeyboardInputNode).onKeyboardEvent(KeyboardEvent.Press(KeyCode.Home, 0))
                    Cutoff.Character -> (fixture.node as TextInputNode).onTextInput(TextInputEvent.Character('D'.code))
                    Cutoff.Pointer -> (fixture.node as PointerInputNode).onPointerEvent(PointerEvent.Press(IntOffset(4, 10), PointerButton.Primary), IntOffset(4, 10))
                    Cutoff.Detach -> (fixture.node as LifecycleNode).detach()
                    Cutoff.Dispose -> (fixture.node as LifecycleNode).dispose()
                }
                if (cutoff != Cutoff.Replacement) assertNull(fixture.composition())
                FontTestReferences.assertCollected(*references.toTypedArray())
                Reference.reachabilityFence(fixture.node)
            }
        }
    }

    @Test
    fun productionHostFailureReleasesCompositionWhileFailedHostRemainsReachable() {
        val width = ReactiveTestSource(80)
        val state = TextFieldState("A")
        val host = createMinecraftUiHost(
            UiDefinition {
                Observe(width) { current -> TextField(state, IntSize(current, 20), modifier = Modifier.Empty.initialFocus()) }
            },
            MinecraftProfileFixture.create(),
        )
        try {
            host.attach()
            host.frame(IntSize(80, 20))
            val references = ephemeralInput(host::dispatchTextInput)
            width.publish(8)
            assertThrows(IllegalArgumentException::class.java) { host.frame(IntSize(80, 20)) }
            FontTestReferences.assertCollected(*references.toTypedArray())
            Reference.reachabilityFence(host)
        } finally {
            host.close()
        }
    }

    private fun ephemeralInput(dispatch: (TextInputEvent.Preedit) -> InputResult): List<WeakReference<*>> {
        val first = CharArray(16) { 'B' }.concatToString()
        val last = CharArray(16) { 'C' }.concatToString()
        val full = first + last
        val event = TextInputEvent.Preedit(full, full.length, listOf(first, last), 1)
        assertEquals(InputResult.Consumed, dispatch(event))
        return listOf(WeakReference(event), WeakReference(full), WeakReference(first), WeakReference(last), WeakReference(event.blocks))
    }

    /**
     * Explicit existing release boundaries; replacement retains only its new immutable input.
     */
    private enum class Cutoff {
        Replacement,
        Empty,
        Focus,
        Disable,
        State,
        ExternalState,
        Cursor,
        Character,
        Pointer,
        Detach,
        Dispose,
    }

    /**
     * Independently bound production node with host-owned renderer and a bounded paint capture.
     */
    private class Fixture : AutoCloseable {
        val state = TextFieldState("A", maxLength = 32767)
        private val renderer = MinecraftProfileImplementation.createTextRenderer(MinecraftProfileFixture.create(), null)
        private val sprite = createDrawImage(IntSize(3, 3), IntArray(9) { 0xFF426789.toInt() })
        private var element = description(state, true, 0xFF426789.toInt())
        val node = element.type.createErased(element)
        val dirty = mutableListOf<DirtyMask>()
        private val release = node.bindRuntime { dirty.add(it) }

        init {
            (node as LifecycleNode).attach()
            (node as FocusTargetNode).onFocusChanged(true)
            dirty.clear()
        }

        /**
         * Delivers input through the actual node and its bound invalidation callback.
         */
        fun input(event: TextInputEvent.Preedit): InputResult = (node as TextInputNode).onTextInput(event)

        /**
         * Observes only the current private composition.
         */
        fun composition(): Any? = member(node, "preedit")

        /**
         * Reads private metadata without introducing a production inspection API.
         */
        fun member(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).also { it.isAccessible = true }.get(owner)

        /**
         * Repaints with current geometry and releases the capture after this call.
         */
        fun paint() {
            val recording = MinecraftTextRecordingScope()
            (node as PaintNode).paint(object : PaintScope by recording { override val size = IntSize(80, 20) })
        }

        /**
         * Applies the production property diff while retaining the same node.
         */
        fun update(state: TextFieldState = this.state, enabled: Boolean = true, appearanceColor: Int = 0xFF426789.toInt()): DirtyMask {
            val next = description(state, enabled, appearanceColor)
            val result = element.type.updateErased(element, next, node)
            element = next
            return result
        }

        private fun description(state: TextFieldState, enabled: Boolean, appearanceColor: Int): Element =
            createMinecraftTextFieldElement(
                sprite, sprite, renderer, ResourceId("minecraft", "default"), state, IntSize(80, 20), enabled, TextStyle.ContainerLabel, Modifier.Empty, null,
                MinecraftTextInputAppearance(sprite, sprite, caretColor = ArgbColor(appearanceColor)),
            )

        override fun close() {
            try {
                (node as LifecycleNode).detach()
                (node as LifecycleNode).dispose()
                release()
            } finally { renderer.close() }
        }
    }
}
