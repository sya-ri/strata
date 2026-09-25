@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.platform.InputConstants
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.onActivate
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.minecraft.fabric.FabricUiInput
import dev.s7a.strata.runtime.minecraft.fabric.FabricUiSessions
import dev.s7a.strata.runtime.minecraft.fabric.mixin.lifecycle.FabricUiKeyMappingAccess
import dev.s7a.strata.runtime.minecraft.fabric.mixin.lifecycle.FabricUiMinecraftAccess
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiCategory
import dev.s7a.strata.ui.UiCloseReason
import dev.s7a.strata.ui.UiDefinition
import dev.s7a.strata.ui.UiInputPolicy
import dev.s7a.strata.ui.UiInteractionMode
import dev.s7a.strata.ui.UiOperationResult
import dev.s7a.strata.ui.UiPresentation
import dev.s7a.strata.ui.UiRejection
import dev.s7a.strata.ui.UiSession
import dev.s7a.strata.ui.UiSessionStatus
import dev.s7a.strata.ui.UiVisibility
import dev.s7a.strata.ui.UiVisibilityPolicy
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.core.Direction
import net.minecraft.world.phys.BlockHitResult
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * Native HUD pixels, retained switching, visibility, callback ownership, and remapped gameplay input in a loaded world.
 */
internal object MinecraftUiSessionGameTest {
    /**
     * Runs on the suite thread; all UI and native state is owned and restored on the client thread.
     */
    fun run(context: MinecraftCanvasTestContext) {
        val sessions = mutableListOf<UiSession>()
        val previous =
            context.onClient {
                val client = Minecraft.getInstance()
                checkNotNull(client.player)
                Triple(context.exchangeHudHidden(false), client.isWindowActive, (client.options.keyUp as FabricUiKeyMappingAccess).strataKey())
            }
        val toggleCrouch =
            context.onClient {
                Minecraft
                    .getInstance()
                    .options
                    .toggleCrouch()
                    .get()
            }
        val pauseOnLostFocus =
            context.onClient {
                val options = Minecraft.getInstance().options
                options.pauseOnLostFocus.also { options.pauseOnLostFocus = false }
            }
        val result =
            runCatching {
                context.configureViewport(IntSize(640, 480), 1)
                context.onClient { context.setScreen(null) }
                verifyHud(context, sessions)
                verifyInput(context, sessions)
            }
        runCanvasTestCleanup(
            result.exceptionOrNull(),
            { context.onClient { sessions.asReversed().forEach(UiSession::close) } },
            { context.onClient { context.setScreen(null) } },
            {
                context.onClient {
                    Minecraft
                        .getInstance()
                        .options.keyUp
                        .setKey(previous.third)
                    KeyMapping.resetMapping()
                }
            },
            {
                context.onClient {
                    Minecraft
                        .getInstance()
                        .options
                        .toggleCrouch()
                        .set(toggleCrouch)
                }
            },
            { context.onClient { context.exchangeHudHidden(previous.first) } },
            { context.onClient { context.setWindowFocused(previous.second) } },
            { context.onClient { Minecraft.getInstance().options.pauseOnLostFocus = pauseOnLostFocus } },
        )
        result.getOrThrow()
        Files.writeString(context.outputDirectory.resolve("strata-ui-sessions.txt"), "checked=hud-pixels,draw-order,category,hide,close,f1,exclusive-interaction,event-switch,event-close,retained-host,remapped-movement,native-player-movement,native-attack,permission-release,focus-release,toggle-release\n")
    }

