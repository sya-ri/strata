package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies the fixed-size common Minecraft pointer-button behavior.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftPointerButtonTest {
    @Test
    fun fixedButtonEmitsNineSliceThenGlyphCommandsAndSemantics() {
        var presses = 0
        val host =
            host { context ->
                context.pointerButton(UiText.Literal("A")) { presses += 1 }
            }
        host.attach()

        val frame = host.frame(IntSize(150, 20))
        val commands = frame.drawCommands.filterIsInstance<DrawCommand.BlitImage>()
        assertEquals(5, commands.size)
        assertEquals(
            listOf(
                IntRect(0, 0, 3, 20),
                IntRect(3, 0, 147, 20),
                IntRect(147, 0, 150, 20),
                IntRect(75, 7, 83, 15),
                IntRect(74, 6, 82, 14),
            ),
            commands.map { command -> command.destination },
        )
        assertEquals(IntRect(3, 0, 147, 20), commands[1].source)
        assertEquals(IntRect(197, 0, 200, 20), commands[2].source)
        assertSame(commands[0].image, commands[1].image)
        assertSame(commands[0].image, commands[2].image)
        assertEquals(
            UiText.Literal("A"),
            frame.semantics
                .single()
                .semantics.label,
        )
        assertEquals(
            SemanticsRole.Button,
            frame.semantics
                .single()
                .semantics.role,
        )
        assertEquals(
            false,
            frame.semantics
                .single()
                .semantics.disabled,
        )
        assertEquals(0, presses)

        assertEquals(InputResult.Consumed, host.dispatchPointer(PointerEvent.Press(IntOffset(5, 5), PointerButton.Primary)))
        assertEquals(1, presses)
        host.close()
    }

    @Test
    fun hoverChangesOnlyAfterMoveAndUsesHighlightedSprite() {
        val host = host { context -> context.pointerButton(UiText.Literal("A")) {} }
        host.attach()
        val normal = host.frame(IntSize(150, 20)).drawCommands.filterIsInstance<DrawCommand.BlitImage>()
        val normalSprite = normal.first().image

        assertEquals(InputResult.Ignored, host.dispatchPointer(PointerEvent.Move(IntOffset(10, 10))))
        val hovered = host.frame(IntSize(150, 20)).drawCommands.filterIsInstance<DrawCommand.BlitImage>()
        assertNotSame(normalSprite, hovered.first().image)
        assertSame(hovered.first().image, hovered[1].image)
        assertSame(hovered.first().image, hovered[2].image)

        assertEquals(InputResult.Ignored, host.dispatchPointer(PointerEvent.Move(IntOffset(150, 20))))
        val exited = host.frame(IntSize(150, 20)).drawCommands.filterIsInstance<DrawCommand.BlitImage>()
        assertSame(normalSprite, exited.first().image)
        host.close()
    }

    @Test
    fun nonPrimaryReleaseAndScrollAreIgnoredWhilePrimaryIsConsumed() {
        var presses = 0
        val host = host { context -> context.pointerButton(UiText.Literal("A")) { presses += 1 } }
        host.attach()
        host.frame(IntSize(150, 20))

        assertEquals(InputResult.Ignored, host.dispatchPointer(PointerEvent.Press(IntOffset(5, 5), PointerButton.Secondary)))
        assertEquals(InputResult.Ignored, host.dispatchPointer(PointerEvent.Release(IntOffset(5, 5), PointerButton.Primary)))
        assertEquals(InputResult.Ignored, host.dispatchPointer(PointerEvent.Scroll(IntOffset(5, 5), 1.0, 1.0)))
        assertEquals(InputResult.Consumed, host.dispatchPointer(PointerEvent.Press(IntOffset(5, 5), PointerButton.Primary)))
        assertEquals(1, presses)
        host.close()
    }

    @Test
    fun disabledButtonDoesNotConsumeAndUsesInactiveSemantics() {
        var presses = 0
        val host =
            host { context ->
                context.pointerButton(UiText.Literal("A"), enabled = false) { presses += 1 }
            }
        host.attach()
        val frame = host.frame(IntSize(150, 20))
        assertEquals(
            true,
            frame.semantics
                .single()
                .semantics.disabled,
        )
        assertEquals(InputResult.Ignored, host.dispatchPointer(PointerEvent.Press(IntOffset(5, 5), PointerButton.Primary)))
        assertEquals(0, presses)
        host.close()
    }

    @Test
    fun exactLabelWidthBoundaryIsAcceptedAndNextWidthFails() {
        val accepted = host { context -> context.pointerButton(UiText.Literal("H".repeat(16) + "A")) {} }
        accepted.attach()
        val acceptedCommands = accepted.frame(IntSize(150, 20)).drawCommands.filterIsInstance<DrawCommand.BlitImage>()
        assertEquals(37, acceptedCommands.size)
        accepted.close()

        val rejected = host { context -> context.pointerButton(UiText.Literal("H".repeat(16) + "B")) {} }
        assertThrows(IllegalArgumentException::class.java) { rejected.attach() }
        rejected.close()
    }

    @Test
    fun everyViewportMismatchIsRejectedByTheFixedButtonMeasurement() {
        listOf(IntSize(149, 20), IntSize(150, 19), IntSize(151, 20), IntSize(150, 21)).forEach { viewport ->
            val host = host { context -> context.pointerButton(UiText.Literal("A")) {} }
            host.attach()
            assertThrows(IllegalArgumentException::class.java) { host.frame(viewport) }
            host.close()
        }
    }

    @Test
    fun tiledAndStretchedBordersRetainExactSourceAndDestinationSlices() {
        val profile =
            MinecraftProfileFixture.create(
                normalBorder = 3,
                normalCenterMode = MinecraftNineSliceCenterMode.Stretched,
                highlightedBorder = 3,
                highlightedCenterMode = MinecraftNineSliceCenterMode.Stretched,
                disabledBorder = 1,
                disabledCenterMode = MinecraftNineSliceCenterMode.Tiled,
            )
        val normalHost = host(profile) { context -> context.pointerButton(UiText.Literal("A")) {} }
        normalHost.attach()
        val normal = normalHost.frame(IntSize(150, 20)).drawCommands.filterIsInstance<DrawCommand.BlitImage>()
        assertEquals(
            listOf(
                IntRect(0, 0, 3, 20),
                IntRect(3, 0, 147, 20),
                IntRect(147, 0, 150, 20),
            ),
            normal.take(3).map { command -> command.destination },
        )
        assertEquals(
            listOf(
                IntRect(0, 0, 3, 20),
                IntRect(3, 0, 197, 20),
                IntRect(197, 0, 200, 20),
            ),
            normal.take(3).map { command -> command.source },
        )
        normalHost.close()

        val disabledHost = host(profile) { context -> context.pointerButton(UiText.Literal("A"), enabled = false) {} }
        disabledHost.attach()
        val disabled = disabledHost.frame(IntSize(150, 20)).drawCommands.filterIsInstance<DrawCommand.BlitImage>()
        assertEquals(
            listOf(
                IntRect(0, 0, 1, 20),
                IntRect(1, 0, 149, 20),
                IntRect(149, 0, 150, 20),
            ),
            disabled.take(3).map { command -> command.destination },
        )
        assertEquals(
            listOf(
                IntRect(0, 0, 1, 20),
                IntRect(1, 0, 149, 20),
                IntRect(199, 0, 200, 20),
            ),
            disabled.take(3).map { command -> command.source },
        )
        disabledHost.close()
    }

    @Test
    fun oddAndEvenLabelWidthsUseTheLockedCenteredOriginAndBaseline() {
        val evenHost = host { context -> context.pointerButton(UiText.Literal("A")) {} }
        evenHost.attach()
        val even = evenHost.frame(IntSize(150, 20)).drawCommands.filterIsInstance<DrawCommand.BlitImage>()
        assertEquals(IntRect(74, 6, 82, 14), even[4].destination)
        assertEquals(IntRect(75, 7, 83, 15), even[3].destination)
        evenHost.close()

        val oddHost = host { context -> context.pointerButton(UiText.Literal("H")) {} }
        oddHost.attach()
        val odd = oddHost.frame(IntSize(150, 20)).drawCommands.filterIsInstance<DrawCommand.BlitImage>()
        assertEquals(IntRect(71, 6, 79, 14), odd[4].destination)
        assertEquals(IntRect(72, 7, 80, 15), odd[3].destination)
        oddHost.close()
    }

    @Test
    fun hoverStaysUntilMoveLeavesAndDetachClearsItBeforeReattach() {
        val host = host { context -> context.pointerButton(UiText.Literal("A")) {} }
        host.attach()
        val normal =
            host
                .frame(IntSize(150, 20))
                .drawCommands
                .filterIsInstance<DrawCommand.BlitImage>()[0]
                .image
        assertEquals(InputResult.Ignored, host.dispatchPointer(PointerEvent.Move(IntOffset(5, 5))))
        val highlighted =
            host
                .frame(IntSize(150, 20))
                .drawCommands
                .filterIsInstance<DrawCommand.BlitImage>()[0]
                .image
        assertNotSame(normal, highlighted)
        assertSame(
            highlighted,
            host
                .frame(IntSize(150, 20))
                .drawCommands
                .filterIsInstance<DrawCommand.BlitImage>()[0]
                .image,
        )
        assertEquals(InputResult.Ignored, host.dispatchPointer(PointerEvent.Move(IntOffset(150, 20))))
        assertSame(
            normal,
            host
                .frame(IntSize(150, 20))
                .drawCommands
                .filterIsInstance<DrawCommand.BlitImage>()[0]
                .image,
        )
        host.dispatchPointer(PointerEvent.Move(IntOffset(5, 5)))
        assertNotSame(
            normal,
            host
                .frame(IntSize(150, 20))
                .drawCommands
                .filterIsInstance<DrawCommand.BlitImage>()[0]
                .image,
        )
        host.detach()
        host.attach()
        assertSame(
            normal,
            host
                .frame(IntSize(150, 20))
                .drawCommands
                .filterIsInstance<DrawCommand.BlitImage>()[0]
                .image,
        )
        host.close()
    }

    @Test
    fun headlessPixelsFollowNormalHoverDisabledSpritesAndTextLayers() {
        val host = host { context -> context.pointerButton(UiText.Literal("A")) {} }
        host.attach()
        val normal = rasterizeHeadless(host.frame(IntSize(150, 20)).drawCommands, IntSize(150, 20))
        assertEquals(0xFF202020.toInt(), normal.argbAt(5, 5))
        assertEquals(0xFFFFFFFF.toInt(), normal.argbAt(74, 6))
        assertEquals(0xFF3F3F3F.toInt(), normal.argbAt(75, 7))
        host.dispatchPointer(PointerEvent.Move(IntOffset(5, 5)))
        val highlighted = rasterizeHeadless(host.frame(IntSize(150, 20)).drawCommands, IntSize(150, 20))
        assertEquals(0xFF303030.toInt(), highlighted.argbAt(5, 5))
        host.close()

        val disabledHost = host { context -> context.pointerButton(UiText.Literal("A"), enabled = false) {} }
        disabledHost.attach()
        val disabled = rasterizeHeadless(disabledHost.frame(IntSize(150, 20)).drawCommands, IntSize(150, 20))
        assertEquals(0xFF404040.toInt(), disabled.argbAt(5, 5))
        assertEquals(0xFFA0A0A0.toInt(), disabled.argbAt(74, 6))
        assertEquals(0xFF282828.toInt(), disabled.argbAt(75, 7))
        disabledHost.close()
    }

    @Test
    fun primaryCallbackFailureRemainsTheExactTerminalFailure() {
        val primary = IllegalArgumentException("button callback")
        val host = host { context -> context.pointerButton(UiText.Literal("A")) { throw primary } }
        host.attach()
        host.frame(IntSize(150, 20))
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                host.dispatchPointer(PointerEvent.Press(IntOffset(5, 5), PointerButton.Primary))
            }
        assertSame(primary, failure)
        host.close()
    }

    @Test
    fun overlappingEnabledButtonsBothHoverButTopmostConsumesPrimaryPress() {
        var lowerPresses = 0
        var upperPresses = 0
        val host =
            host { context ->
                MinecraftButtonContainerElement.create(
                    listOf(
                        context.pointerButton(UiText.Literal("A")) { lowerPresses += 1 },
                        context.pointerButton(UiText.Literal("B")) { upperPresses += 1 },
                    ),
                )
            }
        host.attach()
        val normal = host.frame(IntSize(150, 20)).drawCommands.filterIsInstance<DrawCommand.BlitImage>()
        assertEquals(10, normal.size)
        assertEquals(InputResult.Ignored, host.dispatchPointer(PointerEvent.Move(IntOffset(5, 5))))
        val hovered = host.frame(IntSize(150, 20)).drawCommands.filterIsInstance<DrawCommand.BlitImage>()
        assertNotSame(normal[0].image, hovered[0].image)
        assertNotSame(normal[5].image, hovered[5].image)
        assertSame(hovered[0].image, hovered[1].image)
        assertSame(hovered[5].image, hovered[6].image)

        assertEquals(InputResult.Consumed, host.dispatchPointer(PointerEvent.Press(IntOffset(5, 5), PointerButton.Primary)))
        assertEquals(0, lowerPresses)
        assertEquals(1, upperPresses)
        host.close()
    }

    @Test
    fun disabledTopButtonFallsThroughToEnabledLowerButtonAndDoesNotHover() {
        var lowerPresses = 0
        var upperPresses = 0
        val host =
            host { context ->
                MinecraftButtonContainerElement.create(
                    listOf(
                        context.pointerButton(UiText.Literal("A")) { lowerPresses += 1 },
                        context.pointerButton(UiText.Literal("B"), enabled = false) { upperPresses += 1 },
                    ),
                )
            }
        host.attach()
        val normal = host.frame(IntSize(150, 20)).drawCommands.filterIsInstance<DrawCommand.BlitImage>()
        assertEquals(InputResult.Ignored, host.dispatchPointer(PointerEvent.Move(IntOffset(5, 5))))
        val hovered = host.frame(IntSize(150, 20)).drawCommands.filterIsInstance<DrawCommand.BlitImage>()
        assertNotSame(normal[0].image, hovered[0].image)
        assertSame(normal[5].image, hovered[5].image)
        assertEquals(InputResult.Consumed, host.dispatchPointer(PointerEvent.Press(IntOffset(5, 5), PointerButton.Primary)))
        assertEquals(1, lowerPresses)
        assertEquals(0, upperPresses)
        host.close()
    }

    @Test
    fun allDisabledButtonsIgnorePrimaryPress() {
        var presses = 0
        val host =
            host { context ->
                MinecraftButtonContainerElement.create(
                    listOf(
                        context.pointerButton(UiText.Literal("A"), enabled = false) { presses += 1 },
                        context.pointerButton(UiText.Literal("B"), enabled = false) { presses += 1 },
                    ),
                )
            }
        host.attach()
        host.frame(IntSize(150, 20))
        assertEquals(InputResult.Ignored, host.dispatchPointer(PointerEvent.Press(IntOffset(5, 5), PointerButton.Primary)))
        assertEquals(0, presses)
        host.close()
    }

    private fun host(
        profile: MinecraftUiProfile = MinecraftProfileFixture.create(),
        content: (MinecraftUiContext) -> Element,
    ): MinecraftUiHost =
        createMinecraftUiHost(
            createMinecraftScreenDefinition(UiText.Literal("button"), content = content),
            profile,
        )
}
