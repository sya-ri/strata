package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.KeyboardModifiers
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.ModifierElement
import dev.s7a.strata.modifier.ModifierNodeType
import dev.s7a.strata.modifier.size
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.FocusTargetNode
import dev.s7a.strata.node.ModifierNode
import dev.s7a.strata.runtime.spi.RuntimeTextInputFocus
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.PreeditEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lwjgl.glfw.GLFW
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.memAddress

/**
 * Verifies the Minecraft-to-common input boundary without loading a client singleton.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class FabricMinecraftInputMappingTest {
    @Test
    fun mapsEverySupportedNativeButton() {
        assertEquals(PointerButton.Primary, mapMinecraftButton(0))
        assertEquals(PointerButton.Secondary, mapMinecraftButton(1))
        assertEquals(PointerButton.Middle, mapMinecraftButton(2))
        assertEquals(PointerButton.Auxiliary(0), mapMinecraftButton(3))
        assertEquals(PointerButton.Auxiliary(1), mapMinecraftButton(4))
        assertEquals(PointerButton.Auxiliary(Int.MAX_VALUE - 3), mapMinecraftButton(Int.MAX_VALUE))
        assertNull(mapMinecraftButton(-1))
    }

    @Test
    fun floorsOnlyFiniteIntegerRangeCoordinates() {
        assertEquals(Int.MIN_VALUE, mapMinecraftCoordinate(Int.MIN_VALUE.toDouble()))
        assertEquals(Int.MAX_VALUE, mapMinecraftCoordinate(Int.MAX_VALUE.toDouble() + 0.75))
        assertEquals(-2, mapMinecraftCoordinate(-1.25))
        assertNull(mapMinecraftCoordinate(Double.NaN))
        assertNull(mapMinecraftCoordinate(Double.POSITIVE_INFINITY))
        assertNull(mapMinecraftCoordinate(Int.MAX_VALUE.toDouble() + 1.0))
        assertEquals(IntOffset(-2, 3), mapMinecraftPosition(-1.25, 3.75))
        assertNull(mapMinecraftPosition(Double.NaN, 3.75))
        assertNull(mapMinecraftPosition(-1.25, Double.NEGATIVE_INFINITY))
    }

    @Test
    fun mapsNativeVerticalScrollIntoCommonDirection() {
        assertEquals(-2.5, mapMinecraftVerticalScroll(2.5))
        assertEquals(2.5, mapMinecraftVerticalScroll(-2.5))
        assertEquals(0.0, mapMinecraftVerticalScroll(-0.0))
        assertEquals(2.5 to -3.5, mapMinecraftScroll(2.5, 3.5))
        assertEquals(null, mapMinecraftScroll(Double.NaN, 1.0))
        assertEquals(null, mapMinecraftScroll(1.0, Double.POSITIVE_INFINITY))
    }

    @Test
    fun mapsOnlyFiniteNativeDragDisplacementWithoutChangingDirection() {
        assertEquals(2.5 to -3.5, mapMinecraftDrag(2.5, -3.5))
        assertEquals(0.0 to -0.0, mapMinecraftDrag(0.0, -0.0))
        assertEquals(null, mapMinecraftDrag(Double.NaN, 1.0))
        assertEquals(null, mapMinecraftDrag(1.0, Double.NEGATIVE_INFINITY))
    }

    @Test
    fun mapsKeyPressReleaseAndEveryModifierBit() {
        val flags =
            GLFW.GLFW_MOD_SHIFT or
                GLFW.GLFW_MOD_CONTROL or
                GLFW.GLFW_MOD_ALT or
                GLFW.GLFW_MOD_SUPER or
                GLFW.GLFW_MOD_CAPS_LOCK or
                GLFW.GLFW_MOD_NUM_LOCK
        val native = KeyEvent(GLFW.GLFW_KEY_ENTER, 17, flags)
        val modifiers = KeyboardModifiers(true, true, true, true, true, true)
        assertEquals(KeyboardEvent.Press(KeyCode.Enter, 17, modifiers), mapMinecraftKeyPress(native))
        assertEquals(KeyboardEvent.Release(KeyCode.Enter, 17, modifiers), mapMinecraftKeyRelease(native))
        assertEquals(KeyCode.Unknown, mapMinecraftKeyPress(KeyEvent(GLFW.GLFW_KEY_UNKNOWN, 0, 0))?.key)
        assertNull(mapMinecraftKeyPress(KeyEvent(GLFW.GLFW_KEY_UNKNOWN - 1, 0, 0)))
    }

    @Test
    fun mapsCommittedUnicodeAndDetachedPreeditSnapshots() {
        assertEquals(TextInputEvent.Character(0x1F642), mapMinecraftCharacter(CharacterEvent(0x1F642)))
        assertNull(mapMinecraftCharacter(CharacterEvent(0xD800)))
        val blocks = arrayListOf("first", "second")
        val mapped = mapMinecraftPreedit(PreeditEvent("composition", 4, blocks, 1))
        blocks.clear()
        assertEquals(TextInputEvent.Preedit("composition", 4, listOf("first", "second"), 1), mapped)
        assertEquals(TextInputEvent.Preedit("", 0, emptyList(), -1), mapMinecraftPreedit(null))
        assertEquals(
            TextInputEvent.Preedit("日本🙂語", 4, listOf("日本", "🙂", "語"), 1),
            mapMinecraftPreedit(PreeditEvent("日本🙂語", 4, listOf("日本", "🙂", "語"), 1)),
        )
    }

    @Test
    fun nativePreeditCallbackConvertsScalarCaretsBeforeTheCommonMapping() {
        MemoryStack.stackPush().use { stack ->
            val characters = stack.ints(0x65E5, 0x1F642, 0x672C)
            val blockSizes = stack.ints(1, 1, 1)
            val boundaries = listOf(0, 1, 3, 4)
            for (caret in boundaries.indices) {
                val native = PreeditEvent.createFromCallback(characters.remaining(), memAddress(characters), blockSizes.remaining(), memAddress(blockSizes), 1, caret)
                assertEquals(
                    TextInputEvent.Preedit("日🙂本", boundaries[caret], listOf("日", "🙂", "本"), 1),
                    mapMinecraftPreedit(native),
                )
            }
        }
    }

    @Test
    fun legacyCharacterCallbacksAssembleOnlyAdjacentSurrogatePairs() {
        val input = FabricMinecraftCharacterInput()
        assertNull(input.accept('\uD83D'))
        assertEquals(TextInputEvent.Character(0x1F642), input.accept('\uDE42'))
        assertNull(input.accept('\uDE42'))
        assertEquals(TextInputEvent.Character('本'.code), input.accept('本'))
        assertNull(input.accept('\uD83D'))
        assertEquals(TextInputEvent.Character('A'.code), input.accept('A'))
        assertNull(input.accept('\uDE42'))
        assertNull(input.accept('\uD83C'))
        assertNull(input.accept('\uD83D'))
        assertEquals(TextInputEvent.Character(0x1F642), input.accept('\uDE42'))
    }

    @Test
    fun legacyCharacterResetDiscardsIncompleteInputAtFocusAndLifecycleBoundaries() {
        val input = FabricMinecraftCharacterInput()
        assertNull(input.accept('\uD83D'))
        input.reset()
        input.reset()
        assertNull(input.accept('\uDE42'))
        assertEquals(TextInputEvent.Character('한'.code), input.accept('한'))
        assertNull(input.accept('\uD83D'))
        assertEquals(TextInputEvent.Character(0x1F642), input.accept('\uDE42'))
    }

    @Test
    fun nativeTextInputFocusResubmitsOnlyWhenTheEditableIntervalChanges() {
        val (first, second) = editableFocusIntervals()
        val transitions = ArrayList<Boolean>()
        val focus = FabricMinecraftTextInputFocus(transitions::add)
        focus.synchronize(null)
        assertFalse(focus.isActive)
        focus.synchronize(first)
        focus.synchronize(first)
        assertTrue(focus.isActive)
        assertEquals(listOf(true), transitions)
        focus.synchronize(second)
        assertEquals(listOf(true, false, true), transitions)
        focus.clear()
        focus.clear()
        focus.synchronize(null)
        assertFalse(focus.isActive)
        assertEquals(listOf(true, false, true, false), transitions)
    }

    @Test
    fun synchronousNativePreeditMayCloseAfterTheRetainedTransactionEnds() {
        val interval = editableFocusIntervals().first
        val transitions = ArrayList<Boolean>()
        var closeCalls = 0
        var navigationCalls = 0
        lateinit var focus: FabricMinecraftTextInputFocus
        val lifecycle =
            FabricScreenLifecycleTransaction.create(
                {},
                {},
                {
                    focus.clear()
                    closeCalls += 1
                },
                {
                    assertFalse(focus.isActive)
                    navigationCalls += 1
                },
            )
        focus =
            FabricMinecraftTextInputFocus { active ->
                transitions += active
                assertEquals(active, focus.isActive)
                if (active) {
                    assertFalse(lifecycle.isActive())
                    lifecycle.run { lifecycle.requestCloseThenNavigate() }
                }
            }
        lifecycle.run {}.also { focus.synchronize(interval) }
        assertEquals(listOf(true, false), transitions)
        assertFalse(focus.isActive)
        assertEquals(1, closeCalls)
        assertEquals(1, navigationCalls)
    }

    @Test
    fun nativeFocusFailureReleasesTheIntervalAndPreservesCleanupFailure() {
        val interval = editableFocusIntervals().first
        val primary = IllegalArgumentException("preedit callback")
        val cleanup = IllegalStateException("native loss")
        val transitions = ArrayList<Boolean>()
        val focus =
            FabricMinecraftTextInputFocus { active ->
                transitions += active
                if (active) throw primary else throw cleanup
            }
        assertSame(primary, assertThrows(IllegalArgumentException::class.java) { focus.synchronize(interval) })
        assertEquals(listOf(cleanup), primary.suppressed.toList())
        assertEquals(listOf(true, false), transitions)
        assertFalse(focus.isActive)
        focus.clear()
        assertEquals(listOf(true, false), transitions)
    }

    private fun editableFocusIntervals(): Pair<RuntimeTextInputFocus, RuntimeTextInputFocus> =
        createRuntimeUiSession {
            evaluateComponentTree { Spacer(modifier = Modifier.Empty.size(1, 1).then(EditableFocusElement)) }
        }.use { session ->
            session.attach()
            session.frame(Constraints.fixed(1, 1))
            val first = checkNotNull(session.textInputFocus)
            session.detach()
            session.attach()
            session.frame(Constraints.fixed(1, 1))
            first to checkNotNull(session.textInputFocus)
        }

    private object EditableFocusElement : ModifierElement {
        override val type: ModifierNodeType<*, *> =
            ModifierNodeType(
                elementClass = EditableFocusElement::class,
                nodeClass = EditableFocusNode::class,
                validateLocal = {},
                createNode = { EditableFocusNode() },
                updateNode = { _, _, _ -> DirtyMask.None },
            )
    }

    private class EditableFocusNode :
        ModifierNode(),
        FocusTargetNode {
        override val acceptsFocus: Boolean = true
        override val requestsInitialFocus: Boolean = true
        override val requiresTextInput: Boolean = true

        override fun onFocusChanged(focused: Boolean) = Unit
    }
}
