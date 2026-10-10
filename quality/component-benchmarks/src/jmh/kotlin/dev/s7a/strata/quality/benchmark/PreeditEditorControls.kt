package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.performance.RuntimeWorkMonitor
import dev.s7a.strata.performance.WorkExpectation
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText

/**
 * Seven independent focused editor, cutoff, lifetime and retention controls completing the 28-control contract.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object PreeditEditorControls {
    /**
     * Runs the complete editor controls against the actual loaded runtime before collection.
     */
    internal fun verify(runtime: PreeditRuntime): List<String> {
        val controls = linkedMapOf<String, () -> Unit>(
            "EqualCaretRangeWork" to ::equalCaretRange,
            "CurrentRemainingCapacity" to ::replacement,
            "ImmutableStringAndBlockOwnership" to { ownership(runtime) },
            "IndependentPixelsGeometryExtrema" to ::extrema,
            "CommittedCutoffObserverRouting" to ::cutoff,
            "FailureDetachDisposeOwnerIsolation" to ::lifetime,
            "CurrentCompositionAndOldFrames" to ::history,
        )
        controls.values.forEach { it() }
        return controls.keys.toList()
    }

    private fun equalCaretRange() {
        val inputs = PreeditCase.Ascii32.inputs()
        PreeditEditor(inputs).use { editor ->
            val first = TextInputEvent.Preedit("AB", 2, listOf("A", "B"), 0)
            editor.input(first)
            val initial = editor.frame()
            val glyphs = editor.glyphCalls
            RuntimeWorkMonitor(editor.host, maxNodeRecords = 64).use { monitor ->
                monitor.checkpoint()
                repeat(64) {
                    check(editor.input(first) == InputResult.Consumed)
                    check(editor.frame() === initial)
                }
                monitor.verify(WorkExpectation(exact = mapOf("Measure" to 0, "Layout" to 0, "Paint" to 0, "Semantics" to 0).mapValues { it.value.toLong() }))
                check(editor.glyphCalls == glyphs)
                val reference = PreeditPixels("P", inputs.remaining)
                reference.input(first)
                listOf(
                    TextInputEvent.Preedit("AB", 1, listOf("A", "B"), 0),
                    TextInputEvent.Preedit("AB", 1, listOf("A", "B"), 1),
                ).forEachIndexed { index, event ->
                    monitor.checkpoint()
                    check(editor.input(event) == reference.input(event))
                    PreeditWorkEvidence.assertFrame(editor, reference, editor.frame())
                    monitor.verify(WorkExpectation(minimum = mapOf("Paint" to 1L), exact = if (index == 1) mapOf("Measure" to 0L, "Layout" to 0L, "Semantics" to 0L) else mapOf("Semantics" to 0L)))
                    check(editor.glyphCalls == glyphs)
                }
            }
        }
    }

    private fun replacement() {
        val inputs = PreeditCase.Inputs("P", 4, null, emptyList())
        PreeditEditor(inputs).use { editor ->
            val reference = PreeditPixels("P", 3)
            repeat(128) { index ->
                val raw = if (index % 2 == 0) "🙂A" else "日🙂"
                val event = TextInputEvent.Preedit(raw, raw.length, listOf(raw), 0)
                check(editor.input(event) == reference.input(event))
                PreeditWorkEvidence.assertFrame(editor, reference, editor.frame())
                check(editor.state.value.contentEquals("P"))
            }
            val old = editor.frame()
            val focus = editor.host.textInputFocus
            val glyphs = editor.glyphCalls
            check(editor.input(TextInputEvent.Preedit("🙂AA", 4, listOf("🙂AA"), 0)) == InputResult.Ignored)
            check(editor.frame() === old && editor.glyphCalls == glyphs && editor.host.textInputFocus === focus)
            editor.state.value = "PP"
            editor.frame()
            check(editor.input(TextInputEvent.Preedit("🙂A", 3, listOf("🙂A"), 0)) == InputResult.Ignored)
            check(editor.input(TextInputEvent.Preedit("🙂", 2, listOf("🙂"), 0)) == InputResult.Consumed)
            val next = PreeditPixels("PP", 2, insertion = 1)
            next.input(TextInputEvent.Preedit("🙂", 2, listOf("🙂"), 0))
            check(next.underlines() == listOf(IntRect(5, 12, 6, 13)))
            PreeditWorkEvidence.assertFrame(editor, next, editor.frame())
        }
    }

    private fun ownership(runtime: PreeditRuntime) {
        val raw = "A日🙂\nB"
        val blocks = mutableListOf(raw)
        val event = TextInputEvent.Preedit(raw, 4, blocks, 0)
        val accepted = checkNotNull(runtime.normalize(event, 6))
        val output = runtime.output(accepted)
        blocks[0] = "\u0000"
        blocks.clear()
        check(event.blocks == listOf(raw))
        check(runtime.output(accepted) == Triple(raw, 4, 0..5))
        check(output.first == raw)
        PreeditEditor(PreeditCase.Inputs("P", 7, null, emptyList())).use { editor ->
            val reference = PreeditPixels("P", 6)
            check(editor.input(event) == reference.input(event))
            PreeditWorkEvidence.assertFrame(editor, reference, editor.frame())
            PreeditRetention.inspect(editor.host, 1, 1)
        }
    }

    private fun extrema() {
        val inputs = PreeditCase.Inputs("P", 8, null, emptyList())
        val advances = mapOf('A'.code to 4, 'B'.code to -4, 'Z'.code to 0)
        PreeditEditor(inputs, advances = advances).use { editor ->
            val reference = PreeditPixels("P", 7, advances)
            listOf("AB", "Z", "AB\nAB").forEach { raw ->
                val event = TextInputEvent.Preedit(raw, raw.length, listOf(raw), 0)
                check(editor.input(event) == reference.input(event))
                val frame = editor.frame()
                PreeditWorkEvidence.assertFrame(editor, reference, frame)
                val underlines = frame.drawCommands.filterIsInstance<DrawCommand.FillRectangle>().map { it.bounds }.filter { it.height == 1 }
                check(underlines == reference.underlines())
                if (raw.length == 2) check(underlines == listOf(IntRect(5, 12, 9, 13)))
                if (raw.length == 1) check(underlines.isEmpty())
            }
        }
    }

    private fun cutoff() {
        val inputs = PreeditCase.Ascii32.inputs()
        PreeditEditor(inputs).use { editor ->
            val evaluations = editor.evaluations
            check(runCatching { editor.state.observe {} }.exceptionOrNull() is IllegalStateException)
            editor.input(inputs.events.first())
            editor.frame()
            check(editor.state.value.contentEquals("P") && editor.evaluations == evaluations)
            editor.state.value = "Q"
            check(editor.evaluations == evaluations)
            check(editor.frame().semantics.single().semantics.value == UiText.Literal("Q"))
            check(editor.evaluations == evaluations + 1)
            editor.enabled.value = false
            check(editor.input(inputs.events.first()) == InputResult.Consumed)
            check(editor.frame().semantics.single().semantics.disabled)
            check(editor.input(inputs.events.first()) == InputResult.Ignored)
            PreeditRetention.inspect(editor.host, 1, 0)
            editor.enabled.value = true
            editor.frame()
            check(editor.input(inputs.events.first()) == InputResult.Consumed)
        }
        PreeditEditor(inputs, focused = false).use { editor ->
            val initial = editor.frame()
            check(editor.host.textInputFocus == null)
            check(editor.input(inputs.events.first()) == InputResult.Ignored)
            check(editor.frame() === initial)
        }
    }

    private fun lifetime() {
        val inputs = PreeditCase.Ascii32.inputs()
        PreeditEditor(inputs).use { first ->
            first.input(inputs.events.first())
            val old = first.frame()
            PreeditEditor(inputs).use { second ->
                val firstOwner = PreeditRetention.inspect(first.host, 1, 1).single()
                val secondOwner = PreeditRetention.inspect(second.host, 1, 0).single()
                check(firstOwner !== secondOwner)
                check(runCatching { PreeditEditor(inputs, borrowedState = first.state) }.exceptionOrNull() is IllegalStateException)
                check(first.frame() === old)
                val previous = first.state
                first.replaceState(TextAreaState("Q", maxLength = 33))
                val replaced = first.frame()
                previous.observe {}.close()
                PreeditRetention.inspect(first.host, 1, 0)
                PreeditWorkEvidence.assertFrame(first, PreeditPixels("Q", 32), replaced)
                check(first.input(inputs.events.first()) == InputResult.Consumed)
                first.frame()
                first.host.detach()
                check(first.host.textInputFocus == null)
                PreeditRetention.inspect(first.host, 1, 0)
                first.host.attach()
                check(first.input(inputs.events.first()) == InputResult.Ignored)
                first.frame()
                check(first.input(inputs.events.first()) == InputResult.Consumed)
                first.frame()
                second.close()
                PreeditRetention.inspect(second.host, 0, 0)
                second.state.observe {}.close()
                check(first.frame().semantics.single().semantics.value == UiText.Literal("Q"))
            }
        }
        failureCleanup(inputs)
    }

    private fun failureCleanup(inputs: PreeditCase.Inputs) {
        val failure = IllegalStateException("Injected frame glyph failure")
        val faceFailure = IllegalStateException("Injected face cleanup failure")
        val backendFailure = IllegalStateException("Injected backend cleanup failure")
        PreeditEditor(inputs).use { editor ->
            editor.glyphFailure = failure
            editor.faceCloseFailure = faceFailure
            editor.backendCloseFailure = backendFailure
            check(editor.input(TextInputEvent.Preedit("新", 1, listOf("新"), 0)) == InputResult.Consumed)
            check(runCatching { editor.frame() }.exceptionOrNull() === failure)
            check(failure.suppressed.toList() == listOf(faceFailure))
            check(faceFailure.suppressed.toList() == listOf(backendFailure))
            check(editor.ownedResources == 0)
            PreeditRetention.inspect(editor.host, 0, 0)
            editor.state.observe {}.close()
        }
    }

    private fun history() {
        val inputs = PreeditCase.Ascii32.inputs()
        PreeditEditor(inputs).use { editor ->
            val reference = PreeditPixels("P", 32)
            val event = TextInputEvent.Preedit("A", 1, listOf("A"), 0)
            editor.input(event)
            reference.input(event)
            val old = editor.frame()
            val pixels = PreeditWorkEvidence.assertFrame(editor, reference, old)
            repeat(2_048) { index ->
                val raw = String(Character.toChars(0x10000 + index))
                val next = TextInputEvent.Preedit(raw, raw.length, listOf(raw), 0)
                check(editor.input(next) == reference.input(next))
                PreeditWorkEvidence.assertFrame(editor, reference, editor.frame())
                if (index % 64 == 0) PreeditRetention.inspect(editor.host, 1, 1)
            }
            check(rasterizeHeadless(old.drawCommands, old.size).copyArgb().contentEquals(pixels))
            check(old.semantics.single().semantics.value == UiText.Literal("P"))
            editor.input(TextInputEvent.Preedit("", 0, emptyList(), -1))
            editor.frame()
            PreeditRetention.inspect(editor.host, 1, 0)
            editor.close()
            PreeditRetention.inspect(editor.host, 0, 0)
            check(rasterizeHeadless(old.drawCommands, old.size).copyArgb().contentEquals(pixels))
            editor.state.observe {}.close()
        }
    }
}
