@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.nio.file.Files
import java.nio.file.Path

/**
 * Rejects drift between the reviewed external table and the actual compiled immutable fixture inputs before timing.
 * Tables are also explicit collector inputs; compiled fixture provenance alone does not certify an unrelated table.
 */
public object SamplingCoverageFrozenInputs {
    /** Verifies all 53 complete input records, every Float bit pattern and the exact 98-row comparison inventory. */
    public fun verify() {
        val records = resource("sampling-coverage-fixtures.tsv")
        check(records.size == 53 && records.map { it[0] }.distinct().size == 53)
        val expected = records.associateBy { it[0] }
        for (geometry in SamplingCoverageFixture.Geometry.entries) {
            val input = SamplingCoverageFixture.geometry(geometry)
            val actual = record(geometry.name, "Geometry", geometry.width, geometry.height, input) + listOf("IntegerViewport", "PushClip/SampledImage/PopClip", "FreshInputs", "RepeatFreshInputs")
            check(expected.getValue(geometry.name) == actual) { "Frozen geometry changed: $geometry" }
            check(input.commands.first() == DrawCommand.PushClip(IntRect(0, 0, input.viewport.width, input.viewport.height)))
            check(input.commands[1] === input.command && input.commands.last() == DrawCommand.PopClip)
        }
        for (fixture in SamplingCoverageFixture.FrameOnly.entries) {
            val input = SamplingCoverageFixture.frame(fixture)
            val order =
                if (fixture == SamplingCoverageFixture.FrameOnly.MixedCompositionBarrier) {
                    check(input.commands[0] == DrawCommand.FillRectangle(IntRect(0, 0, 64, 64), ArgbColor(0x80456789.toInt())))
                    check(input.commands[2] == DrawCommand.FillRectangle(IntRect(0, 0, 64, 64), ArgbColor(0x80765432.toInt())))
                    check(input.commands[1] === input.command)
                    "FillRectangle80456789/SampledImage/FillRectangle80765432"
                } else {
                    check(input.commands.single() === input.command)
                    "SampledImage"
                }
            val transition =
                when (fixture) {
                    SamplingCoverageFixture.FrameOnly.CleanSameCommandIdentity -> "BorrowSameInputs"
                    SamplingCoverageFixture.FrameOnly.UnchangedMetadataEquivalentOutput -> "AlternateEqualCommandCopies"
                    else -> "AlternateDistinctListsSameCommands"
                }
            val actual = record(fixture.name, "FrameOnly", input.viewport.width, input.viewport.height, input) + listOf("None", order, "PrimedInputs", transition)
            check(expected.getValue(fixture.name) == actual) { "Frozen frame control changed: $fixture" }
        }
        val matrix = resource("sampling-coverage-matrix.tsv")
        check(matrix.size == 98 && matrix.map { it.take(2) }.distinct().size == 98)
        for (geometry in SamplingCoverageFixture.Geometry.entries) check(matrix.filter { it[0] == geometry.name }.map { SamplingCoverageFixture.Boundary.valueOf(it[1]) }.toSet() == SamplingCoverageFixture.Boundary.entries.toSet())
        for (fixture in SamplingCoverageFixture.FrameOnly.entries) check(SamplingCoverageFixture.Boundary.valueOf(matrix.single { it[0] == fixture.name }[1]) == SamplingCoverageFixture.Boundary.CompleteCpuFramePreparation)
        val controls = resource("sampling-coverage-controls.tsv")
        check(controls.size == 30 && controls.map { SamplingCoverageWorkEvidence.Control.valueOf(it[1]) } == SamplingCoverageWorkEvidence.Control.entries)
        check(SamplingCoverageFixture.image.size.width == 64 && SamplingCoverageFixture.image.size.height == 48)
        val pixels = resource("sampling-coverage-source.tsv")
        check(pixels.size == 48 && pixels.all { it.size == 64 })
        for (y in 0 until 48) for (x in 0 until 64) {
            val expectedPixel = pixels[y][x].toLong(16).toInt()
            check(SamplingCoverageFixture.image.argbAt(x, y) == expectedPixel && SamplingCoverageFixture.pattern(x, y) == expectedPixel)
        }
    }

    /** Requires identical separately archived table, source pixels and control specification on both variants. */
    public fun verifyExternal(inputs: Map<String, Path>) {
        val resources = mapOf("sampling-fixtures" to "sampling-coverage-fixtures.tsv", "sampling-matrix" to "sampling-coverage-matrix.tsv", "sampling-controls" to "sampling-coverage-controls.tsv", "sampling-source" to "sampling-coverage-source.tsv")
        for ((label, name) in resources) {
            val external = Files.readAllBytes(checkNotNull(inputs[label]) { "Register the frozen $label as a collector input." })
            val compiled = checkNotNull(javaClass.getResourceAsStream("/$name")).use { it.readBytes() }
            check(external.contentEquals(compiled)) { "External fixture differs from compiled resource: $label" }
        }
    }

    /** Checks new-map frequency separately from the returned inputs' current lookup extents and CPU metadata payload. */
    @Suppress("LongParameterList") // Frequency and returned metadata payload describe different work on a clean input borrow.
    public fun observed(id: String, boundary: SamplingCoverageFixture.Boundary, density: Int, runtime: SamplingCoverageRuntime, maps: List<Any>, capacity: Long, newMapCount: Int = maps.size) {
        val expected = resource("sampling-coverage-matrix.tsv").single { it[0] == id && SamplingCoverageFixture.Boundary.valueOf(it[1]) == boundary }
        val width = maps.singleOrNull()?.let(runtime::size)?.width ?: 0
        val height = maps.singleOrNull()?.let(runtime::size)?.height ?: 0
        val bytes = maps.sumOf { map -> runtime.indices(map).size.let { it.width.toLong() * it.height * 4L } }
        val actual = listOf(id, boundary.name, density.toString(), newMapCount.toString(), width.toString(), height.toString(), bytes.toString(), capacity.toString())
        check(expected.take(8) == actual) { "Actual map work changed: $actual, expected=$expected" }
        println("Sampling coverage observed\t${actual.joinToString("\t")}")
    }

    private fun record(id: String, kind: String, width: Int, height: Int, input: SamplingCoverageFixture.Input): List<String> {
        val command = input.command
        val source = command.source
        val destination = command.destination
        val bounds = input.mapBounds
        check(command.image === SamplingCoverageFixture.image)
        return listOf(id, kind, width.toString(), height.toString(), input.density.toString()) +
            listOf(source.left, source.top, source.right, source.bottom, destination.left, destination.top, destination.right, destination.bottom).map(::bits) +
            listOf(bounds.left, bounds.top, bounds.right, bounds.bottom, input.viewport.width, input.viewport.height).map(Int::toString) +
            listOf(command.orientation.flipX.toString(), command.orientation.flipY.toString(), Integer.toHexString(command.tint.value).padStart(8, '0'), bits(command.alphaCutoff))
    }

    private fun bits(value: Float): String = Integer.toHexString(value.toRawBits()).padStart(8, '0')

    private fun resource(name: String): List<List<String>> = checkNotNull(javaClass.getResourceAsStream("/$name")).bufferedReader(Charsets.UTF_8).use { reader -> reader.readLines().drop(1).map { it.split('\t') } }
}
