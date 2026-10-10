@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.SampledImageOrientation
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Frozen immutable inputs shared unchanged by both actual Fabric archive variants.
 * Geometry metadata uses the declared map bounds; complete preparation uses the actual viewport and clip admission.
 * The 4096-square constructor therefore remains distinct from the complete frame's real byte-budget fallback.
 */
public object SamplingCoverageFixture {
    private val alphas = intArrayOf(0, 1, 127, 254, 255)
    /** The bounded source includes transparent, translucent and opaque independently patterned texels. */
    public val image: DrawImage = createDrawImage(IntSize(64, 48)) { x, y -> pattern(x, y) }

    /** Detached source color formula used by the scalar reference without reading the runtime's pixels. */
    public fun pattern(x: Int, y: Int): Int {
        val alpha = alphas[(x + y * 3) % 5]
        return (alpha shl 24) or (((x * 37 + y * 11) and 255) shl 16) or (((x * 13 + y * 43) and 255) shl 8) or ((x * 71 + y * 19) and 255)
    }

    /** Input changes are typed; no runtime component or domain state is selected by a string. */
    public enum class Scenario {
        Shape,
        NegativeOrigin,
        FractionalDestination,
        HalfPixelEdge,
        SparseCoverage,
        MirroredBoth,
        CroppedFractionalSource,
    }

    /** Complete 45-case geometry inventory, with logical bounds and physical density fixed before timing. */
    public enum class Geometry(public val width: Int, public val height: Int, public val density: Int, public val scenario: Scenario = Scenario.Shape) {
        OneByOneD1(1, 1, 1),
        OneByOneD2(1, 1, 2),
        OneByOneD4(1, 1, 4),
        SixteenByOneD1(16, 1, 1),
        SixteenByOneD2(16, 1, 2),
        SixteenByOneD4(16, 1, 4),
        OneBySixteenD1(1, 16, 1),
        OneBySixteenD2(1, 16, 2),
        OneBySixteenD4(1, 16, 4),
        Square64D1(64, 64, 1),
        Square64D2(64, 64, 2),
        Square64D4(64, 64, 4),
        Wide256D1(256, 16, 1),
        Wide256D2(256, 16, 2),
        Wide256D4(256, 16, 4),
        Tall256D1(16, 256, 1),
        Tall256D2(16, 256, 2),
        Tall256D4(16, 256, 4),
        Wide1024D1(1024, 1, 1),
        Wide1024D2(1024, 1, 2),
        Wide1024D4(1024, 1, 4),
        Tall1024D1(1, 1024, 1),
        Tall1024D2(1, 1024, 2),
        Tall1024D4(1, 1024, 4),
        Square1024D1(1024, 1024, 1),
        Square1024D2(1024, 1024, 2),
        Square1024D4(1024, 1024, 4),
        NegativeOriginD1(64, 32, 1, Scenario.NegativeOrigin),
        NegativeOriginD2(64, 32, 2, Scenario.NegativeOrigin),
        NegativeOriginD4(64, 32, 4, Scenario.NegativeOrigin),
        FractionalDestinationD1(64, 32, 1, Scenario.FractionalDestination),
        FractionalDestinationD2(64, 32, 2, Scenario.FractionalDestination),
        FractionalDestinationD4(64, 32, 4, Scenario.FractionalDestination),
        HalfPixelEdgeD1(64, 32, 1, Scenario.HalfPixelEdge),
        HalfPixelEdgeD2(64, 32, 2, Scenario.HalfPixelEdge),
        HalfPixelEdgeD4(64, 32, 4, Scenario.HalfPixelEdge),
        SparseCoverageD1(64, 32, 1, Scenario.SparseCoverage),
        SparseCoverageD2(64, 32, 2, Scenario.SparseCoverage),
        SparseCoverageD4(64, 32, 4, Scenario.SparseCoverage),
        MirroredBothD1(64, 32, 1, Scenario.MirroredBoth),
        MirroredBothD2(64, 32, 2, Scenario.MirroredBoth),
        MirroredBothD4(64, 32, 4, Scenario.MirroredBoth),
        CroppedFractionalSourceD1(64, 32, 1, Scenario.CroppedFractionalSource),
        CroppedFractionalSourceD2(64, 32, 2, Scenario.CroppedFractionalSource),
        CroppedFractionalSourceD4(64, 32, 4, Scenario.CroppedFractionalSource),
    }

