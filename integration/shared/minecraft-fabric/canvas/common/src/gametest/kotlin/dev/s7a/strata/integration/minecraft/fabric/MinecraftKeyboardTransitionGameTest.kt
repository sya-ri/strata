@file:Suppress("DEPRECATION") // This regression verifies the compatibility screen entry points.

package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.initialFocus
import dev.s7a.strata.modifier.onActivate
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Coordinates one keyboard-transition scenario with client-thread-confined activation counts.
 * Native work is marshalled through [context]; exit attempts to detach and close both owned screens before propagating failures.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftKeyboardTransitionGameTest(
    private val context: MinecraftCanvasTestContext,
    private val profile: MinecraftUiProfile,
) {
    private var firstActivations = 0
    private var secondActivations = 0
    private var replacementActivations = 0

    /**
     * Verifies native key consumption, focus traversal and reentrant replacement without retaining the detached screen's callbacks.
     */
    internal fun run() {
        val (initial, replacement) = context.onClient { createScreens() }
        val outcome =
            runCatching {
                context.onClient { context.setScreen(initial) }
                context.waitFor {
                    context.currentScreen() === initial && MinecraftPresentationObservation.read(initial).inputs != null
                }
                verifyInitialActivations(initial, replacement)
                context.waitFor {
                    context.currentScreen() === replacement && MinecraftPresentationObservation.read(replacement).inputs != null
                }
                context.onClient {
                    check(pressMinecraftSpace(replacement)) { "The replacement screen's initial focus did not consume native Space." }
                    check(context.currentScreen() === replacement)
                    check(replacementActivations == 1) { "The replacement screen did not acquire initial focus." }
                    check(firstActivations == 2 && secondActivations == 1) {
                        "The detached screen retained focus or activation callbacks after replacement."
                    }
                }
            }
        runCanvasTestCleanup(
            outcome.exceptionOrNull(),
            {
                context.onClient {
                    if (context.currentScreen() === initial || context.currentScreen() === replacement) context.setScreen(null)
                }
            },
            { context.onClient { initial.close() } },
            { context.onClient { replacement.close() } },
        )
        outcome.getOrThrow()
    }

    private fun createScreens(): Pair<FabricMinecraftScreen, FabricMinecraftScreen> {
        val replacement = createReplacement()
        val initial =
            runCatching { createInitial(replacement) }
                .onFailure { failure -> runCanvasTestCleanup(failure, replacement::close) }
                .getOrThrow()
        return initial to replacement
    }

    private fun createReplacement(): FabricMinecraftScreen =
        createMinecraftScreen(
            ScreenDefinition("Keyboard replacement") {
                Column {
                    Button("Replacement", modifier = Modifier.initialFocus().onActivate { replacementActivations += 1 })
                }
            },
            profile,
            parent = null,
        )

    private fun createInitial(replacement: FabricMinecraftScreen): FabricMinecraftScreen =
        createMinecraftScreen(
            ScreenDefinition("Keyboard activation") {
                Column {
                    Button(
                        "First",
                        modifier =
                            Modifier.onActivate {
                                firstActivations += 1
                                if (firstActivations == 2) context.setScreen(replacement)
                            },
                    )
                    Button("Second", modifier = Modifier.onActivate { secondActivations += 1 })
                }
            },
            profile,
            parent = null,
        )

    private fun verifyInitialActivations(
        initial: FabricMinecraftScreen,
        replacement: FabricMinecraftScreen,
    ) {
        context.onClient {
            check(pressMinecraftTab(initial)) { "Native Tab must be consumed by Strata focus traversal." }
            check(pressMinecraftEnter(initial)) { "Native Enter must be consumed by the first Strata activation target." }
            check(firstActivations == 1 && secondActivations == 0)
            check(pressMinecraftTab(initial)) { "Native Tab must advance to the second Strata activation target." }
            check(pressMinecraftSpace(initial)) { "Native Space must be consumed by the second Strata activation target." }
            check(firstActivations == 1 && secondActivations == 1)
            check(pressMinecraftTab(initial, reverse = true)) { "Native Shift+Tab must reverse Strata focus traversal." }
            check(pressMinecraftEnter(initial)) { "Native Enter must remain consumed while its callback replaces the screen." }
            check(context.currentScreen() === replacement) { "The Strata activation callback did not install its replacement screen." }
            check(firstActivations == 2 && secondActivations == 1)
        }
    }
}
