@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.TextField
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.initialFocus
import dev.s7a.strata.modifier.onActivate
import dev.s7a.strata.runtime.minecraft.fabric.FabricUiSessions
import dev.s7a.strata.runtime.minecraft.fabric.mixin.lifecycle.FabricUiKeyMappingAccess
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiCloseReason
import dev.s7a.strata.ui.UiDefinition
import dev.s7a.strata.ui.UiInputPolicy
import dev.s7a.strata.ui.UiInteractionMode
import dev.s7a.strata.ui.UiPresentation
import dev.s7a.strata.ui.UiSession
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft

/**
 * Verifies native held-state adoption without press replay and release across ownership boundaries.
 */
internal object MinecraftHeldMovementGameTest {
    /**
     * Runs with forward already remapped to Enter by the enclosing session suite.
     */
    fun run(
        context: MinecraftCanvasTestContext,
        sessions: MutableList<UiSession>,
    ) {
        context.onClient {
            verifyAdoption(context, sessions)
            verifyBoundaries(context, sessions)
            verifyFailedOpen(context, sessions)
        }
        verifyEditor(context, sessions)
    }

    private fun movement(): List<KeyMapping> = Minecraft.getInstance().options.let { listOf(it.keyUp, it.keyDown, it.keyLeft, it.keyRight, it.keyJump, it.keyShift, it.keySprint) }

    private fun hold(bindings: List<KeyMapping>) {
        bindings.forEach {
            val access = it as FabricUiKeyMappingAccess
            access.strataDown(true)
            access.strataClicks(3)
        }
    }

    private fun verifyAdoption(
        context: MinecraftCanvasTestContext,
        sessions: MutableList<UiSession>,
    ) {
        context.setScreen(null)
        val movement = movement()
        val options = Minecraft.getInstance().options
        val blocked = listOf(options.keyAttack, options.keyUse, options.keyDrop, options.keySwapOffhand, options.keyPickItem) + options.keyHotbarSlots
        hold(movement + blocked)
        val session = UiDefinition(inputPolicy = UiInputPolicy.All) { Spacer() }.open().also(sessions::add)
        val screen = checkNotNull(FabricUiSessions.nativeScreen(session))
        check(movement.all { it.isDown }) { "All effective movement bindings, including toggle crouch, must survive opening." }
        check(blocked.none { it.isDown }) { "Opening must not inherit attack, use or discrete actions even with permission." }
        check((movement + blocked).all { (it as FabricUiKeyMappingAccess).strataClicks() == 0 }) { "Opening must discard queued clicks." }
        repeat(2) { pressMinecraftEnter(screen) }
        check((options.keyUp as FabricUiKeyMappingAccess).strataClicks() == 0) { "Adoption must own held bindings before repeated down events." }
        releaseMinecraftEnter(screen)
        check(options.keyUp.isDown.not() && options.keyLeft.isDown) { "A native release must clear only its remapped binding." }
        session.setInputPolicy(UiInputPolicy.BlockAll)
        check(movement.none { it.isDown })
        session.setInputPolicy(UiInputPolicy.Movement)
        check(movement.none { it.isDown }) { "Restoring permission must not revive old input." }
        session.close()
        hold(movement)
        val blockedSession = UiDefinition { Spacer() }.open().also(sessions::add)
        check(movement.none { it.isDown }) { "BlockAll must discard preexisting movement." }
        blockedSession.close()
        hold(movement)
        val hud = UiDefinition(presentation = UiPresentation.Hud, inputPolicy = UiInputPolicy.Movement) { Spacer() }.open().also(sessions::add)
        hud.setInteractionMode(UiInteractionMode.Cursor)
        check(movement.all { it.isDown }) { "A passive HUD acquiring gameplay input must adopt held movement." }
        hud.setInteractionMode(UiInteractionMode.None)
        check(movement.none { it.isDown })
        hud.setInteractionMode(UiInteractionMode.Cursor)
        check(movement.none { it.isDown }) { "Reacquiring input must not revive a released HUD snapshot." }
        hud.close()
    }

    private fun verifyBoundaries(
        context: MinecraftCanvasTestContext,
        sessions: MutableList<UiSession>,
    ) {
        val movement = movement()
        hold(movement)
        val first = UiDefinition(inputPolicy = UiInputPolicy.Movement) { Spacer() }.open().also(sessions::add)
        val replacement = UiDefinition(inputPolicy = UiInputPolicy.Movement) { Spacer() }.open().also(sessions::add)
        check(movement.none { it.isDown }) { "A replacement owner must not inherit the previous UI's input." }
        replacement.close()
        first.close()
        context.setScreen(null)
        hold(movement)
        val focused = UiDefinition(inputPolicy = UiInputPolicy.Movement) { Spacer() }.open().also(sessions::add)
        check(movement.all { it.isDown })
        context.setWindowFocused(false)
        check(movement.none { it.isDown })
        context.setWindowFocused(true)
        check(movement.none { it.isDown }) { "Window focus must not revive old input." }
        focused.close()
        hold(movement)
        val closed = UiDefinition(inputPolicy = UiInputPolicy.Movement) { Spacer() }.open().also(sessions::add)
        closed.close()
        check(movement.none { it.isDown }) { "Closing must release every adopted binding and toggle restoration state." }
        listOf(UiCloseReason.WorldExited, UiCloseReason.Disconnected).forEach { reason ->
            hold(movement)
            UiDefinition(inputPolicy = UiInputPolicy.Movement) { Spacer() }.open().also(sessions::add)
            FabricUiSessions.closeAll(reason)
            check(movement.none { it.isDown }) { "World and connection teardown must release adopted input." }
        }
    }

    private fun verifyEditor(
        context: MinecraftCanvasTestContext,
        sessions: MutableList<UiSession>,
    ) {
        var activations = 0
        val session =
            context.onClient {
                val field = TextFieldState()
                hold(movement())
                UiDefinition(inputPolicy = UiInputPolicy.Movement) {
                    Column {
                        TextField(field, modifier = Modifier.initialFocus())
                        Button("Leave editor", modifier = Modifier.onActivate { activations += 1 })
                    }
                }.open().also(sessions::add)
            }
        context.waitTicks(3)
        context.onClient {
            check(movement().none { it.isDown }) { "Initial editor focus must release inherited movement." }
            pressMinecraftTab(checkNotNull(FabricUiSessions.nativeScreen(session)))
        }
        context.waitTicks(2)
        context.onClient {
            check(movement().none { it.isDown }) { "Leaving the editor must not revive inherited movement." }
            check(pressMinecraftEnter(checkNotNull(FabricUiSessions.nativeScreen(session))))
            check(activations == 1) { "Tab must actually move focus from the editor to the activation target." }
            session.close()
        }
    }

    private fun verifyFailedOpen(
        context: MinecraftCanvasTestContext,
        sessions: MutableList<UiSession>,
    ) {
        hold(movement())
        val failure = runCatching { UiDefinition(inputPolicy = UiInputPolicy.Movement) { error("Rejected test content") }.open() }
        check(failure.isFailure) { "The failure fixture must reject installation." }
        check(movement().none { it.isDown }) { "Failed installation must discard its captured input." }
        context.setScreen(null)
        val next = UiDefinition(inputPolicy = UiInputPolicy.Movement) { Spacer() }.open().also(sessions::add)
        check(movement().none { it.isDown }) { "A later owner must not receive a failed installation's snapshot." }
        next.close()
    }
}