    /** Independent no-map, fresh-map, rejection and barrier preparation controls. */
    public enum class FrameOnly {
        CleanSameCommandIdentity,
        ChangedListSameCommands,
        OrdinaryDirectNoMap,
        InvisibleNoMap,
        ExactBudgetFallback,
        UnsupportedEffectsFallback,
        MixedCompositionBarrier,
        UnchangedMetadataEquivalentOutput,
    }

    /** Separate actual operation boundaries; constructor work is never mislabeled complete-frame admission. */
    public enum class Boundary {
        ExactMapMetadata,
        CompleteCpuFramePreparation,
    }

    /** All immutable geometry and complete balanced command inputs for one row. */
    public data class Input(
        public val command: DrawCommand.SampledImage,
        public val mapBounds: IntRect,
        public val viewport: IntSize,
        public val density: Int,
        public val commands: List<DrawCommand>,
    )

    /** Builds exact-effects cases that cannot accidentally measure the ordinary direct path. */
    public fun geometry(value: Geometry): Input {
        val bounds = if (value.scenario == Scenario.NegativeOrigin) IntRect(-16, -8, 48, 24) else IntRect(0, 0, value.width, value.height)
        val edge = 0.5f / value.density.toFloat()
        val destination =
            when (value.scenario) {
                Scenario.FractionalDestination -> FloatRect(0.25f, 0.375f, 63.75f, 31.625f)
                Scenario.HalfPixelEdge -> FloatRect(edge, edge, 64f - edge, 32f - edge)
                Scenario.SparseCoverage -> FloatRect(31.25f, 15.25f, 32.75f, 16.75f)
                else -> FloatRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat())
            }
        val source = if (value.scenario == Scenario.CroppedFractionalSource) FloatRect(1.125f, 2.25f, 61.875f, 45.75f) else FloatRect(0.25f, 0.375f, 63.75f, 47.625f)
        val orientation = if (value.scenario == Scenario.MirroredBoth) SampledImageOrientation.FlipBoth else SampledImageOrientation.Normal
        val command = DrawCommand.SampledImage(image, source, destination, ArgbColor(0xFFFF00FF.toInt()), alphaCutoff = 1f, orientation = orientation)
        val viewport = IntSize(value.width, value.height)
        val commands = listOf(DrawCommand.PushClip(IntRect(0, 0, value.width, value.height)), command, DrawCommand.PopClip)
        return Input(command, bounds, viewport, value.density, commands)
    }

    /** Uses bounded fixed state transitions; clean cases borrow primed inputs and changed cases rebuild real inputs. */
    public fun frame(value: FrameOnly): Input {
        val base = geometry(Geometry.Square64D1)
        val command =
            when (value) {
                FrameOnly.OrdinaryDirectNoMap -> base.command.copy(source = FloatRect(0f, 0f, 64f, 48f), destination = FloatRect(0f, 0f, 64f, 48f), tint = ArgbColor(-1), alphaCutoff = 0f)
                FrameOnly.InvisibleNoMap -> base.command.copy(destination = FloatRect(128f, 128f, 192f, 192f))
                FrameOnly.ExactBudgetFallback -> base.command.copy(destination = FloatRect(0f, 0f, 4096f, 4096f))
                FrameOnly.UnsupportedEffectsFallback -> base.command.copy(tint = ArgbColor(0xFF817263.toInt()), alphaCutoff = 0.5f)
                else -> base.command
            }
        val viewport = if (value == FrameOnly.ExactBudgetFallback) IntSize(4096, 4096) else base.viewport
        val commands =
            if (value == FrameOnly.MixedCompositionBarrier) {
                listOf(DrawCommand.FillRectangle(IntRect(0, 0, 64, 64), ArgbColor(0x80456789.toInt())), command, DrawCommand.FillRectangle(IntRect(0, 0, 64, 64), ArgbColor(0x80765432.toInt())))
            } else {
                listOf(command)
            }
        return Input(command, IntRect(0, 0, viewport.width, viewport.height), viewport, 1, commands)
    }
}
