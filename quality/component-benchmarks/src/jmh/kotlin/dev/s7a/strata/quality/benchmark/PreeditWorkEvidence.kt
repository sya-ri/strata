package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.RuntimeWorkMonitor
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * Whole-Issue deterministic admission using independent scalars, complete pixels, work and retained ownership.
 * Optional fresh output keeps every complete-frame PNG and the 28-control/45-case receipt outside timing.
 * This is behavior evidence only; timing, surviving allocation and native/GPU metrics are never inferred.
 */
@OptIn(InternalStrataRuntimeApi::class)
public object PreeditWorkEvidence {
    /**
     * Runs the complete admission and optionally preserves fresh full-frame evidence in one absent directory.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.size <= 1)
        verify(args.singleOrNull()?.let(Path::of))
    }

    /**
     * Verifies all 86 compiled rows and all controls before either archive can enter collection.
     */
    public fun verify(output: Path? = null) {
        output?.let {
            check(Files.exists(it).not())
            Files.createDirectories(it)
        }
        val fixtures = listOf(TextAreaPreeditBenchmark::class.java, TextAreaPreeditFrameBenchmark::class.java)
        val rows = JmhWorkloadInventory.capture(fixtures, setOf("avgt"))
        check(rows.size == 86 && PreeditCase.entries.size == 45)
        val frozen = PreeditCase.entries.associateWith { it.inputs() }
        frozen.forEach { (case, inputs) ->
            check(inputs.events.size == 64)
            inputs.events.forEach { event ->
                val result = PreeditReference.normalize(event, inputs.remaining)
                check(result == null || result.text.length <= inputs.remaining)
            }
            check(case.frameOnly || inputs.edit == null)
        }
        val runtime = PreeditRuntime()
        val controls = PreeditScalarControls.verify(runtime) + PreeditEditorControls.verify(runtime)
        check(controls.size == 28 && controls.distinct().size == 28)
        val cases = JsonArray()
        frozen.forEach { (case, inputs) -> cases.add(verifyCase(runtime, case, inputs, output)) }
        val report = JsonObject().apply {
            addProperty("contract", "textarea-preedit-behavior-v1")
            addProperty("measured", false)
            addProperty("status", "passed")
            addProperty("case_count", cases.size())
            addProperty("comparison_rows", rows.size)
            addProperty("standard_cells", rows.size * 6)
            addProperty("native_timing", "N/A")
            addProperty("gpu_upload", "N/A")
            add("controls", JsonArray().apply { controls.forEach(::add) })
            add("registered_rows", JsonArray().apply { rows.sorted().forEach(::add) })
            add("cases", cases)
        }
        output?.let { Files.writeString(it.resolve("behavior.json"), report.toString(), StandardOpenOption.CREATE_NEW) }
        println(report)
    }

    private fun verifyCase(
        runtime: PreeditRuntime,
        case: PreeditCase,
        inputs: PreeditCase.Inputs,
        output: Path?,
    ): JsonObject {
        if (case.frameOnly) return verifyFrameControl(case, inputs, output)
        var shared = 0
        val outcomes = JsonArray()
        PreeditEditor(inputs).use { editor ->
            val reference = PreeditPixels(inputs.committed, inputs.remaining)
            inputs.seed?.let {
                check(editor.input(it) == reference.input(it))
                assertFrame(editor, reference, editor.frame())
            }
            RuntimeWorkMonitor(editor.host, maxNodeRecords = 64).use { monitor ->
                monitor.checkpoint()
                inputs.events.forEachIndexed { index, event ->
                    val expected = PreeditReference.normalize(event, inputs.remaining)
                    val actual = runtime.normalize(event, inputs.remaining)
                    if (expected == null) {
                        check(actual == null)
                    } else {
                        val result = runtime.output(checkNotNull(actual))
                        check(result == Triple(expected.text, expected.caret, expected.focused))
                        if (result.first === event.fullText) shared++
                    }
                    val outcome = editor.input(event)
                    check(outcome == reference.input(event)) { "Input outcome differs for $case at $index" }
                    val frame = editor.frame()
                    val pixels = assertFrame(editor, reference, frame)
                    preserve(output, "$case-$index", frame, editor)
                    outcomes.add(JsonObject().apply {
                        addProperty("event", index)
                        addProperty("consumed", outcome == InputResult.Consumed)
                        addProperty("pixels_sha256", digest(pixels))
                        addProperty("commands", frame.drawCommands.size)
                        addProperty("committed_units", editor.state.value.length)
                        add("source_expression_work", PreeditSourceWork.describe(event, inputs.remaining))
                    })
                }
                return JsonObject().apply {
                    addProperty("case", case.name)
                    addProperty("raw_units", inputs.events.first().fullText.length)
                    addProperty("remaining_units", inputs.remaining)
                    addProperty("deliveries", inputs.events.size)
                    addProperty("immutable_result_reuse", shared)
                    addProperty("glyph_calls_after_cache", editor.glyphCalls)
                    addProperty("normalizer_scans", "Required block/scalar scans remain; not instrumented surviving JVM work")
                    add("actual_editor_work", monitor.snapshot())
                    add("trace", outcomes)
                }
            }
        }
    }

