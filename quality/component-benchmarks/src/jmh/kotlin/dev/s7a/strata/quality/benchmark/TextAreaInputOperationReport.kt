package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.performance.JmhPerformanceRunner
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.JvmOperationReportAdapter
import dev.s7a.strata.performance.JvmOperationReportRow
import dev.s7a.strata.performance.JvmPerformanceRunner
import dev.s7a.strata.performance.PerformancePlan
import dev.s7a.strata.quality.benchmark.TextAreaInputBenchmark.Operation
import dev.s7a.strata.quality.benchmark.TextAreaInputBenchmark.Point
import dev.s7a.strata.quality.benchmark.TextAreaInputBenchmark.Shape
import dev.s7a.strata.quality.benchmark.TextAreaInputBenchmark.Workload
import dev.s7a.strata.runtime.minecraft.MinecraftUiHost
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import java.nio.file.Path

/**
 * Measures all 60 original logical input, layout, clean-frame and lookup boundaries with the shared owner-thread meter.
 * Original scene setup and complete independent oracles run before sampling; assertions, coordinate diagnostics and surviving-owner close remain outside samples.
 * Fresh initial-layout ownership remains inside its original operation, and failure or cleanup rejection prevents a complete report.
 */
@OptIn(InternalStrataRuntimeApi::class)
public object TextAreaInputOperationReport : JvmOperationReportAdapter {
    override val inputs: Map<String, Path>
        get() = cleanupOperationInputs()

    private enum class LayoutOperation(
        val method: String,
        val boundary: String,
    ) {
        InitialLayout("initialLayout", "Create, attach, lay out and close one fresh host using already prepared resource bytes."),
        Clean("clean", "Return one unchanged frame from the prepared retained host."),
    }

    override val rows: List<JvmOperationReportRow> =
        Workload.entries.map(::inputRow) +
            LayoutOperation.entries.flatMap { operation -> Shape.entries.map { shape -> layoutRow(operation, shape) } } +
            Shape.entries.flatMap { shape -> Point.entries.map { point -> lookupRow(shape, point) } }

    /**
     * Requires every original generated method/parameter combination in both modes and six unchanged clean-frame controls.
     */
    public fun verifyRows() {
        val expected = JmhWorkloadInventory.capture(listOf(TextAreaInputBenchmark::class.java), setOf("avgt", "sample"))
        val actual =
            rows.flatMap { row ->
                listOf("avgt", "sample").map { mode -> JmhPerformanceRunner.workloadIdentity(row.benchmark, mode, row.parameters) }
            }
        check(rows.size == 60 && rows.map { it.id }.distinct().size == 60)
        check(actual.size == 120 && actual.toSet() == expected)
        check(rows.count { it.control } == 6 && rows.all { it.operationsPerSample == 1 })
    }

    override fun collect(plan: PerformancePlan): Map<String, JsonObject> {
        verifyRows()
        val reports = linkedMapOf<String, JsonObject>()
        Workload.entries.forEach { workload -> reports.putAll(inputReport(workload, plan)) }
        LayoutOperation.entries.forEach { operation -> Shape.entries.forEach { shape -> reports.putAll(layoutReport(operation, shape, plan)) } }
        Shape.entries.forEach { shape -> Point.entries.forEach { point -> reports.putAll(lookupReport(shape, point, plan)) } }
        check(reports.keys == rows.map { it.id }.toSet())
        return reports
    }

    private fun inputReport(
        workload: Workload,
        plan: PerformancePlan,
    ): Map<String, JsonObject> {
        val scene = TextAreaInputBenchmark.InputScene().apply { this.workload = workload }
        return AutoCloseable { scene.close() }.use { _ ->
            scene.setUp()
            val fixture = TextAreaInputAccess.field(scene, "fixture") as TextAreaInputFixture
            val host = TextAreaInputAccess.field(fixture, "host") as MinecraftUiHost
            val cursor = TextAreaInputAccess.field(TextAreaInputAccess.editor(host), "cursor")
            val expectedOffset = TextAreaInputAccess.field(cursor, "offset") as Int
            val state = TextAreaInputAccess.field(fixture, "state") as TextAreaState
            val committed = state.value
            val focus = host.textInputFocus
            val expectedResult = if (workload.operation === Operation.Primary) InputResult.Ignored else InputResult.Consumed
            val benchmark = TextAreaInputBenchmark()
            var last: InputResult = InputResult.Ignored
            val id = inputRow(workload).id
            JvmPerformanceRunner.warmup(plan.warmup) { last = benchmark.input(scene) }
            JvmPerformanceRunner.sequence(plan.samples, mapOf(id to plan.samples)) { _, sequence ->
                sequence.sample(id) { last = benchmark.input(scene) }
                check(last === expectedResult)
                check(TextAreaInputAccess.field(cursor, "offset") == expectedOffset)
                check(state.value == committed && host.textInputFocus === focus)
            }
        }
    }

