package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Tab
import dev.s7a.strata.component.TabSelectionIndicator
import dev.s7a.strata.component.UiScope
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies externally controlled Tab appearance, semantics, hover, custom indicators, and callback cardinality.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftTabTest {
    @Test
    fun selectedUnderlineAndTabSemanticsAreOwnedByTheComponent() {
        var presses = 0
        val host =
            host {
                Tab(
                    "All",
                    selected = true,
                    width = 73,
                    modifier = Modifier.Empty.onPress { presses += 1 },
                )
            }
        host.attach()

        val frame = host.frame(IntSize(73, 20))
        val semantics = frame.semantics.single().semantics
        assertEquals(SemanticsRole.Tab, semantics.role)
        assertEquals(UiText.Literal("All"), semantics.label)
        assertEquals(true, semantics.selected)
        assertFalse(semantics.disabled)
        assertEquals(
            listOf(
                IntRect(30, 15, 43, 16),
                IntRect(29, 14, 42, 15),
            ),
            frame.drawCommands.filterIsInstance<DrawCommand.FillRectangle>().map { command -> command.bounds },
        )
        assertEquals(
            listOf(
                ArgbColor(0xFF3F3F3F.toInt()),
                ArgbColor(0xFFFFFFFF.toInt()),
            ),
            frame.drawCommands.filterIsInstance<DrawCommand.FillRectangle>().map { command -> command.color },
        )

        host.dispatchPointer(PointerEvent.Press(IntOffset(2, 2), PointerButton.Primary))
        assertEquals(1, presses)
        host.close()
    }

    @Test
    fun unselectedTabOmitsIndicatorAndSelectedCustomContentReplacesUnderline() {
        val unselected = host { Tab("Hidden", selected = false, width = 73) }
        unselected.attach()
        val unselectedFrame = unselected.frame(IntSize(73, 20))
        assertTrue(unselectedFrame.drawCommands.none { command -> command is DrawCommand.FillRectangle })
        assertEquals(
            false,
            unselectedFrame.semantics
                .single()
                .semantics.selected,
        )
        unselected.close()

        val indicatorColor = ArgbColor(0xFF00AAFF.toInt())
        val selected =
            host {
                Tab(
                    "Custom",
                    selected = true,
                    width = 73,
                    indicator =
                        TabSelectionIndicator.Custom {
                            Spacer(modifier = Modifier.Empty.size(9, 2).background(indicatorColor))
                        },
                )
            }
        selected.attach()
        val customFills = selected.frame(IntSize(73, 20)).drawCommands.filterIsInstance<DrawCommand.FillRectangle>()
        assertEquals(listOf(IntRect(32, 18, 41, 20)), customFills.map { command -> command.bounds })
        assertEquals(listOf(indicatorColor), customFills.map { command -> command.color })
        selected.close()
    }

    @Test
    fun customIndicatorRequiresExactlyOneRootOnlyWhenSelected() {
        val zero =
            host {
                Tab(
                    "Zero",
                    selected = true,
                    indicator = TabSelectionIndicator.Custom { },
                )
            }
        assertThrows(IllegalArgumentException::class.java) { zero.attach() }
        zero.close()

        val ignored =
            host {
                Tab(
                    "Ignored",
                    selected = false,
                    indicator = TabSelectionIndicator.Custom { throw AssertionError("unselected indicator ran") },
                )
            }
        ignored.attach()
        ignored.frame(IntSize(150, 20))
        ignored.close()
    }

    private fun host(content: UiScope.() -> Unit): MinecraftUiHost =
        createMinecraftUiHost(
            ScreenDefinition("Tab", content = content),
            MinecraftProfileFixture.create(),
        )
}
