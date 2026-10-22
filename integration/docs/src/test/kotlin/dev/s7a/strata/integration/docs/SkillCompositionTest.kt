package dev.s7a.strata.integration.docs

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.PlayerSkinSource
import dev.s7a.strata.component.SlotBinding
import dev.s7a.strata.component.Slots
import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.integration.docs.skill.connectionScreen
import dev.s7a.strata.integration.docs.skill.openConfirmationScreen
import dev.s7a.strata.integration.docs.skill.powerScreen
import dev.s7a.strata.integration.docs.skill.reactiveScreen
import dev.s7a.strata.integration.docs.skill.storageScreen
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PlatformDrawCommand
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.minecraft.MinecraftInventorySlotBinding
import dev.s7a.strata.runtime.minecraft.MinecraftPlayerSkinBinding
import dev.s7a.strata.runtime.minecraft.MinecraftUiHost
import dev.s7a.strata.runtime.minecraft.MinecraftUiPlatform
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.spi.UiPresenters
import dev.s7a.strata.text.UiText
import dev.s7a.strata.ui.UiDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Exercises compiled API-only documentation compositions through the CPU runtime and actual input dispatch.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class SkillCompositionTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun sharedPanelComposesIndependentContentAndClampsOnlyGaugeGeometry() {
        val profile = ShowcaseMinecraftAssetFixture(temporary).assets().profile
        for ((stored, fillWidth, connected) in listOf(Triple(25, 19, false), Triple(150, 76, true))) {
            val definition =
                UiDefinition("Panels") {
                    Column {
                        powerScreen(stored, 100)
                        connectionScreen(connected)
                    }
                }
            createMinecraftUiHost(definition, profile, fontBackend = LwjglMinecraftFontBackendFactory).use { host ->
                host.attach()
                val frame = host.frame(IntSize(320, 240))
                assertTrue(frame.hasLabel("Power"))
                assertTrue(frame.hasLabel("$stored / 100 E"))
                assertTrue(frame.hasLabel("Connection"))
                assertTrue(frame.hasLabel(if (connected) "Connected" else "Disconnected"))
                val fill = frame.drawCommands.filterIsInstance<DrawCommand.FillRectangle>().single { it.color.value == 0xFF20C7DF.toInt() }
                assertEquals(fillWidth, fill.bounds.width)
                assertEquals(6, fill.bounds.height)
            }
        }
    }

    @Test
    fun reactiveControlsApplySourceChangesAndReleaseEveryObservation() {
        val clock = SkillTestSource("09:00")
        val sending = SkillTestSource(false)
        val loading = SkillTestSource(false)
        val history = SkillTestSource(listOf("First"))
        val draft = TextAreaState("draft")
        var sends = 0
        val profile = ShowcaseMinecraftAssetFixture(temporary).assets().profile
        val definition = reactiveScreen(clock, sending, loading, history, draft) { sends++ }
        createMinecraftUiHost(definition, profile, fontBackend = LwjglMinecraftFontBackendFactory).use { host ->
            host.attach()
            activate(host, host.frame(IntSize(320, 240)), "Send")
            assertEquals(1, sends)
            sending.publish(true)
            clock.publish("09:01")
            loading.publish(true)
            history.publish(listOf("Updated"))
            val frame = host.frame(IntSize(320, 240))
            assertTrue(frame.hasLabel("09:01"))
            assertTrue(frame.hasLabel("Loading..."))
            assertTrue(frame.hasLabel("Updated"))
            assertEquals("draft", draft.value)
            activate(host, frame, "Sending...")
            assertEquals(1, sends)
            for (source in listOf(clock, sending, loading, history)) assertTrue(0 < source.activeSubscriptions)
        }
        for (source in listOf(clock, sending, loading, history)) assertEquals(0, source.activeSubscriptions)
    }

    @Test
    fun storageLayoutBindsTheDocumentedInventoryAndDispatchesDone() {
        val assets = ShowcaseMinecraftAssetFixture(temporary).assets()
        var done = 0
        InventoryPlatform(assets).use { platform ->
            createMinecraftUiHost(storageScreen { done++ }, assets.profile, platform, LwjglMinecraftFontBackendFactory).use { host ->
                host.attach()
                val frame = host.frame(IntSize(320, 240))
                assertEquals(List(9, Slots::playerInventory), platform.requested)
                assertTrue(frame.hasLabel("Oak chest"))
                activate(host, frame, "Done")
                assertEquals(1, done)
            }
            assertEquals(9, platform.released)
            assertEquals(0, platform.activeSubscriptions)
            assertTrue(platform.closed)
        }
    }

    @Test
    fun confirmationOffersItsDefinitionToTheInstalledPresenterAndInvokesItsAction() {
        val offered = ArrayList<UiDefinition>()
        val rejection = IllegalStateException("Capture before ownership transfer")
        var confirmed = 0
        UiPresenters
            .install { definition ->
                offered.add(definition)
                throw rejection
            }.use {
                assertSame(rejection, assertThrows(IllegalStateException::class.java) { openConfirmationScreen { confirmed++ } })
            }
        offered.single().use { definition ->
            val profile = ShowcaseMinecraftAssetFixture(temporary).assets().profile
            createMinecraftUiHost(definition, profile, fontBackend = LwjglMinecraftFontBackendFactory).use { host ->
                host.attach()
                assertEquals(UiText.Literal("Confirm action"), host.title)
                val frame = host.frame(IntSize(320, 180))
                assertTrue(frame.hasLabel("Continue with this action?"))
                assertEquals(0, confirmed)
                activate(host, frame, "Yes")
                assertEquals(1, confirmed)
            }
        }
    }

    private fun RuntimeUiFrame.hasLabel(label: String): Boolean = semantics.any { it.semantics.label == UiText.Literal(label) }

    private fun activate(
        host: MinecraftUiHost,
        frame: RuntimeUiFrame,
        label: String,
    ) {
        val button = frame.semantics.single { it.semantics.role == SemanticsRole.Button && it.semantics.label == UiText.Literal(label) }
        val point = IntOffset(button.bounds.left + button.bounds.width / 2, button.bounds.top + button.bounds.height / 2)
        host.dispatchPointer(PointerEvent.Press(point, PointerButton.Primary))
        host.dispatchPointer(PointerEvent.Release(point, PointerButton.Primary))
    }

    /**
     * Owns fixed empty inventory bindings and original CPU images; it deliberately supplies no player-skin service.
     */
    private class InventoryPlatform(
        private val assets: ShowcaseMinecraftAssets,
    ) : MinecraftUiPlatform {
        val requested = ArrayList<SlotBinding>()
        var released = 0
        var activeSubscriptions = 0
        var closed = false
        private val bindings = ArrayList<MinecraftInventorySlotBinding>()

        override fun inventorySlot(binding: SlotBinding): MinecraftInventorySlotBinding {
            check(closed.not())
            requested.add(binding)
            return object : MinecraftInventorySlotBinding {
                private var closed = false
                private var observer: (() -> Unit)? = null

                override fun drawCommand(): PlatformDrawCommand? {
                    check(closed.not())
                    return null
                }

                override fun observe(observer: () -> Unit): AutoCloseable {
                    check(closed.not() && this.observer == null)
                    this.observer = observer
                    activeSubscriptions++
                    return AutoCloseable {
                        if (this.observer === observer) {
                            this.observer = null
                            activeSubscriptions--
                        }
                    }
                }

                override fun dispatchPointer(event: PointerEvent): InputResult {
                    check(closed.not())
                    return InputResult.Ignored
                }

                override fun close() {
                    if (closed) return
                    closed = true
                    if (observer != null) activeSubscriptions--
                    observer = null
                    released++
                }
            }.also(bindings::add)
        }

        override fun image(resource: ResourceId): DrawImage {
            check(closed.not())
            return assets.image(resource)
        }

        override fun playerSkin(source: PlayerSkinSource): MinecraftPlayerSkinBinding = error("This storage fixture does not request player skins: $source")

        override fun refresh() {
            check(closed.not())
        }

        override fun close() {
            if (closed) return
            closed = true
            bindings.forEach(MinecraftInventorySlotBinding::close)
            bindings.clear()
        }
    }
}