    private fun layoutReport(
        operation: LayoutOperation,
        shape: Shape,
        plan: PerformancePlan,
    ): Map<String, JsonObject> {
        val scene = TextAreaInputBenchmark.LayoutScene().apply { this.shape = shape }
        return AutoCloseable { scene.close() }.use { _ ->
            scene.setUp()
            val fixture = TextAreaInputAccess.field(scene, "fixture") as TextAreaInputFixture
            val committed = TextAreaInputAccess.field(fixture, "value") as String
            val expected = scene.clean()
            val calls = TextAreaInputAccess.field(fixture, "glyphCalls") as Int
            val benchmark = TextAreaInputBenchmark()
            var last: RuntimeUiFrame = expected
            val id = layoutRow(operation, shape).id
            JvmPerformanceRunner.warmup(plan.warmup) { last = layout(operation, benchmark, scene) }
            JvmPerformanceRunner.sequence(plan.samples, mapOf(id to plan.samples)) { _, sequence ->
                sequence.sample(id) { last = layout(operation, benchmark, scene) }
                when (operation) {
                    LayoutOperation.Clean -> {
                        check(last === expected)
                        check(TextAreaInputAccess.field(fixture, "glyphCalls") == calls)
                    }

                    LayoutOperation.InitialLayout -> {
                        check(
                            last.semantics
                                .single { entry -> entry.semantics.role === SemanticsRole.TextArea }
                                .semantics.value == UiText.Literal(committed),
                        )
                        check(TextAreaInputAccess.field(fixture, "backends") == 1 && TextAreaInputAccess.field(fixture, "faces") == 1)
                    }
                }
            }
        }
    }

    private fun lookupReport(
        shape: Shape,
        point: Point,
        plan: PerformancePlan,
    ): Map<String, JsonObject> {
        val scene =
            TextAreaInputBenchmark.LookupScene().apply {
                this.shape = shape
                this.point = point
            }
        return AutoCloseable { scene.close() }.use { _ ->
            scene.setUp()
            val fixture = TextAreaInputAccess.field(scene, "fixture") as TextAreaInputFixture
            val line = fixture.currentLine()
            val x = TextAreaInputAccess.field(scene, "x") as Int
            val expected = TextAreaInputAccess.nearest(line, x)
            val calls = TextAreaInputAccess.field(fixture, "glyphCalls") as Int
            val benchmark = TextAreaInputBenchmark()
            var last = 0
            val id = lookupRow(shape, point).id
            JvmPerformanceRunner.warmup(plan.warmup) { last = benchmark.lookup(scene) }
            JvmPerformanceRunner
                .sequence(plan.samples, mapOf(id to plan.samples)) { _, sequence ->
                    sequence.sample(id) { last = benchmark.lookup(scene) }
                    check(last == expected && TextAreaInputAccess.field(fixture, "glyphCalls") == calls)
                    TextAreaInputAccess.verifyVisits(line, x)
                }.also { reports -> reports.getValue(id).add("untimed_lookup_work", lookupWork(line, x)) }
        }
    }

    private fun lookupWork(
        line: Any,
        x: Int,
    ): JsonObject {
        val methods = line.javaClass.declaredMethods.filter { method -> method.name.startsWith("boundaryVisits") }
        check(methods.size <= 1)
        val positions = TextAreaInputAccess.field(line, "positions") as IntArray
        val method = methods.singleOrNull()
        val observed = method?.apply { isAccessible = true }?.invoke(line, x) as Int?
        return JsonObject().apply {
            addProperty("scope", "One actual coordinate diagnostic invocation after sampling; not a timed call count")
            addProperty("x", x)
            addProperty("current_line_boundaries", positions.size)
            addProperty("nondecreasing_coordinates", (1 until positions.size).all { index -> positions[index - 1] <= positions[index] })
            addProperty("observed_coordinate_reads", observed)
            addProperty("diagnostic_available", method != null)
        }
    }

    private fun layout(
        operation: LayoutOperation,
        benchmark: TextAreaInputBenchmark,
        scene: TextAreaInputBenchmark.LayoutScene,
    ): RuntimeUiFrame =
        when (operation) {
            LayoutOperation.InitialLayout -> benchmark.initialLayout(scene)
            LayoutOperation.Clean -> benchmark.clean(scene)
        }

    private fun inputRow(workload: Workload): JvmOperationReportRow =
        JvmOperationReportRow(
            "input-${workload.name}",
            "${TextAreaInputBenchmark::class.java.name}.input",
            mapOf("workload" to workload.name),
            "Restore the original public input opportunity, settle the frame and dispatch the prebuilt target event once.",
        )

    private fun layoutRow(
        operation: LayoutOperation,
        shape: Shape,
    ): JvmOperationReportRow =
        JvmOperationReportRow(
            "${operation.method}-${shape.name}",
            "${TextAreaInputBenchmark::class.java.name}.${operation.method}",
            mapOf("shape" to shape.name),
            operation.boundary,
            control = operation === LayoutOperation.Clean,
        )

    private fun lookupRow(
        shape: Shape,
        point: Point,
    ): JvmOperationReportRow =
        JvmOperationReportRow(
            "lookup-${shape.name}-${point.name}",
            "${TextAreaInputBenchmark::class.java.name}.lookup",
            mapOf("shape" to shape.name, "point" to point.name),
            "Invoke the original current-line lookup through its prebound primitive handle once.",
        )
}
