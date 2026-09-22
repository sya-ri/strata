package dev.s7a.strata.integration.paper

import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextArea
import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.component.TextAreaViewport
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.menuBackground
import dev.s7a.strata.modifier.onActivate
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.runtime.paper.PaperScreens
import dev.s7a.strata.runtime.remote.RemoteSessionStatus
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.state.mutableStateOf
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.nio.file.Files
import java.util.UUID

/**
 * Real Paper-owned multiline editor for manual OS IME verification with concurrent server revisions.
 * Only an operator-confirmed Japanese phrase, newline, and explicit Finish action can complete this fixture.
 */
internal class PaperManualImeSession(
    private val plugin: Plugin,
    private val player: Player,
) : PaperVerificationSession {
    override val playerId: UUID = player.uniqueId
    private val draft = TextAreaState(maxLength = 128)
    private val updates = mutableStateOf(0)
    private var ticks = 0
    private var completed = false
    private var closed = false
    private var handle =
        PaperScreens.open(
            plugin,
            player,
            ScreenDefinition("Strata remote OS IME verification") {
                Column(Modifier.Empty.menuBackground().padding(8), spacing = 8) {
                    Text("Japanese IME: type nihongo, convert, confirm.")
                    Text("Keep composition active while updates advance.")
                    Text("Updates: ${updates.value}")
                    TextArea(draft, TextAreaViewport.Size(IntSize(304, 72)))
                    Text("Insert an Enter newline, then finish.")
                    Button("Finish test", enabled = draft.value.contains("日本語") && draft.value.contains('\n'), modifier = Modifier.Empty.onActivate { completed = true })
                }
            },
        )

    override fun tick(): Boolean {
        if (closed) return true
        if (player.isOnline.not() || handle.status is RemoteSessionStatus.Closed || 18000 <= ticks++) {
            close()
            return true
        }
        if (ticks % 10 == 0) updates.value++
        if (completed.not()) return false
        check(draft.value.contains("日本語") && draft.value.contains('\n') && 1 < updates.value)
        val run = requireNotNull(System.getProperty("strata.paper.run"))
        val output = plugin.dataFolder.toPath()
        Files.createDirectories(output)
        Files.writeString(output.resolve("manual-server.properties"), "runId=$run\ndraft=${draft.value.replace("\n", "\\n")}\nupdates=${updates.value}\ninput=operator-confirmed\n")
        handle = PaperScreens.open(plugin, player, ScreenDefinition("Strata remote OS IME complete") { Column { Text("IME input confirmed on Paper.") } })
        closed = true
        return true
    }

    override fun close() {
        closed = true
        handle.close()
    }
}
