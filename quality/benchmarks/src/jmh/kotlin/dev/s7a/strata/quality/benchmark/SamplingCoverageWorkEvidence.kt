@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.SampledImageOrientation
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Independently defined untimed geometry, byte, admission, identity, frequency and current-map controls.
 * The final two native lifetime controls are explicitly loaded prerequisites and cannot pass from this JVM verifier.
 * Collection must retain their actual loaded evidence as external inputs before invoking the shared collector.
 */
public object SamplingCoverageWorkEvidence {
    /** Stable control inventory frozen with the complete 53 fixtures and 98 measured rows. */
    public enum class Control {
        ExactDoubleCoverageOrder,
        HalfOpenAndUncovered,
        OneByOneMinimumWidth,
        AsymmetricAxesAndTails,
        AxisLimitAndOversize,
        DensitiesOneTwoFour,
        NegativeOriginsAndOffsets,
        FractionalDestination,
        HalfPixelOwnership,
        AdjacentFloatCenters,
        FractionalSourceContainment,
        FlipHorizontal,
        FlipVertical,
        FlipBoth,
        UpsamplingAndDownsampling,
        OriginalFloatEvaluationOrder,
        CompleteRgbaAndExtents,
        MasksAndAlphaThresholds,
        UnsupportedEffects,
        SourceReferentialIdentity,
        PlacementEquivalenceAndCoverage,
        CheckedArithmeticFailureOrder,
        InvalidExtentsAndAdapterDensity,
        ClipsAndInvisibleOutput,
        ImagePassAndByteBudgets,
        CurrentFrameFrequency,
        OldMapImmutabilityAndBound,
        CompletePixelsAndOrderedEffects,
        LoadedSourcePinsFencesAndIsolation,
        LoadedFailuresDetachAndTerminalRelease,
    }

    /** Runs every native-independent control before any collection; prints precise coverage without claiming native acceptance. */
    public fun verify(runtime: SamplingCoverageRuntime) {
        check(Control.entries.size == 30)
        for (control in Control.entries.take(28)) {
            verifyControl(runtime, control)
            println("Sampling coverage control ${control.ordinal + 1} $control: JVM verified")
        }
        println("Sampling coverage controls 29/30 require separately qualified loaded evidence; JVM verification is insufficient")
    }

    /** Actual complete geometry admission and map payload, checked against independent fixed expected cases. */
    public fun admission(runtime: SamplingCoverageRuntime, geometry: SamplingCoverageFixture.Geometry, inputs: Any) {
        val maps = runtime.maps(inputs)
        val budgetFallback = geometry == SamplingCoverageFixture.Geometry.Square1024D4
        check(maps.size == if (budgetFallback) 0 else 1) { "Unexpected actual map count for $geometry" }
        check(runtime.capacity(inputs) == if (budgetFallback) 1L else 0L)
        check(runtime.ineligible(inputs) == 0L)
        check(runtime.sources(inputs).all { it === SamplingCoverageFixture.image })
        val input = SamplingCoverageFixture.geometry(geometry)
        for (map in maps) {
            val destination = input.command.destination
            val bounds = IntRect(maxOf(0, floor(destination.left.toDouble()).toInt()), maxOf(0, floor(destination.top.toDouble()).toInt()), minOf(input.viewport.width, ceil(destination.right.toDouble()).toInt()), minOf(input.viewport.height, ceil(destination.bottom.toDouble()).toInt()))
            SamplingCoverageOracle.metadata(runtime, map, input.command, bounds, input.density)
        }
    }

    /** All eight fixed frame cases retain their real ordinary/fallback/barrier counts and source requests. */
    public fun frameAdmission(runtime: SamplingCoverageRuntime, fixture: SamplingCoverageFixture.FrameOnly, inputs: Any) {
        val expected =
            when (fixture) {
                SamplingCoverageFixture.FrameOnly.CleanSameCommandIdentity,
                SamplingCoverageFixture.FrameOnly.ChangedListSameCommands,
                SamplingCoverageFixture.FrameOnly.UnchangedMetadataEquivalentOutput,
                -> 1
                else -> 0
            }
        check(runtime.maps(inputs).size == expected)
        check(runtime.capacity(inputs) == if (fixture == SamplingCoverageFixture.FrameOnly.ExactBudgetFallback) 1L else 0L)
        val unsupported = fixture == SamplingCoverageFixture.FrameOnly.UnsupportedEffectsFallback || fixture == SamplingCoverageFixture.FrameOnly.MixedCompositionBarrier
        check(runtime.ineligible(inputs) == if (unsupported) 1L else 0L)
        check(runtime.tintFallback(inputs) == if (fixture == SamplingCoverageFixture.FrameOnly.UnsupportedEffectsFallback) 1L else 0L)
        if (fixture == SamplingCoverageFixture.FrameOnly.InvisibleNoMap) check(runtime.layers(inputs).isEmpty())
        if (fixture == SamplingCoverageFixture.FrameOnly.OrdinaryDirectNoMap) check(runtime.sources(inputs).single() === SamplingCoverageFixture.image)
    }

