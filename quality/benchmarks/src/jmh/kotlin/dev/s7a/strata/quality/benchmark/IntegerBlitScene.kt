package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.quality.benchmark.IntegerBlitBenchmark.Path
import dev.s7a.strata.quality.benchmark.IntegerBlitBenchmark.Scenario
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.headless.rasterizeHeadlessRegion
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Owns immutable input commands for one fixed raster scene, with no retained output or mutable scratch.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class IntegerBlitScene private constructor(
    private val bounds: IntRect,
    private val density: Int,
    private val commands: List<DrawCommand>,
) {
    /**
     * Allocates the complete raster region and applies every ordered command.
     */
    fun paint(): HeadlessImage = rasterizeHeadlessRegion(commands, bounds, density)

    /**
     * Checks complete physical pixels and independence of two returned images against the rational reference.
     */
    fun verify() {
        val expected = IntegerBlitReference.paint(commands, bounds, density)
        val first = paint()
        check(first.copyArgb().contentEquals(expected))
        val second = paint()
        check(second.copyArgb().contentEquals(expected))
        check(first.copyArgb().contentEquals(expected))
    }

    /**
     * Materializes sources once, including one private generated array for the compact overflow control.
     */
    companion object {
        fun create(path: Path, scenario: Scenario): IntegerBlitScene {
            val bounds =
                if (scenario == Scenario.NonzeroOrigin) IntRect(19, 13, 83, 77) else IntRect(0, 0, scenario.width, scenario.height)
            val sourceSize = IntSize(scenario.sourceWidth, scenario.sourceHeight)
            val image = createDrawImage(sourceSize) { x, y -> sourceColor(x + y * sourceSize.width, scenario) }
            val source =
                when (scenario) {
                    Scenario.CroppedClip, Scenario.NonzeroOrigin -> IntRect(2, 3, 69, 50)
                    else -> IntRect(0, 0, sourceSize.width, sourceSize.height)
                }
            val destination =
                when (scenario) {
                    Scenario.CroppedClip -> IntRect(-23, -11, 91, 73)
                    Scenario.NonzeroOrigin -> IntRect(8, 3, 128, 105)
                    Scenario.ExtremeLong -> IntRect(Int.MIN_VALUE + 65, Int.MIN_VALUE + 65, 64, 64)
                    Scenario.BigInteger, Scenario.BigIntegerOneRow -> IntRect(Int.MIN_VALUE + 2, 0, 1, 1)
                    else -> bounds
                }
            val command =
                when (path) {
                    Path.Logical -> DrawCommand.BlitImage(image, source, destination)
                    Path.Physical -> DrawCommand.BlitImagePixels(image, source, destination)
                }
            val clips = clips(scenario, bounds)
            val commands = background(scenario, bounds) + clips + command + List(clips.size) { DrawCommand.PopClip }
            return IntegerBlitScene(bounds, scenario.density, commands)
        }

        private fun sourceColor(index: Int, scenario: Scenario): Int {
            val alpha =
                when (scenario) {
                    Scenario.OpaqueUniform, Scenario.OpaqueHeterogeneous -> 255
                    Scenario.TranslucentUniform, Scenario.TranslucentHeterogeneous, Scenario.BigInteger, Scenario.BigIntegerOneRow -> 128
                    Scenario.TransparentUniform, Scenario.TransparentHeterogeneous -> 0
                    else ->
                        when (index % 3) {
                            0 -> 255
                            1 -> 128
                            else -> 0
                        }
                }
            return (alpha shl 24) or (index * 73471 and 0xFFFFFF)
        }

        private fun background(scenario: Scenario, bounds: IntRect): List<DrawCommand> =
            when (scenario) {
                Scenario.OpaqueHeterogeneous, Scenario.TranslucentHeterogeneous, Scenario.TransparentHeterogeneous -> {
                    val size = IntSize(bounds.width * scenario.density, bounds.height * scenario.density)
                    val image = createDrawImage(size) { x, y -> ((1 + (x + y) % 255) shl 24) or ((x * 971 + y * 73471) and 0xFFFFFF) }
                    listOf(DrawCommand.BlitImagePixels(image, IntRect(0, 0, size.width, size.height), bounds))
                }

                else -> listOf(DrawCommand.FillRectangle(bounds, ArgbColor(0x804A6789.toInt())))
            }

        private fun clips(scenario: Scenario, bounds: IntRect): List<DrawCommand> =
            when (scenario) {
                Scenario.OneRow1, Scenario.OneRow2, Scenario.OneRow3, Scenario.OneRow4 ->
                    listOf(DrawCommand.PushFractionalClip(FloatRect(0f, 0f, bounds.right.toFloat(), 1f / scenario.density)))
                Scenario.CroppedClip ->
                    listOf(DrawCommand.PushClip(IntRect(13, 7, 85, 70)), DrawCommand.PushFractionalClip(FloatRect(13.25f, 7.5f, 84.5f, 69.25f)))
                Scenario.NonzeroOrigin ->
                    listOf(DrawCommand.PushClip(IntRect(21, 15, 81, 75)), DrawCommand.PushFractionalClip(FloatRect(21.125f, 15.375f, 80.625f, 74.875f)))
                Scenario.BigInteger ->
                    listOf(DrawCommand.PushClip(bounds), DrawCommand.PushFractionalClip(FloatRect(0f, 0f, 0.25f, 0.25f)))
                Scenario.BigIntegerOneRow ->
                    listOf(DrawCommand.PushClip(bounds), DrawCommand.PushFractionalClip(FloatRect(0f, 0f, 0.25f, 1f / scenario.density)))
                Scenario.EmptyClip -> listOf(DrawCommand.PushClip(IntRect(0, 0, 0, 0)))
                else -> emptyList()
            }
    }
}
