package dev.s7a.strata.integration.docs.skill

import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextArea
import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.component.TextAreaViewport
import dev.s7a.strata.component.VirtualList
import dev.s7a.strata.component.VirtualListState
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onActivate
import dev.s7a.strata.modifier.size
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.map
import dev.s7a.strata.text.TextLayout

/**
 * Immutable message snapshot; construction performs no validation or resource acquisition.
 * Instances may be published across threads through the caller's history source.
 *
 * @property id immutable identity, unique within every history snapshot and stable across revisions.
 * @property preview text displayed in the message's fixed-height history row.
 */
public data class InboxMessage(
    public val id: Long,
    public val preview: String,
)

/**
 * Creates one automatically updating messaging screen with a 160-by-160 logical-pixel content area.
 * Clock and loading rows occupy 12 pixels each, history 60, draft 40, and the button 20, with four 4-pixel gaps.
 *
 * Call on the UI owner thread shared by [draft]; the returned one-shot definition is opened on that thread.
 * Inputs and draft remain caller-owned. Publish immutable history snapshots with unique, stable message IDs.
 * The definition retains its mapped sources and one navigation state outside every evaluation callback.
 * Direct source bindings update the clock, history, and button independently; only loading changes child structure.
 * The editor stays attached so unrelated updates preserve its draft, focus, cursor, and active composition.
 * History keeps its stable-key scroll anchor when that message remains present.
 *
 * [clockMinutes] is interpreted as minutes from midnight, wrapped to a 24-hour HH:MM display.
 * [sending] controls both the button label and its visual and input eligibility.
 * [loading] controls a loading label inside a reserved row so the editor does not move.
 * [onSend] runs on the UI owner thread when activation is enabled; application failures propagate to the host.
 * Source and attachment failures follow the public Strata lifecycle contracts; an attached [draft] cannot be shared with another editor.
 */
public fun inboxScreen(
    clockMinutes: StateSource<Long>,
    sending: StateSource<Boolean>,
    history: StateSource<List<InboxMessage>>,
    loading: StateSource<Boolean>,
    draft: TextAreaState,
    onSend: () -> Unit,
): ScreenDefinition {
    val clockLabel =
        clockMinutes.map { minutes ->
            val minuteOfDay = ((minutes % 1_440L) + 1_440L) % 1_440L
            val hour = (minuteOfDay / 60L).toString().padStart(2, '0')
            val minute = (minuteOfDay % 60L).toString().padStart(2, '0')
            "$hour:$minute"
        }
    val sendLabel = sending.map { active -> if (active) "Sending..." else "Send" }
    val sendEnabled = sending.map { active -> active.not() }
    val historyState = VirtualListState<Long>()

    return ScreenDefinition("Inbox") {
        Column(
            modifier = Modifier.Empty.size(160, 160),
            spacing = 4,
        ) {
            Text(clockLabel, layout = TextLayout.Multiline(), modifier = Modifier.Empty.size(160, 12))
            Observe(loading, modifier = Modifier.Empty.size(160, 12)) { active ->
                if (active) {
                    Text("Loading...", layout = TextLayout.Multiline())
                }
            }
            VirtualList(
                items = history,
                keyOf = { message -> message.id },
                state = historyState,
                viewportSize = IntSize(160, 60),
                rowHeight = 12,
            ) { message ->
                Text(message.preview)
            }
            TextArea(
                state = draft,
                viewport = TextAreaViewport.Size(IntSize(160, 40)),
            )
            Button(
                label = sendLabel,
                width = 160,
                enabled = sendEnabled,
                modifier = Modifier.Empty.size(160, 20).onActivate(sendEnabled, onSend),
            )
        }
    }
}