    private fun verifyFrameControl(
        case: PreeditCase,
        inputs: PreeditCase.Inputs,
        output: Path?,
    ): JsonObject {
        PreeditEditor(inputs).use { editor ->
            val reference = PreeditPixels(inputs.committed, inputs.remaining)
            inputs.seed?.let {
                check(editor.input(it) == reference.input(it))
                assertFrame(editor, reference, editor.frame())
            }
            var frame = editor.frame()
            if (inputs.edit == null) {
                repeat(64) { check(editor.frame() === frame) }
                assertFrame(editor, reference, frame)
            } else {
                repeat(64) {
                    editor.state.value = inputs.committed
                    editor.frame()
                    check(editor.input(inputs.edit) == InputResult.Consumed)
                    frame = editor.frame()
                    val committed = inputs.committed + if (case == PreeditCase.CommittedNewlineEdit) "\n" else "A"
                    check(editor.state.value == committed)
                    assertFrame(editor, PreeditPixels(committed, 0), frame)
                }
            }
            preserve(output, case.name, frame, editor)
            return JsonObject().apply {
                addProperty("case", case.name)
                addProperty("frames", 64)
                addProperty("committed_units", editor.state.value.length)
                addProperty("pixels_sha256", digest(rasterizeHeadless(frame.drawCommands, frame.size).copyArgb()))
            }
        }
    }

    /**
     * Compares full pixels, independent underlines, viewport geometry, state-only semantics and vertical position.
     */
    internal fun assertFrame(
        editor: PreeditEditor,
        expected: PreeditPixels,
        frame: RuntimeUiFrame,
    ): IntArray {
        check(frame.size == editor.viewport)
        val semantics = frame.semantics.single()
        check(semantics.bounds == IntRect(0, 0, 72, 44))
        check(semantics.semantics.role == SemanticsRole.TextArea)
        check(semantics.semantics.value == UiText.Literal(editor.state.value))
        check(semantics.semantics.disabled == editor.enabled.value.not())
        check(editor.state.scrollState.metrics.offset == expected.scroll)
        val pixels = rasterizeHeadless(frame.drawCommands, editor.viewport).copyArgb()
        check(pixels.contentEquals(expected.pixels())) { "Independent complete-frame pixels differ" }
        val underlines = frame.drawCommands.filterIsInstance<DrawCommand.FillRectangle>().map { it.bounds }.filter { it.height == 1 }
        check(underlines == expected.underlines()) { "Independent underline geometry differs" }
        return pixels
    }

    private fun preserve(
        output: Path?,
        name: String,
        frame: RuntimeUiFrame,
        editor: PreeditEditor,
    ) {
        output?.let {
            Files.write(it.resolve("$name.png"), rasterizeHeadless(frame.drawCommands, editor.viewport).encodePng(), StandardOpenOption.CREATE_NEW)
        }
    }

    /**
     * Stable fingerprint of the complete immutable physical ARGB output, independent of runtime PNG encoding.
     */
    internal fun digest(pixels: IntArray): String {
        val bytes = ByteBuffer.allocate(pixels.size * Int.SIZE_BYTES)
        pixels.forEach(bytes::putInt)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
    }
}
