package dev.s7a.strata.integration.docs.example

import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.PlayerHead
import dev.s7a.strata.component.PlayerHeadScale
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.UiScope
import dev.s7a.strata.layout.HorizontalAlignment.Start
import dev.s7a.strata.layout.VerticalAlignment.Center
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.fillMaxWidth

/**
 * Reuses the completed player row, filling its parent list width, while the final chapters change list size and scrolling.
 * Reads caller-owned immutable presentation data on the active host thread and propagates layout failures.
 */
internal fun UiScope.playerRow(
    player: ReadmePlayer,
    rowModifier: Modifier,
) {
    Row(
        modifier = rowModifier.fillMaxWidth(),
        spacing = 8,
        verticalAlignment = Center,
    ) {
        PlayerHead(player.skin, PlayerHeadScale(3))
        Column(
            modifier = Modifier.Empty.weight(1f),
            spacing = 4,
            horizontalAlignment = Start,
        ) {
            Text(player.name)
            Text(player.role)
        }
        Button("Invite", width = 60)
    }
}
