package dev.s7a.strata.integration.external

import dev.s7a.strata.dsl.Box
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.minecraft.Button
import dev.s7a.strata.runtime.minecraft.MinecraftNineSliceCenterMode
import dev.s7a.strata.runtime.minecraft.MinecraftSlots
import dev.s7a.strata.runtime.minecraft.MinecraftTextStyle
import dev.s7a.strata.runtime.minecraft.Slot
import dev.s7a.strata.runtime.minecraft.Text
import dev.s7a.strata.runtime.minecraft.TextField
import dev.s7a.strata.runtime.minecraft.containerBackground
import dev.s7a.strata.runtime.minecraft.createMinecraftScreenDefinition
import dev.s7a.strata.runtime.minecraft.createMinecraftTextFieldState
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.minecraft.createMinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.menuBackground
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies the common Minecraft host with a primitive compiled in an external Gradle module.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class ExternalMinecraftUiHostIntegrationTest {
    @Test
    fun externalContentBuildsMenuAndTextWithoutPublicContextOrRegistration() {
        assertExternalMenuAndText()
        assertExternalTextFieldAndStyle()
        assertExternalContainerAndSlot()
    }

    private fun assertExternalMenuAndText() {
        val menuHost =
            createMinecraftScreenDefinition("external menu") {
                Box(modifier = Modifier.Empty.menuBackground()) {}
            }.let { definition -> createMinecraftUiHost(definition, profile()) }
        menuHost.attach()
        val menuFrame = menuHost.frame(IntSize(32, 32))
        assertEquals(1, menuFrame.drawCommands.count { command -> command is DrawCommand.BlitImage })
        menuHost.close()

        val textHost =
            createMinecraftScreenDefinition(UiText.Literal("external text")) {
                Text("A B")
            }.let { definition -> createMinecraftUiHost(definition, profile()) }
        textHost.attach()
        val textFrame = textHost.frame(IntSize(6, 9))
        assertEquals(4, textFrame.drawCommands.count { command -> command is DrawCommand.BlitImage })
        val textSemantics = textFrame.semantics.single { entry -> entry.semantics.role == SemanticsRole.Text }
        assertEquals(UiText.Literal("A B"), textSemantics.semantics.label)
        textHost.close()
    }

    private fun assertExternalTextFieldAndStyle() {
        val state = createMinecraftTextFieldState("A", maxLength = 8)
        val fieldHost =
            createMinecraftScreenDefinition(UiText.Literal("external field")) {
                TextField(state)
            }.let { definition -> createMinecraftUiHost(definition, profile()) }
        fieldHost.attach()
        val fieldFrame = fieldHost.frame(IntSize(200, 20))
        assertEquals(
            SemanticsRole.TextField,
            fieldFrame.semantics
                .single()
                .semantics.role,
        )
        assertEquals(
            UiText.Literal("A"),
            fieldFrame.semantics
                .single()
                .semantics.label,
        )
        fieldHost.close()

        val inactiveHost =
            createMinecraftScreenDefinition(UiText.Literal("external styled text")) {
                Text("A", style = MinecraftTextStyle.Inactive)
            }.let { definition -> createMinecraftUiHost(definition, profile()) }
        inactiveHost.attach()
        assertEquals(IntSize(1, 9), inactiveHost.frame(IntSize(1, 9)).size)
        inactiveHost.close()
    }

    private fun assertExternalContainerAndSlot() {
        val containerHost =
            createMinecraftScreenDefinition("external container") {
                Box(modifier = Modifier.Empty.containerBackground()) {}
            }.let { definition -> createMinecraftUiHost(definition, profile()) }
        containerHost.attach()
        assertEquals(
            2,
            containerHost
                .frame(IntSize(176, 168))
                .drawCommands
                .count { command -> command is DrawCommand.BlitImage },
        )
        containerHost.close()

        val slotHost =
            createMinecraftScreenDefinition("external slot") {
                Slot()
            }.let { definition -> createMinecraftUiHost(definition, profile()) }
        slotHost.attach()
        slotHost.frame(IntSize(18, 18))
        assertEquals(InputResult.Ignored, slotHost.dispatchPointer(PointerEvent.Move(IntOffset(1, 1))))
        assertEquals(
            2,
            slotHost
                .frame(IntSize(18, 18))
                .drawCommands
                .count { command -> command is DrawCommand.BlitImage },
        )
        slotHost.close()

        val synchronizedSlotHost =
            createMinecraftScreenDefinition("external synchronized slot") {
                Slot(bind = MinecraftSlots.playerInventory(0))
            }.let { definition -> createMinecraftUiHost(definition, profile()) }
        val failure = assertThrows(IllegalStateException::class.java) { synchronizedSlotHost.attach() }
        assertEquals(
            "Bound inventory Slots require a versioned Minecraft platform host.",
            failure.message,
        )
        synchronizedSlotHost.close()
    }

    @Test
    fun externalPrimitiveUsesFixedViewportAndRetainedInvalidationWithoutRegistration() {
        val probe = ExternalProbe()
        var contentCalls = 0
        val definition =
            createMinecraftScreenDefinition(
                title = UiText.Literal("external"),
                pausesGame = false,
            ) {
                contentCalls += 1
                element(ExternalElement(probe = probe, width = 4, height = 3))
            }
        val host = createMinecraftUiHost(definition, profile())
        host.attach()

        val firstFrame = host.frame(IntSize(6, 5))
        val node = probe.componentNodes.getValue(ExternalNodeId.Root)
        assertEquals(IntSize(6, 5), firstFrame.size)
        assertEquals(listOf(Constraints.fixed(6, 5)), probe.componentMeasureConstraints)
        assertEquals(
            InputResult.Consumed,
            host.dispatchPointer(PointerEvent.Press(IntOffset(1, 1), PointerButton.Primary)),
        )

        node.invalidateForTest(DirtyPhase.Paint)
        host.frame(IntSize(6, 5))
        assertSame(node, probe.componentNodes.getValue(ExternalNodeId.Root))
        assertEquals(2, node.paints)
        assertEquals(1, contentCalls)

        host.detach()
        host.attach()
        assertEquals(InputResult.Ignored, host.dispatchPointer(PointerEvent.Move(IntOffset(1, 1))))
        val zeroFrame = host.frame(IntSize.Zero)
        assertEquals(IntSize.Zero, zeroFrame.size)
        assertEquals(Constraints.fixed(0, 0), probe.componentMeasureConstraints.last())
        assertSame(node, probe.componentNodes.getValue(ExternalNodeId.Root))

        host.close()
        assertEquals(1, probe.lifecycle.count { event -> event is ExternalLifecycleEvent.Detach })
        assertEquals(1, probe.lifecycle.count { event -> event is ExternalLifecycleEvent.Dispose })
    }

    @Test
    fun externalPurposeSpecificCompositionRunsWithoutBecomingAStandardBuiltIn() {
        val host =
            createMinecraftUiHost(
                createMinecraftScreenDefinition("external energy") {
                    EnergyGauge(energy = 3, capacity = 4)
                },
                profile(),
            )
        host.attach()
        val frame = host.frame(IntSize(20, 4))
        val fills = frame.drawCommands.filterIsInstance<DrawCommand.FillRectangle>()
        assertEquals(2, fills.size)
        assertEquals(20, fills[0].bounds.width)
        assertEquals(15, fills[1].bounds.width)
        host.close()
    }

    @Test
    fun externalDslBuildsAndDispatchesPointerButtonWithoutRegistration() {
        var presses = 0
        val host =
            createMinecraftUiHost(
                createMinecraftScreenDefinition(UiText.Literal("external button")) {
                    Button("A", modifier = Modifier.Empty.onPress { presses += 1 })
                },
                profile(),
            )
        host.attach()
        val frame = host.frame(IntSize(150, 20))
        assertEquals(IntSize(150, 20), frame.size)
        assertEquals(
            SemanticsRole.Button,
            frame.semantics
                .single()
                .semantics.role,
        )
        assertEquals(
            InputResult.Consumed,
            host.dispatchPointer(PointerEvent.Press(IntOffset(1, 1), PointerButton.Primary)),
        )
        assertEquals(1, presses)
        host.detach()
        host.attach()
        assertEquals(InputResult.Ignored, host.dispatchPointer(PointerEvent.Move(IntOffset(1, 1))))
        host.close()
    }

    private fun profile() =
        createMinecraftUiProfile {
            menuBackground(image(IntSize(16, 16)))
            containerBackground(image(IntSize(256, 256)))
            slotHighlightBack(image(IntSize(24, 24)))
            slotHighlightFront(image(IntSize(24, 24)))
            listBackground(image(IntSize(16, 16)))
            listHeaderSeparator(image(IntSize(32, 2)))
            listFooterSeparator(image(IntSize(32, 2)))
            scrollbarBackground(image(IntSize(6, 32)))
            scrollbarThumb(image(IntSize(6, 32)))
            textFieldNormal(image(IntSize(200, 20)))
            textFieldHighlighted(image(IntSize(200, 20)))
            for (codePoint in 0x21..0x7E) {
                printableAsciiGlyph(codePoint, image(IntSize(8, 8)))
            }
            val button = image(IntSize(200, 20))
            buttonNormal(button, 1, MinecraftNineSliceCenterMode.Tiled)
            buttonHighlighted(button, 1, MinecraftNineSliceCenterMode.Tiled)
            buttonDisabled(button, 1, MinecraftNineSliceCenterMode.Tiled)
        }

    private fun image(size: IntSize) = createDrawImage(size, IntArray(Math.multiplyExact(size.width, size.height)) { 0x00FFFFFF })
}
