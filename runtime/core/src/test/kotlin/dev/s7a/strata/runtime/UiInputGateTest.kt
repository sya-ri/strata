@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.runtime.spi.RuntimeUiInput
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiGameAction
import dev.s7a.strata.ui.UiInputPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Physical binding ownership is released across every input boundary without requiring Minecraft.
 */
internal class UiInputGateTest {
    @Test
    fun inheritedBindingsRestoreOnlyAllowedHeldStateAndNeverReplayPresses() {
        val down = mutableSetOf<UiGameAction>()
        var presses = 0
        val gate = RuntimeUiInput(UiGameAction.entries.associateWith { it }, { presses++ }, { down.remove(it) })
        val session = UnpresentedUiSession {}
        gate.inheritHeld(UiGameAction.entries) { down.add(it) }
        assertEquals(emptySet<UiGameAction>(), down)
        gate.configure(session, UiInputPolicy.Movement, editing = false)
        gate.inheritHeld(UiGameAction.entries) { down.add(it) }
        assertEquals(setOf(UiGameAction.Movement, UiGameAction.Jump, UiGameAction.Sneak, UiGameAction.Sprint), down)
        gate.event(UiGameAction.entries, down = true, consumed = false)
        assertEquals(0, presses)
        gate.event(listOf(UiGameAction.Movement), down = false, consumed = false)
        assertEquals(setOf(UiGameAction.Jump, UiGameAction.Sneak, UiGameAction.Sprint), down)
        gate.configure(session, UiInputPolicy.Movement, editing = true)
        assertEquals(emptySet<UiGameAction>(), down)
        gate.inheritHeld(UiGameAction.entries) { down.add(it) }
        gate.configure(session, UiInputPolicy.Movement, editing = false)
        assertEquals(emptySet<UiGameAction>(), down)
        gate.inheritHeld(UiGameAction.entries) { down.add(it) }
        gate.configure(session, UiInputPolicy.BlockAll, editing = false)
        gate.inheritHeld(UiGameAction.entries) { down.add(it) }
        assertEquals(emptySet<UiGameAction>(), down)
        gate.configure(session, UiInputPolicy.Movement, editing = false)
        assertEquals(emptySet<UiGameAction>(), down)
        gate.inheritHeld(UiGameAction.entries) { down.add(it) }
        gate.configure(UnpresentedUiSession {}, UiInputPolicy.Movement, editing = false)
        assertEquals(emptySet<UiGameAction>(), down)
        gate.inheritHeld(UiGameAction.entries) { down.add(it) }
        gate.close()
        gate.inheritHeld(UiGameAction.entries) { down.add(it) }
        assertEquals(emptySet<UiGameAction>(), down)
        assertEquals(0, presses)
    }

    @Test
    fun consumedInputAndTextEditingBlockForwardingAndReleaseHeldBindings() {
        val pressed = mutableSetOf<UiGameAction>()
        val gate = RuntimeUiInput(UiGameAction.entries.associateWith { it }, { pressed.add(it) }, { pressed.remove(it) })
        val inputSession = UnpresentedUiSession {}
        gate.configure(inputSession, UiInputPolicy.All, editing = false)
        gate.event(listOf(UiGameAction.Attack), down = true, consumed = true)
        assertEquals(emptySet<UiGameAction>(), pressed)
        gate.event(listOf(UiGameAction.Movement, UiGameAction.Attack), down = true, consumed = false)
        assertEquals(setOf(UiGameAction.Movement, UiGameAction.Attack), pressed)
        gate.configure(inputSession, UiInputPolicy.All, editing = true)
        gate.event(UiGameAction.entries, down = true, consumed = false)
        assertEquals(emptySet<UiGameAction>(), pressed)
        gate.close()
    }

    @Test
    fun ownerPolicyFocusAndTerminalChangesCannotLeavePressedKeys() {
        val down = mutableSetOf<UiGameAction>()
        val gate = RuntimeUiInput(UiGameAction.entries.associateWith { it }, { down.add(it) }, { down.remove(it) })
        val inputSession = UnpresentedUiSession {}
        gate.configure(inputSession, UiInputPolicy.All, editing = false)
        gate.event(UiGameAction.entries, down = true, consumed = false)
        gate.configure(inputSession, UiInputPolicy.Movement, editing = false)
        assertEquals(emptySet<UiGameAction>(), down)
        gate.event(UiGameAction.entries, down = true, consumed = false)
        assertEquals(setOf(UiGameAction.Movement, UiGameAction.Jump, UiGameAction.Sneak, UiGameAction.Sprint), down)
        gate.configure(UnpresentedUiSession {}, UiInputPolicy.Movement, editing = false)
        assertEquals(emptySet<UiGameAction>(), down)
        gate.event(UiGameAction.entries, down = true, consumed = false)
        gate.reset()
        assertEquals(emptySet<UiGameAction>(), down)
        gate.event(UiGameAction.entries, down = true, consumed = false)
        gate.close()
        gate.close()
        assertEquals(emptySet<UiGameAction>(), down)
    }

    @Test
    fun repeatedDownDoesNotRetoggleOrQueueAnotherGameplayPress() {
        var presses = 0
        var releases = 0
        val gate = RuntimeUiInput(mapOf(UiGameAction.Sneak to UiGameAction.Sneak), { presses++ }, { releases++ })
        gate.configure(UnpresentedUiSession {}, UiInputPolicy.Movement, editing = false)
        repeat(3) { gate.event(listOf(UiGameAction.Sneak), down = true, consumed = false) }
        assertEquals(1, presses)
        gate.event(listOf(UiGameAction.Sneak), down = false, consumed = false)
        assertEquals(1, releases)
        gate.event(listOf(UiGameAction.Sneak), down = true, consumed = false)
        assertEquals(2, presses)
        gate.close()
        assertEquals(2, releases)
    }
}
