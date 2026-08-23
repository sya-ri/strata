@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.Checkbox
import dev.s7a.strata.component.CheckboxState
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onCheckedChange
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * Verifies Checkbox state, typed action, and native sprite selection.
 */
internal class MinecraftCheckboxTest {
    @Test
    fun primaryPressUpdatesStateEmitsActionAndRepaintsSelectedSprite() {
        val state = CheckboxState()
        val observed = ArrayList<Boolean>()
        val profile = MinecraftProfileFixture.create()
        val host =
            createMinecraftUiHost(
                ScreenDefinition("checkbox") {
                    Checkbox(
                        label = "Enabled",
                        state = state,
                        width = 80,
                        modifier = Modifier.Empty.onCheckedChange(observed::add),
                    )
                },
                profile,
            )
        host.attach()
        val initial = host.frame(IntSize(80, 17))
        val initialSprite =
            initial.drawCommands
                .filterIsInstance<DrawCommand.BlitImage>()
                .first()
                .image

        assertEquals(InputResult.Consumed, host.dispatchPointer(PointerEvent.Press(IntOffset(2, 2), PointerButton.Primary)))
        assertEquals(true, state.checked)
        assertEquals(listOf(true), observed)
        val selected = host.frame(IntSize(80, 17))
        val selectedSprite =
            selected.drawCommands
                .filterIsInstance<DrawCommand.BlitImage>()
                .first()
                .image
        assertFalse(initialSprite === selectedSprite)
        assertEquals(
            true,
            selected.semantics
                .single()
                .semantics.checked,
        )
        host.close()
    }
}
