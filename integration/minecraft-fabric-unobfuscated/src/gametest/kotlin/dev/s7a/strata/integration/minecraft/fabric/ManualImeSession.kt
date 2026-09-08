package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextArea
import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.component.TextAreaViewport
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.menuBackground
import dev.s7a.strata.modifier.onActivate
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.diagnostics.UiRenderMonitor
import dev.s7a.strata.runtime.diagnostics.UiRenderNodeId
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.extractMinecraftUiProfile
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.map
import net.minecraft.client.Minecraft
import java.nio.file.Files
import java.nio.file.Path

/**
 * Client-thread-owned interactive fixture with continuous label updates around a stable multiline editor.
 * The operator verifies native composition visually; Finish requires a converted Japanese phrase and an Enter newline.
 * It writes only this synthetic draft and primitive evidence to the explicitly configured build output, then exits.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class ManualImeSession(
    private val minecraft: Minecraft,
) {
    private val source = ReactiveRenderSource(0)
    private val draft = TextAreaState()
    private val label = source.map { "Updates: $it" }
    private var completed = false
    private var ticks = 0
    private var closed = false
    private var monitor: UiRenderMonitor? = null
    private var editorIds: List<UiRenderNodeId> = emptyList()
    private val screen =
        createMinecraftScreen(
            ScreenDefinition("Strata OS IME verification") {
                Column(
                    Modifier.Empty
                        .size(320, 180)
                        .menuBackground()
                        .padding(8),
                    spacing = 8,
                ) {
                    Text("Japanese IME: type nihongo, convert, confirm.")
                    Text(label)
                    TextArea(draft, TextAreaViewport.Size(IntSize(304, 72)), key = ElementKey("manual-ime-editor"))
                    Button("Finish test", modifier = Modifier.Empty.onActivate { completed = true })
                }
            },
            extractMinecraftUiProfile(),
            parent = null,
        )

    init {
        minecraft.options.guiScale().set(2)
        minecraft.resizeGui()
        MinecraftClientScreenAccess.setScreen(minecraft, screen)
    }

    /**
     * Updates independent text while normal OS callbacks own all editor input; timeout fails after 15 minutes.
     */
    fun tick() {
        if (closed) return
        ticks += 1
        if (ticks == 4) {
            monitor = screen.startRenderMonitoring()
            editorIds = checkNotNull(monitor).findNodes(ElementKey("manual-ime-editor"))
            check(editorIds.isNotEmpty())
        }
        if (ticks % 10 == 0) source.publish(ticks / 10)
        check(ticks < 18_000) { "Timed out awaiting manual OS IME verification." }
        if (completed) finish()
    }

    private fun finish() {
        val collector = checkNotNull(monitor)
        try {
            check(draft.value.contains("日本語") && draft.value.contains('\n')) { "Confirm Japanese conversion and a literal Enter newline before finishing." }
            check(collector.findNodes(ElementKey("manual-ime-editor")) == editorIds)
            val snapshot = collector.snapshot()
            check(snapshot.overflowed.not())
            check(0L < snapshot.counts.getValue(UiRenderMetric.StateComponentEvaluation))
            val output = Path.of(checkNotNull(System.getProperty("strata.ime.output")))
            Files.createDirectories(output)
            val runId = checkNotNull(System.getProperty("strata.ime.runId"))
            Files.writeString(output.resolve("manual-os-ime.txt"), "runId=$runId\ndraft=${draft.value.replace("\n", "\\n")}\nupdates=${ticks / 10}\neditorIdentity=retained\ninput=OS-keyboard-only\n")
        } finally {
            closed = true
            collector.close()
            monitor = null
            editorIds = emptyList()
            screen.onClose()
            minecraft.stop()
        }
    }
}
