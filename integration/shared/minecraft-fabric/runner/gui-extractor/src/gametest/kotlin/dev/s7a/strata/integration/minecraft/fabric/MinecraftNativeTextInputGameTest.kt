package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.platform.InputConstants
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.TextField
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.integration.minecraft.fabric.MinecraftParityPresentation.closeFabricScreen
import dev.s7a.strata.integration.minecraft.fabric.MinecraftParityPresentation.readRenderWork
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.initialFocus
import dev.s7a.strata.modifier.onCharacterInput
import dev.s7a.strata.modifier.onPreedit
import dev.s7a.strata.modifier.onTextInput
import dev.s7a.strata.modifier.size
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiDefinition
import net.minecraft.client.Minecraft
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo
import net.minecraft.client.input.PreeditEvent

/**
 * Exercises native IME focus, committed-frame reattachment and handoff to an independent native editor.
 * The GameTest thread coordinates waits; fixture state is accessed through blocking client-thread callbacks.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftNativeTextInputGameTest(
    private val context: MinecraftLoadedTestContext,
    private val profile: MinecraftUiProfile,
    private val nativeEditorAddress: String,
) {
    private lateinit var firstState: TextFieldState
    private lateinit var secondState: TextFieldState
    private lateinit var screen: FabricMinecraftScreen
    private lateinit var nativeParent: DeterministicDirectJoinScreen
    private var firstPreeditCalls = 0
    private var secondPreeditCalls = 0

    /**
     * Runs one focus sequence and independently attempts screen and native-editor cleanup on every exit.
     */
    internal fun run() {
        val result =
            runCatching {
                context.setScreen { createScreen() }
                context.waitForScreen(FabricMinecraftScreen::class.java)
                context.waitFor { minecraft ->
                    MinecraftClientScreenAccess.currentScreen(minecraft) === screen &&
                        readRenderWork(minecraft).inputs != null && nativeTextInputEnabled(minecraft)
                }
                context.waitTicks(2)
                verifyFocusTransitions()
                context.waitTicks(2)
                verifyNativeHandoff()
            }
        runCanvasTestCleanup(result.exceptionOrNull(), { closeFabricScreen(context) }, { closeTestScreens() })
        result.getOrThrow()
    }

    private fun createScreen(): FabricMinecraftScreen {
        firstState = TextFieldState("A")
        secondState = TextFieldState("B")
        nativeParent = DeterministicDirectJoinScreen(nativeEditorAddress)
        val definition =
            UiDefinition("native input focus") {
                Column {
                    TextField(
                        firstState,
                        modifier =
                            Modifier
                                .initialFocus()
                                .onPreedit {
                                    firstPreeditCalls += 1
                                    InputResult.Ignored
                                }.onCharacterInput { event ->
                                    if (event.codePoint == 'X'.code) {
                                        MinecraftClientScreenAccess.setScreen(Minecraft.getInstance(), nativeParent)
                                        InputResult.Consumed
                                    } else {
                                        InputResult.Ignored
                                    }
                                },
                    )
                    TextField(
                        secondState,
                        modifier =
                            Modifier.onPreedit {
                                secondPreeditCalls += 1
                                InputResult.Ignored
                            },
                    )
                    Spacer(modifier = Modifier.size(200, 20).onTextInput { InputResult.Ignored })
                }
            }
        screen = createMinecraftScreen(definition, profile, parent = null)
        return screen
    }

    private fun verifyFocusTransitions() {
        context.runOnClient { minecraft ->
            check(nativeTextInputEnabled(minecraft)) { "Initial editable focus did not enable native text input." }
            check(firstPreeditCalls == 1) { "Unchanged frames must not resubmit native preedit." }
            val preedit = PreeditEvent("日🙂", 3, listOf("日", "🙂"), 1)
            check(screen.preeditUpdated(preedit))
            assertTestValue("A", firstState.value, "Preedit unexpectedly committed text.")
            screen.mouseClicked(MouseButtonEvent(4.0, 24.0, MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), false)
            check(nativeTextInputEnabled(minecraft))
            check(secondPreeditCalls == 1) { "Switching editable owners did not resubmit native preedit." }
            check(screen.charTyped(CharacterEvent('한'.code)))
            assertTestValue("A", firstState.value, "Changing focus altered the previous editor.")
            assertTestValue("한B", secondState.value, "Character input did not reach the focused editor.")
            screen.mouseClicked(MouseButtonEvent(4.0, 44.0, MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), false)
            check(nativeTextInputEnabled(minecraft).not()) { "A passive input observer enabled native text input." }
            check(screen.preeditUpdated(preedit).not())
            screen.mouseClicked(MouseButtonEvent(4.0, 4.0, MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), false)
            check(nativeTextInputEnabled(minecraft))
            MinecraftClientScreenAccess.setScreen(minecraft, null)
            check(nativeTextInputEnabled(minecraft).not()) { "Detaching a screen retained native text-input focus." }
            check(screen.preeditUpdated(preedit).not())
            MinecraftClientScreenAccess.setScreen(minecraft, screen)
            check(nativeTextInputEnabled(minecraft).not()) { "Reattachment enabled text input before a committed frame." }
        }
    }

    private fun verifyNativeHandoff() {
        context.runOnClient { minecraft ->
            check(nativeTextInputEnabled(minecraft))
            assertTestValue("A", firstState.value, "Reattaching the screen changed the first editor.")
            assertTestValue("한B", secondState.value, "Reattaching the screen changed the second editor.")
            check(screen.charTyped(CharacterEvent('X'.code)))
            check(MinecraftClientScreenAccess.currentScreen(minecraft) === nativeParent)
            check(nativeTextInputEnabled(minecraft)) { "Deferred Strata removal disabled the next native editor." }
            screen.close()
            check(nativeTextInputEnabled(minecraft)) { "Closing a detached screen disabled the active native editor." }
            nativeParent.setFocused(null)
            MinecraftClientScreenAccess.setScreen(minecraft, null)
            check(nativeTextInputEnabled(minecraft).not())
        }
    }

    private fun closeTestScreens() {
        context.runOnClient { minecraft ->
            runCanvasTestCleanup(
                null,
                { if (::screen.isInitialized) screen.close() },
                { if (::nativeParent.isInitialized) nativeParent.setFocused(null) },
                {
                    val active = MinecraftClientScreenAccess.currentScreen(minecraft)
                    val ownsScreen = ::screen.isInitialized && active === screen
                    val ownsParent = ::nativeParent.isInitialized && active === nativeParent
                    if (ownsScreen || ownsParent) MinecraftClientScreenAccess.setScreen(minecraft, null)
                },
                { check(nativeTextInputEnabled(minecraft).not()) { "The focus fixture retained native text input after cleanup." } },
            )
        }
    }

    private fun nativeTextInputEnabled(minecraft: Minecraft): Boolean {
        val manager = minecraft.textInputManager()
        val enabled = manager.javaClass.getDeclaredField("textInputEnabled")
        check(enabled.trySetAccessible()) { "Native text-input state is inaccessible to the loaded test." }
        return enabled.getBoolean(manager)
    }
}