    // Each case has independent expected input/admission; no changed helper is used to calculate an expected result.
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun verifyControl(runtime: SamplingCoverageRuntime, control: Control) {
        val base = SamplingCoverageFixture.geometry(SamplingCoverageFixture.Geometry.Square64D1)
        when (control) {
            Control.ExactDoubleCoverageOrder -> {
                for (density in listOf(1, 2, 4)) for (start in listOf(-16.5f, -0.5f, 0.5f, 63.5f, Math.nextUp(0.5f), Math.nextDown(0.5f))) {
                    val command = base.command.copy(destination = FloatRect(start, start, start + 2f, start + 2f))
                    val left = floor(start.toDouble()).toInt()
                    verifyMap(runtime, base.copy(command = command, mapBounds = IntRect(left, left, left + 3, left + 3), density = density))
                }
            }
            Control.HalfOpenAndUncovered -> verifyMap(runtime, SamplingCoverageFixture.geometry(SamplingCoverageFixture.Geometry.SparseCoverageD4))
            Control.OneByOneMinimumWidth -> verifyMap(runtime, SamplingCoverageFixture.geometry(SamplingCoverageFixture.Geometry.OneByOneD1))
            Control.AsymmetricAxesAndTails -> for (geometry in listOf(SamplingCoverageFixture.Geometry.Wide1024D4, SamplingCoverageFixture.Geometry.Tall1024D4)) verifyMap(runtime, SamplingCoverageFixture.geometry(geometry))
            Control.AxisLimitAndOversize -> {
                verifyMap(runtime, SamplingCoverageFixture.geometry(SamplingCoverageFixture.Geometry.Square1024D4))
                fails<IllegalArgumentException> { runtime.map(base.command, IntRect(0, 0, 4097, 1), 1) }
            }
            Control.DensitiesOneTwoFour -> for (density in listOf(1, 2, 4)) verifyMap(runtime, base.copy(density = density))
            Control.NegativeOriginsAndOffsets -> verifyMap(runtime, SamplingCoverageFixture.geometry(SamplingCoverageFixture.Geometry.NegativeOriginD4))
            Control.FractionalDestination -> verifyMap(runtime, SamplingCoverageFixture.geometry(SamplingCoverageFixture.Geometry.FractionalDestinationD2))
            Control.HalfPixelOwnership -> for (geometry in listOf(SamplingCoverageFixture.Geometry.HalfPixelEdgeD1, SamplingCoverageFixture.Geometry.HalfPixelEdgeD2, SamplingCoverageFixture.Geometry.HalfPixelEdgeD4)) verifyMap(runtime, SamplingCoverageFixture.geometry(geometry))
            Control.AdjacentFloatCenters -> for (density in listOf(1, 2, 4)) for (edge in listOf(Math.nextDown(0.5f / density), 0.5f / density, Math.nextUp(0.5f / density))) verifyMap(runtime, base.copy(command = base.command.copy(destination = FloatRect(edge, edge, 63.5f, 63.5f)), density = density))
            Control.FractionalSourceContainment -> {
                verifyMap(runtime, SamplingCoverageFixture.geometry(SamplingCoverageFixture.Geometry.CroppedFractionalSourceD4))
                fails<IllegalArgumentException> { base.command.copy(source = FloatRect(-0.01f, 0f, 64f, 48f)) }
                fails<IllegalArgumentException> { base.command.copy(source = FloatRect(0f, 0f, Math.nextUp(64f), 48f)) }
            }
            Control.FlipHorizontal -> verifyMap(runtime, base.copy(command = base.command.copy(orientation = SampledImageOrientation.FlipHorizontal)))
            Control.FlipVertical -> verifyMap(runtime, base.copy(command = base.command.copy(orientation = SampledImageOrientation.FlipVertical)))
            Control.FlipBoth -> verifyMap(runtime, base.copy(command = base.command.copy(orientation = SampledImageOrientation.FlipBoth)))
            Control.UpsamplingAndDownsampling -> for (geometry in listOf(SamplingCoverageFixture.Geometry.SixteenByOneD1, SamplingCoverageFixture.Geometry.Wide256D4)) verifyMap(runtime, SamplingCoverageFixture.geometry(geometry))
            Control.OriginalFloatEvaluationOrder -> for (orientation in SampledImageOrientation.entries) verifyMap(runtime, base.copy(command = base.command.copy(destination = FloatRect(180.25f, 70.25f, 184.75f, 75.75f), orientation = orientation), mapBounds = IntRect(179, 69, 186, 77), density = 4))
            Control.CompleteRgbaAndExtents -> for (geometry in SamplingCoverageFixture.Geometry.entries) verifyMap(runtime, SamplingCoverageFixture.geometry(geometry))
            Control.MasksAndAlphaThresholds -> {
                for (mask in 0..7) {
                    val tint = ArgbColor(0xFF000000.toInt() or (if (mask and 1 == 0) 0 else 0xFF0000) or (if (mask and 2 == 0) 0 else 0xFF00) or (if (mask and 4 == 0) 0 else 0xFF))
                    verifyMap(runtime, base.copy(command = base.command.copy(tint = tint)))
                }
                verifyMap(runtime, base.copy(command = base.command.copy(tint = ArgbColor(-1), alphaCutoff = 0f)))
                for (alpha in 0..255) {
                    check((1f <= alpha.toFloat() / 255f) == (255 <= alpha))
                    check(0f <= alpha.toFloat() / 255f)
                }
            }
            Control.UnsupportedEffects -> {
                for (cutoff in listOf(0.1f, 0.5f, Math.nextDown(1f))) fails<IllegalArgumentException> { runtime.map(base.command.copy(alphaCutoff = cutoff), base.mapBounds, 1) }
                fails<IllegalArgumentException> { runtime.map(base.command.copy(tint = ArgbColor(0xFF123456.toInt())), base.mapBounds, 1) }
                fails<IllegalArgumentException> { runtime.map(base.command.copy(alphaCutoff = 0f), base.mapBounds, 1) }
            }
            Control.SourceReferentialIdentity -> {
                val first = runtime.map(base.command, base.mapBounds, 1)
                val same = runtime.map(base.command.copy(), base.mapBounds, 1)
                val replacement = createDrawImage(base.command.image.size, base.command.image.copyArgb())
                val distinct = runtime.map(base.command.copy(image = replacement), base.mapBounds, 1)
                check(runtime.equivalent(first, same))
                check(runtime.equivalent(first, distinct).not())
                check(runtime.indices(first).copyArgb().contentEquals(runtime.indices(distinct).copyArgb()))
            }
            Control.PlacementEquivalenceAndCoverage -> {
                val first = verifyMap(runtime, base)
                val moved = base.copy(command = base.command.copy(destination = FloatRect(128f, 128f, 192f, 192f)), mapBounds = IntRect(128, 128, 192, 192))
                check(runtime.equivalent(first, verifyMap(runtime, moved)))
                check(runtime.equivalent(first, verifyMap(runtime, base.copy(command = base.command.copy(destination = FloatRect(1f, 0f, 64f, 64f))))).not())
            }
            Control.CheckedArithmeticFailureOrder -> {
                val unsupported = base.command.copy(alphaCutoff = 0.5f)
                fails<ArithmeticException> { runtime.map(unsupported, IntRect(0, 0, Int.MAX_VALUE, 1), 2) }
                fails<ArithmeticException> { runtime.map(base.command, IntRect(1_073_741_824, 0, 1_073_741_825, 1), 2) }
                fails<ArithmeticException> { runtime.map(base.command, IntRect(1_073_741_823, 0, 1_073_741_825, 1), 2) }
                fails<IllegalArgumentException> { runtime.map(unsupported, IntRect(0, 0, 4097, 1), 1) }.also { check(it.message.orEmpty().contains("axes")) }
            }
            Control.InvalidExtentsAndAdapterDensity -> {
                fails<IllegalArgumentException> { runtime.map(base.command, IntRect(0, 0, 0, 1), 1) }
                fails<IllegalArgumentException> { runtime.map(base.command, base.mapBounds, 0) }
                fails<IllegalArgumentException> { runtime.map(base.command, base.mapBounds, -1) }
                fails<IllegalArgumentException> { runtime.prepare(base.copy(density = 0)) }
                fails<IllegalArgumentException> { base.command.copy(destination = FloatRect(0f, 0f, 0f, 1f)) }
            }
            Control.ClipsAndInvisibleOutput -> {
                val clipped = base.copy(commands = listOf(DrawCommand.PushClip(IntRect(16, 16, 48, 48)), base.command, DrawCommand.PopClip))
                val prepared = runtime.prepare(clipped)
                check(runtime.size(runtime.maps(prepared).single()) == IntSize(32, 32))
                SamplingCoverageOracle.completePixels(clipped)
                val fractional = base.copy(commands = listOf(DrawCommand.PushFractionalClip(FloatRect(0.5f, 0.5f, 63.5f, 63.5f)), base.command, DrawCommand.PopClip))
                check(runtime.maps(runtime.prepare(fractional)).size == 1)
                SamplingCoverageOracle.completePixels(fractional)
                val rejected = fractional.copy(density = 2, commands = listOf(DrawCommand.PushFractionalClip(FloatRect(0.25f, 0.25f, 63.75f, 63.75f)), base.command, DrawCommand.PopClip))
                check(runtime.maps(runtime.prepare(rejected)).isEmpty())
                SamplingCoverageOracle.completePixels(rejected)
                frameAdmission(runtime, SamplingCoverageFixture.FrameOnly.InvisibleNoMap, runtime.prepare(SamplingCoverageFixture.frame(SamplingCoverageFixture.FrameOnly.InvisibleNoMap)))
            }
            Control.ImagePassAndByteBudgets -> {
                val repeated = base.command.copy(destination = FloatRect(0f, 0f, 1f, 1f), tint = ArgbColor(-1), alphaCutoff = 0f, orientation = SampledImageOrientation.FlipHorizontal)
                val many = base.copy(viewport = IntSize(1, 1), commands = List(257) { repeated })
                val prepared = runtime.prepare(many)
                check(runtime.maps(prepared).size == 256 && runtime.capacity(prepared) == 1L)
                check(runtime.passBudget())
                frameAdmission(runtime, SamplingCoverageFixture.FrameOnly.ExactBudgetFallback, runtime.prepare(SamplingCoverageFixture.frame(SamplingCoverageFixture.FrameOnly.ExactBudgetFallback)))
            }
            Control.CurrentFrameFrequency -> for (fixture in SamplingCoverageFixture.FrameOnly.entries) {
                val scene = SamplingCoverageFrameBenchmark.Scene().apply { this.fixture = fixture; setup() }
                val first = scene.prepare()
                repeat(10) {
                    val next = scene.prepare()
                    check((first === next) == (fixture == SamplingCoverageFixture.FrameOnly.CleanSameCommandIdentity))
                    frameAdmission(runtime, fixture, next)
                }
            }
            Control.OldMapImmutabilityAndBound -> {
                val old = verifyMap(runtime, base)
                val bytes = runtime.indices(old).copyArgb()
                repeat(128) { offset -> verifyMap(runtime, base.copy(command = base.command.copy(destination = FloatRect(offset.toFloat(), 0f, offset + 64f, 64f)), mapBounds = IntRect(offset, 0, offset + 64, 64))) }
                check(bytes.contentEquals(runtime.indices(old).copyArgb()))
            }
            Control.CompletePixelsAndOrderedEffects -> {
                for (geometry in SamplingCoverageFixture.Geometry.entries) {
                    val input = SamplingCoverageFixture.geometry(geometry)
                    val map = verifyMap(runtime, input)
                    SamplingCoverageFrozenInputs.observed(geometry.name, SamplingCoverageFixture.Boundary.ExactMapMetadata, input.density, runtime, listOf(map), 0L)
                    val prepared = runtime.prepare(input)
                    admission(runtime, geometry, prepared)
                    SamplingCoverageFrozenInputs.observed(geometry.name, SamplingCoverageFixture.Boundary.CompleteCpuFramePreparation, input.density, runtime, runtime.maps(prepared), runtime.capacity(prepared))
                    SamplingCoverageOracle.completePixels(input, runtime.trace(prepared))
                }
                for (fixture in SamplingCoverageFixture.FrameOnly.entries) {
                    val input = SamplingCoverageFixture.frame(fixture)
                    val scene = SamplingCoverageFrameBenchmark.Scene().apply { this.fixture = fixture; setup() }
                    val previous = scene.prepare()
                    val prepared = scene.prepare()
                    frameAdmission(runtime, fixture, prepared)
                    val before = runtime.maps(previous)
                    val maps = runtime.maps(prepared)
                    val newMaps = maps.count { map -> before.none { it === map } }
                    SamplingCoverageFrozenInputs.observed(fixture.name, SamplingCoverageFixture.Boundary.CompleteCpuFramePreparation, input.density, runtime, maps, runtime.capacity(prepared), newMaps)
                    SamplingCoverageOracle.completePixels(input, runtime.trace(prepared))
                }
            }
            Control.LoadedSourcePinsFencesAndIsolation, Control.LoadedFailuresDetachAndTerminalRelease -> error("Loaded controls cannot be passed by the JVM metadata verifier.")
        }
    }

    private fun verifyMap(runtime: SamplingCoverageRuntime, input: SamplingCoverageFixture.Input): Any = runtime.map(input.command, input.mapBounds, input.density).also {
        SamplingCoverageOracle.metadata(runtime, it, input.command, input.mapBounds, input.density)
    }

    private inline fun <reified T : Throwable> fails(block: () -> Unit): T {
        val failure = checkNotNull(runCatching(block).exceptionOrNull()) { "Expected ${T::class.simpleName}" }
        check(failure is T) { "Expected ${T::class.simpleName}, received ${failure::class.simpleName}" }
        return failure
    }
}
