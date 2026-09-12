package dev.s7a.strata.integration.docs

import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.integration.docs.example.ReadmePlayer
import dev.s7a.strata.integration.docs.example.actionsPlayersScreen
import dev.s7a.strata.integration.docs.example.areaPlayersScreen
import dev.s7a.strata.integration.docs.example.basicPlayersScreen
import dev.s7a.strata.integration.docs.example.eightPlayersScreen
import dev.s7a.strata.integration.docs.example.fivePlayersScreen
import dev.s7a.strata.integration.docs.example.fixedPlayersScreen
import dev.s7a.strata.integration.docs.example.fourPlayersScreen
import dev.s7a.strata.integration.docs.example.leftPlayersScreen
import dev.s7a.strata.integration.docs.example.rightPlayersScreen
import dev.s7a.strata.integration.docs.example.rolesPlayersScreen
import dev.s7a.strata.integration.docs.example.scrollPlayersScreen
import dev.s7a.strata.integration.docs.example.sevenPlayersScreen
import dev.s7a.strata.integration.docs.example.sixPlayersScreen
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
 * @property playerCount prefix of the fixed offline roster passed to the compiled example.
 * @property scrollOffsets logical wheel displacements sampled in order, with no interpolated screen movement.
 */
internal enum class ReadmeDemoStage(
    val title: String,
    val caption: String,
    val sourceName: String,
    val durationCentiseconds: Int,
    val create: (List<ReadmePlayer>, ImageSource) -> ScreenDefinition,
    val playerCount: Int = 3,
    val scrollOffsets: List<Int> = listOf(0),
) {
    Basic("Start with a row", "Each row takes the width of its contents.", "BasicPlayersExample", 200, ::basicPlayersScreen),

    Roles("Add secondary text", "One Text adds a role to every player.", "RolesPlayersExample", 250, ::rolesPlayersScreen),

    Actions("Add a button", "One Button extends every row without calculating its position.", "ActionsPlayersExample", 250, ::actionsPlayersScreen),

    Fixed("Fix the list width", "Set the outer Column width once; every row fills it.", "FixedPlayersExample", 250, ::fixedPlayersScreen),

    Weighted("Give text the remaining space", "Weight expands the text column and lines up the buttons.", "WeightedPlayersExample", 250, ::weightedPlayersScreen),

    Right("Align text to the right", "End aligns names and roles; row frames, faces, and buttons stay still.", "RightPlayersExample", 200, ::rightPlayersScreen),

    Left("Align text to the left", "Start moves only the text back to the left.", "LeftPlayersExample", 150, ::leftPlayersScreen),

    Four("Add a fourth player", "The same row composition is reused as the list starts to overflow.", "FourPlayersExample", 25, ::fourPlayersScreen, 4),

    Five("Add a fifth player", "Players arrive one at a time.", "FivePlayersExample", 25, ::fivePlayersScreen, 5),

    Six("Add a sixth player", "The list is now taller than the screen.", "SixPlayersExample", 25, ::sixPlayersScreen, 6),

    Seven("Add a seventh player", "Additional rows cannot be reached yet.", "SevenPlayersExample", 25, ::sevenPlayersScreen, 7),

    Eight("Add an eighth player", "Eight players now need a scroll viewport.", "EightPlayersExample", 25, ::eightPlayersScreen, 8),

    Area("Add a scroll area", "Wrap the existing Column in ScrollArea to contain the list.", "AreaPlayersExample", 150, ::areaPlayersScreen, 8),

    AreaBottom("Scroll without a bar", "Wheel input reaches the final players; no scrollbar has been added yet.", "AreaPlayersExample", 75, ::areaPlayersScreen, 8, listOf(208)),

    Bar("Add a linked scrollbar", "Pass the same ScrollState to Scrollbar; its thumb reflects the list position.", "ScrollPlayersExample", 150, ::scrollPlayersScreen, 8, listOf(208)),

    BarTop("Scroll up together", "The list and linked scrollbar move together under wheel input.", "ScrollPlayersExample", 125, ::scrollPlayersScreen, 8, listOf(168, 126, 84, 42, 0)),

    BarBottom("Scroll down together", "The linked thumb follows the list back to its last player.", "ScrollPlayersExample", 225, ::scrollPlayersScreen, 8, listOf(42, 84, 126, 168, 208, 208)),
}
