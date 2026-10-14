package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import dev.s7a.strata.performance.JmhPerformanceRunner
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.JvmOperationReportAdapter
import dev.s7a.strata.performance.JvmOperationReportRow
import dev.s7a.strata.performance.JvmPerformanceRunner
import dev.s7a.strata.performance.PerformancePlan
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontSnapshot
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.nio.file.Path

/**
 * Uses the shared owner-thread meter for all nine original operations and sixteen original scenarios.
 * Fixture setup, reference pixels, assertions and surviving-owner cleanup remain outside samples.
 * Each lifecycle operation retains its original opening, queries, frame extraction and close boundary.
 */
@OptIn(InternalStrataRuntimeApi::class)
public object UnihexOverrideOperationReport : JvmOperationReportAdapter {
    // Both font corpora consume the same registered component control files and original external inputs.
    override val inputs: Map<String, Path> get() = FontFallbackOperationReport.inputs

    private enum class Operation(val method: String, val boundary: String) {
        Glyphs("glyphs", "Resolve the complete prepared scalar batch in its original order."),
        Warm("warm", "Resolve the first prepared scalar once."),
        FirstUse("firstUse", "Open an engine, resolve the first scalar and close the engine."),
        Load("load", "Load the complete immutable snapshot from prepared resource bytes."),
        Lifecycle("lifecycle", "Open an engine, resolve the complete scalar batch and close the engine."),
        CleanFrame("cleanFrame", "Extract an unchanged retained Text frame at the original timestamp."),
        DirtyFrame("dirtyFrame", "Publish the next original text, perform Text layout and extract its frame."),
        TextLifecycle("textLifecycle", "Open and attach an independent host, extract its first Text frame and close."),
        ReplaceSnapshot("replaceSnapshot", "Load and replace the snapshot and profile, extract an independent first Text frame and close."),
    }

    override val rows: List<JvmOperationReportRow> =
        Operation.entries.flatMap { operation -> UnihexOverrideScenario.entries.map { scenario -> row(operation, scenario) } }

    /** Requires exact method and scenario coverage from the real generated JMH registry. */
    internal fun verifyInventory() {
        val actual = JmhWorkloadInventory.capture(listOf(UnihexOverrideBenchmark::class.java), setOf("avgt"))
        val declared = rows.map { JmhPerformanceRunner.workloadIdentity(it.benchmark, "avgt", it.parameters) }.toSet()
        check(rows.size == 144 && rows.map { it.id }.toSet().size == rows.size && declared == actual)
    }

    override fun collect(plan: PerformancePlan): Map<String, JsonObject> {
        verifyInventory()
        return Operation.entries.flatMap { operation ->
            UnihexOverrideScenario.entries.map { scenario -> row(operation, scenario).id to collectRow(operation, scenario, plan) }
        }.toMap()
    }

    private fun collectRow(
        operation: Operation,
        scenario: UnihexOverrideScenario,
        plan: PerformancePlan,
    ): JsonObject {
        val owner = UnihexOverrideBenchmark.OverrideSession().apply { this.scenario = scenario }
        owner.setup()
        return try {
            val trace = owner.trace()
            val reversed = pixels(owner.dirtyFrame())
            val original = pixels(owner.dirtyFrame())
            JvmPerformanceRunner.measure(
                row(operation, scenario).id,
                plan,
                afterOperation = { index, result ->
                    when (result) {
                        is MinecraftFontGlyph -> equalGlyph(if (operation == Operation.Warm || operation == Operation.FirstUse) trace.first() else trace.last(), result)
                        is MinecraftFontSnapshot -> check(result.diagnostics.isEmpty())
                        is RuntimeUiFrame -> {
                            val expected = if (operation == Operation.DirtyFrame && (plan.warmup + index) % 2 == 0) reversed else original
                            check(pixels(result).contentEquals(expected))
                        }
                        else -> error("Unexpected original Unihex operation result")
                    }
                    val retained = owner.retained
                    check(retained[0] <= scenario.cacheEntries && retained[1] <= 1024 * 1024L && retained[2] == 0L)
                    check(owner.subscriptions == 1)
                },
            ) {
                invoke(operation, owner)
            }.evidence
        } finally {
            owner.close()
            UnihexOverrideWorkEvidence.verifyTerminalOwner(owner)
        }
    }

    private fun invoke(
        operation: Operation,
        owner: UnihexOverrideBenchmark.OverrideSession,
    ): Any =
        when (operation) {
            Operation.Glyphs -> owner.glyphs()
            Operation.Warm -> owner.warm()
            Operation.FirstUse -> owner.firstUse()
            Operation.Load -> owner.load()
            Operation.Lifecycle -> owner.lifecycle()
            Operation.CleanFrame -> owner.cleanFrame()
            Operation.DirtyFrame -> owner.dirtyFrame()
            Operation.TextLifecycle -> owner.textLifecycle()
            Operation.ReplaceSnapshot -> owner.replaceSnapshot()
        }

    private fun row(
        operation: Operation,
        scenario: UnihexOverrideScenario,
    ): JvmOperationReportRow =
        JvmOperationReportRow(
            "${operation.method}-${scenario.name}",
            "${UnihexOverrideBenchmark::class.java.name}.${operation.method}",
            mapOf("scenario" to scenario.name),
            operation.boundary,
        )

    private fun equalGlyph(
        expected: MinecraftFontGlyph,
        actual: MinecraftFontGlyph,
    ) {
        check(expected.copy(image = null) == actual.copy(image = null))
        check(expected.image?.size == actual.image?.size)
        check(expected.image?.copyArgb()?.contentEquals(actual.image?.copyArgb()) ?: (actual.image == null))
    }

    private fun pixels(frame: RuntimeUiFrame): IntArray = rasterizeHeadless(frame.drawCommands, frame.size).copyArgb()
}
