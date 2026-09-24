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
    fun consumedInputAndTextEditingBlockForwardingAndReleaseHeldBindings() {
        val pressed = mutableSetOf<UiGameAction>()
        val gate = RuntimeUiInput(UiGameAction.entries.associateWith { it }, { pressed.add(it) }, { pressed.remove(it) })
        val inputSession = UnpresentedUiSession {}
        gate.configure(inputSession, UiInputPolicy.All, false)
        gate.event(listOf(UiGameAction.Attack), true, true)
        assertEquals(emptySet<UiGameAction>(), pressed)
        gate.event(listOf(UiGameAction.Movement, UiGameAction.Attack), true, false)
        assertEquals(setOf(UiGameAction.Movement, UiGameAction.Attack), pressed)
        gate.configure(inputSession, UiInputPolicy.All, true)
        gate.event(UiGameAction.entries, true, false)
        assertEquals(emptySet<UiGameAction>(), pressed)
        gate.close()
    }

    @Test
    fun ownerPolicyFocusAndTerminalChangesCannotLeavePressedKeys() {
        val down = mutableSetOf<UiGameAction>()
        val gate = RuntimeUiInput(UiGameAction.entries.associateWith { it }, { down.add(it) }, { down.remove(it) })
        val inputSession = UnpresentedUiSession {}
        gate.configure(inputSession, UiInputPolicy.All, false)
        gate.event(UiGameAction.entries, true, false)
        gate.configure(inputSession, UiInputPolicy.Movement, false)
        assertEquals(emptySet<UiGameAction>(), down)
        gate.event(UiGameAction.entries, true, false)
        assertEquals(setOf(UiGameAction.Movement, UiGameAction.Jump, UiGameAction.Sneak, UiGameAction.Sprint), down)
        gate.configure(UnpresentedUiSession {}, UiInputPolicy.Movement, false)
        assertEquals(emptySet<UiGameAction>(), down)
        gate.event(UiGameAction.entries, true, false)
        gate.reset()
        assertEquals(emptySet<UiGameAction>(), down)
        gate.event(UiGameAction.entries, true, false)
        gate.close()
        gate.close()
        assertEquals(emptySet<UiGameAction>(), down)
    }

    @Test
    fun repeatedDownDoesNotRetoggleOrQueueAnotherGameplayPress() {
        var presses = 0
        var releases = 0
        val gate = RuntimeUiInput(mapOf(UiGameAction.Sneak to UiGameAction.Sneak), { presses++ }, { releases++ })
        gate.configure(UnpresentedUiSession {}, UiInputPolicy.Movement, false)
        repeat(3) { gate.event(listOf(UiGameAction.Sneak), true, false) }
        assertEquals(1, presses)
        gate.event(listOf(UiGameAction.Sneak), false, false)
        assertEquals(1, releases)
        gate.event(listOf(UiGameAction.Sneak), true, false)
        assertEquals(2, presses)
        gate.close()
        assertEquals(2, releases)
    }
}