    private fun verifyHud(
        context: MinecraftCanvasTestContext,
        sessions: MutableList<UiSession>,
    ) {
        val category = UiCategory(ResourceId("strata_test", "ordinary"))
        var evaluations = 0
        val hud =
            context.onClient {
                UiDefinition(presentation = UiPresentation.Hud, visibility = UiVisibilityPolicy(categories = mapOf(category to UiVisibility.KeepVisible))) {
                    evaluations++
                    Column {
                        Spacer(
                            Modifier.Empty
                                .size(16, 16)
                                .background(ArgbColor(0xFFFF0000.toInt()))
                                .onActivate { switch(UiPresentation.Hud) },
                        )
                    }
                }.open().also(sessions::add)
            }
        val above =
            context.onClient {
                UiDefinition(presentation = UiPresentation.Hud, hudOrder = 1) {
                    Column { Spacer(Modifier.Empty.size(16, 16).background(ArgbColor(0xFF0000FF.toInt()))) }
                }.open().also(sessions::add)
            }
        context.waitFor { 0 < evaluations }
        context.onClient {
            check(evaluations == 1 && FabricUiSessions.visibleHuds().size == 2) {
                "Expected two HUDs and one retained evaluation: evaluations=$evaluations, hud=${hud.status}, above=${above.status}, visible=${FabricUiSessions.visibleHuds().size}, screen=${context.currentScreen()}"
            }
        }
        context.waitTicks(3)
        val pixels = ImageIO.read(context.takeScreenshot("strata-ui-huds", IntSize(640, 480)).toFile())
        check(pixels.getRGB(4, 4) == 0xFF0000FF.toInt()) { "Later HUD must render above the earlier HUD through the native HUD hook." }
        check(pixels.getRGB(32, 32) != 0xFF0000FF.toInt()) { "The HUD must leave the world visible outside its content." }
        verifyInteractionOrder(context, hud)
        val screen = context.onClient { checkNotNull(FabricUiSessions.nativeScreen(hud)) }
        context.onClient {
            check(hud.switch(UiPresentation.Screen) == UiOperationResult.Accepted)
            check(context.currentScreen() === screen)
            check(FabricUiSessions.visibleHuds().isEmpty())
        }
        context.waitTicks(2)
        context.onClient {
            check(evaluations == 1) { "Switching must preserve the evaluated retained tree." }
            check(context.pressPointer(screen, IntOffset(4, 4)))
            check(hud.presentation == UiPresentation.Hud)
            check(context.currentScreen() == null)
            check(FabricUiSessions.nativeScreen(hud) === screen)
        }
        verifyHudInteraction(context, hud, above)
        verifyVisibility(context, sessions, category, hud, above)
    }

    private fun verifyHudInteraction(
        context: MinecraftCanvasTestContext,
        hud: UiSession,
        above: UiSession,
    ) {
        context.onClient {
            hud.setInteractionMode(UiInteractionMode.Cursor)
            above.setInteractionMode(UiInteractionMode.Cursor)
            check(hud.interactionMode == UiInteractionMode.None)
            check(above.interactionMode == UiInteractionMode.Cursor)
            checkNotNull(FabricUiSessions.nativeScreen(above)).onClose()
            check(above.interactionMode == UiInteractionMode.None)
            context.exchangeHudHidden(true)
            FabricUiSessions.tick()
            check(FabricUiSessions.visibleHuds().isEmpty())
            context.exchangeHudHidden(false)
            FabricUiSessions.tick()
            check(above.interactionMode == UiInteractionMode.None)
        }
    }

    private fun verifyVisibility(
        context: MinecraftCanvasTestContext,
        sessions: MutableList<UiSession>,
        category: UiCategory,
        hud: UiSession,
        above: UiSession,
    ) {
        val screen = context.onClient { checkNotNull(FabricUiSessions.nativeScreen(hud)) }
        val disposable =
            context.onClient {
                UiDefinition(presentation = UiPresentation.Hud, visibility = UiVisibilityPolicy(categories = mapOf(category to UiVisibility.Close))) { Spacer() }.open().also(sessions::add)
            }
        context.onClient {
            val ordinary = UiDefinition(category = category) { Spacer() }.open().also(sessions::add)
            FabricUiSessions.tick()
            check(FabricUiSessions.visibleHuds() == listOf(screen))
            check(disposable.status == UiSessionStatus.Closed(UiCloseReason.Visibility))
            check(above.setInteractionMode(UiInteractionMode.Cursor) == UiOperationResult.Rejected(UiRejection.ScreenOpen))
            ordinary.close()
            FabricUiSessions.tick()
            check(FabricUiSessions.visibleHuds().size == 2)
            hud.close()
            above.close()
        }
    }

    private fun verifyInteractionOrder(
        context: MinecraftCanvasTestContext,
        hud: UiSession,
    ) {
        context.onClient { hud.setInteractionMode(UiInteractionMode.Cursor) }
        context.waitTicks(3)
        val pixels = ImageIO.read(context.takeScreenshot("strata-ui-interaction-order", IntSize(640, 480)).toFile())
        check(pixels.getRGB(4, 4) == 0xFF0000FF.toInt()) { "Interaction must not move a HUD ahead of a higher drawing order." }
        context.onClient { hud.setInteractionMode(UiInteractionMode.None) }
    }

