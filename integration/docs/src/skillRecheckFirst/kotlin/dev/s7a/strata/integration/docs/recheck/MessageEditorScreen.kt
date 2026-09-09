package dev.s7a.strata.integration.docs.recheck

import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextArea
import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.component.TextAreaViewport
import dev.s7a.strata.component.VirtualList
import dev.s7a.strata.component.VirtualListState
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.menuBackground
import dev.s7a.strata.modifier.onActivate
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.map
import dev.s7a.strata.text.TextLayout
import dev.s7a.strata.text.TextOverflow

/**
 * Returns an API-only, one-shot message editor that retains its editor and history navigation across source updates.
 *
 * Create and present the definition on the editor state's owner thread; input actions execute on that thread.
 * Sources and the draft remain caller-owned, and the caller publishes immutable history snapshots with unique IDs.
 * The definition retains its own list state, while loading never disables the editor or removes the history viewport.
 * Source and action failures propagate through the installed runtime's ordinary cleanup contract.
 * The 200 by 180 panel has four-pixel padding and four gaps: 12 + 44 + 20 + 12 + 68 + 16 + 8 = 180.
 *
 * @param clockSeconds non-negative clock counter, displayed as completed minutes without a time-zone conversion.
 * @param sending whether both visual and interactive send-button behavior are disabled.
 * @param loading whether the reserved history-status line displays a loading indication.
 * @param history immutable message snapshots; stable IDs preserve the visible anchor when rows are prepended.
 * @param draft caller-owned multiline value, cursor, composition, and editor scrolling state.
 * @param send owner-thread action invoked only while the send button is enabled.
 * @return unopened screen definition; call its public open method through an installed matching runtime.
 */
public fun messageEditorScreen(
    clockSeconds: StateSource<Long>,
    sending: StateSource<Boolean>,
    loading: StateSource<Boolean>,
    history: StateSource<List<HistoryEntry>>,
    draft: TextAreaState,
    send: () -> Unit,
): ScreenDefinition {
    val clockLabel = clockSeconds.map { seconds -> "Clock: ${seconds / 60} min" }
    val sendEnabled = sending.map { active -> active.not() }
    val sendLabel = sending.map { active -> if (active) "Sending..." else "Send" }
    val loadingLabel = loading.map { active -> if (active) "Loading history..." else "History" }
    val historyState = VirtualListState<Long>()
    val lineLayout = TextLayout.Multiline(maxLines = 1, overflow = TextOverflow.Ellipsis)

    return ScreenDefinition("Message editor") {
        Column(
            modifier =
                Modifier.Empty
                    .size(200, 180)
                    .menuBackground()
                    .padding(4),
            spacing = 4,
        ) {
            Text(clockLabel, layout = lineLayout, modifier = Modifier.Empty.size(192, 12))
            TextArea(state = draft, viewport = TextAreaViewport.Size(IntSize(192, 44)))
            Button(
                label = sendLabel,
                width = 192,
                enabled = sendEnabled,
                modifier = Modifier.Empty.onActivate(sendEnabled, send),
            )
            Text(loadingLabel, layout = lineLayout, modifier = Modifier.Empty.size(192, 12))
            VirtualList(
                items = history,
                keyOf = { entry -> entry.id },
                state = historyState,
                viewportSize = IntSize(192, 68),
                rowHeight = 12,
            ) { entry ->
                Text(
                    text = "${entry.id}: ${entry.text}",
                    layout = lineLayout,
                    modifier = Modifier.Empty.size(192, 12),
                )
            }
        }
    }
}
