package dev.s7a.strata.integration.docs

import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.integration.docs.example.ReadmePlayer
import dev.s7a.strata.integration.docs.example.actionsPlayersScreen
import dev.s7a.strata.integration.docs.example.areaPlayersScreen
import dev.s7a.strata.integration.docs.example.basicPlayersScreen
import dev.s7a.strata.integration.docs.example.fixedPlayersScreen
import dev.s7a.strata.integration.docs.example.rolesPlayersScreen
import dev.s7a.strata.integration.docs.example.scrollPlayersScreen
import dev.s7a.strata.integration.docs.example.weightedPlayersScreen
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Immutable storyboard coupling each compiled factory to its exact source, inputs, wheel positions, and timing.
 * Factories transfer fresh definitions to their caller; no host or mutable scroll state is retained here.
 *
 * @property title heading used only in the static walkthrough.
 * @property caption static explanation, deliberately absent from the GIF.
 * @property sourceName compiled example basename.
 * @property durationCentiseconds total chapter duration.
 * @property create creates a fresh caller-owned screen from read-only players and panel pixels on the host thread.
 * @property scrollOffsets logical wheel displacements sampled in order, with no interpolated screen movement.
 */
internal enum class ReadmeDemoStage(
    val title: String,
    val caption: String,
    val sourceName: String,
    val durationCentiseconds: Int,
    val create: (List<ReadmePlayer>, ImageSource) -> ScreenDefinition,
    val scrollOffsets: List<Int> = listOf(0),
) {
    Basic("Start with a row", "Each row takes the width of its contents.", "BasicPlayersExample", 200, ::basicPlayersScreen),

    Roles("Add secondary text", "One Text adds a role to every player.", "RolesPlayersExample", 250, ::rolesPlayersScreen),

    Actions("Add a button", "One Button extends every row without calculating its position.", "ActionsPlayersExample", 250, ::actionsPlayersScreen),

    Fixed("Fix the list width", "Set the outer Column width once; every row fills it.", "FixedPlayersExample", 250, ::fixedPlayersScreen),

    Weighted("Give text the remaining space", "Weight expands the text column and lines up the buttons.", "WeightedPlayersExample", 250, ::weightedPlayersScreen),

    Area("Add a scroll area", "Wrap the existing Column in ScrollArea to contain the list.", "AreaPlayersExample", 150, ::areaPlayersScreen),

    AreaBottom("Scroll without a bar", "Wheel input reaches the final players; no scrollbar has been added yet.", "AreaPlayersExample", 75, ::areaPlayersScreen, listOf(208)),

    Bar("Add a linked scrollbar", "Pass the same ScrollState to Scrollbar; its thumb reflects the list position.", "ScrollPlayersExample", 150, ::scrollPlayersScreen, listOf(208)),

    BarTop("Scroll up together", "The list and linked scrollbar move together under wheel input.", "ScrollPlayersExample", 125, ::scrollPlayersScreen, listOf(168, 126, 84, 42, 0)),

    BarBottom("Scroll down together", "The linked thumb follows the list back to its last player.", "ScrollPlayersExample", 225, ::scrollPlayersScreen, listOf(42, 84, 126, 168, 208, 208)),
}