    private fun verifyInput(
        context: MinecraftCanvasTestContext,
        sessions: MutableList<UiSession>,
    ) {
        val session =
            context.onClient {
                context.setWindowFocused(true)
                val options = Minecraft.getInstance().options
                options.toggleCrouch().set(true)
                options.keyShift.isDown = true
                KeyMapping.releaseAll()
                options.keyUp.setKey(InputConstants.getKey("key.keyboard.enter"))
                KeyMapping.resetMapping()
                UiDefinition(inputPolicy = UiInputPolicy.Movement) {
                    Spacer(Modifier.Empty.size(16, 16).onActivate { close() })
                }.open().also(sessions::add)
            }
        context.waitTicks(2)
        verifyMovement(context, session)
        verifyAttack(context, session)
        context.onClient {
            val movement = Minecraft.getInstance().options.keyUp
            val screen = checkNotNull(FabricUiSessions.nativeScreen(session))
            pressMinecraftEnter(screen)
            check(movement.isDown) { "The actual remapped movement binding must receive unconsumed UI input." }
            session.setInputPolicy(UiInputPolicy.BlockAll)
            check(movement.isDown.not())
            session.setInputPolicy(UiInputPolicy.Movement)
            pressMinecraftEnter(screen)
            check(movement.isDown)
            context.setWindowFocused(false)
            check(movement.isDown.not())
            context.setWindowFocused(true)
            context.pressPointer(screen, IntOffset(4, 4))
            check(session.status == UiSessionStatus.Closed(UiCloseReason.Closed))
            check(context.currentScreen() == null)
            check(
                Minecraft
                    .getInstance()
                    .options.keyShift.isDown
                    .not(),
            ) { "Closing the UI must not restore a previously released toggle binding." }
        }
    }

    private fun verifyAttack(
        context: MinecraftCanvasTestContext,
        session: UiSession,
    ) {
        context.onClient {
            val client = Minecraft.getInstance()
            val player = checkNotNull(client.player)
            val attack = client.options.keyAttack as FabricUiKeyMappingAccess
            val previousKey = attack.strataKey()
            val previousHit = client.hitResult
            try {
                client.options.keyAttack.setKey(InputConstants.getKey("key.keyboard.enter"))
                KeyMapping.resetMapping()
                session.setInputPolicy(UiInputPolicy(attack = true))
                client.hitResult = BlockHitResult.miss(player.position(), Direction.UP, player.blockPosition())
                pressMinecraftEnter(checkNotNull(FabricUiSessions.nativeScreen(session)))
                check(attack.strataClicks() == 1)
                FabricUiInput.tick()
                check(0 < (client as FabricUiMinecraftAccess).strataMissTime()) { "An allowed attack must reach vanilla's attack handler while the UI remains open." }
                check(attack.strataClicks() == 0)
                session.setInputPolicy(UiInputPolicy.BlockAll)
                check(
                    client.options.keyAttack.isDown
                        .not(),
                )
            } finally {
                session.setInputPolicy(UiInputPolicy.Movement)
                client.options.keyAttack.setKey(previousKey)
                KeyMapping.resetMapping()
                client.hitResult = previousHit
            }
        }
    }

    private fun verifyMovement(
        context: MinecraftCanvasTestContext,
        session: UiSession,
    ) {
        val origin =
            context.onClient {
                val player = checkNotNull(Minecraft.getInstance().player)
                val position = player.x to player.z
                pressMinecraftEnter(checkNotNull(FabricUiSessions.nativeScreen(session)))
                position
            }
        context.waitFor {
            val player = checkNotNull(Minecraft.getInstance().player)
            0.1 < abs(player.x - origin.first) + abs(player.z - origin.second)
        }
        context.onClient {
            check(context.currentScreen() === FabricUiSessions.nativeScreen(session)) { "Real movement must leave the same UI open." }
            session.setInputPolicy(UiInputPolicy.BlockAll)
        }
        // Allow native inertia to settle before proving that revocation stops further movement.
        context.waitTicks(20)
        val stopped =
            context.onClient {
                val player = checkNotNull(Minecraft.getInstance().player)
                player.x to player.z
            }
        context.waitTicks(5)
        context.onClient {
            val player = checkNotNull(Minecraft.getInstance().player)
            val displacement = abs(player.x - stopped.first) + abs(player.z - stopped.second)
            check(displacement < 0.01) { "Revoked movement must not remain held: displacement=$displacement, forward=${Minecraft.getInstance().options.keyUp.isDown}." }
            session.setInputPolicy(UiInputPolicy.Movement)
        }
    }
}
