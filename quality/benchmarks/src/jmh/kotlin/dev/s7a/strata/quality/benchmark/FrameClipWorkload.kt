package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PlatformDrawCommand
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.lang.reflect.Method

/**
 * Keeps only the current immutable CPU scene and prepared frame inputs, without game or native resource ownership.
 * Reflection resolves the real JVM-synthetic Fabric boundary, never a copied implementation or custom classloader.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class FrameClipWorkload private constructor(
    private val commands: List<DrawCommand>,
    private val viewport: IntSize,
) {
    private val entryPoint: Method =
        Class
            .forName("dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftFrameLayerKt")
            .getDeclaredMethod(
                "partitionFabricMinecraftFrame",
                List::class.java,
                IntSize::class.java,
                checkNotNull(Int::class.javaPrimitiveType),
                checkNotNull(Boolean::class.javaPrimitiveType),
                checkNotNull(Boolean::class.javaPrimitiveType),
            )
    private val inputType = Class.forName("dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftFrameInputs")
    private val resolve =
        inputType.declaredMethods.single { method ->
            method.parameterTypes.contentEquals(arrayOf(Function1::class.java, Function1::class.java)) && method.returnType == inputType
        }
    private val available: (DrawImage) -> Boolean = { true }
    private val prepared: Any = inputType.constructors.single { it.parameterCount == 6 }.newInstance(partition(), 1, 0L, 0L, false, null)

    init {
        check(entryPoint.declaringClass.classLoader === javaClass.classLoader)
        check(inputType.classLoader === javaClass.classLoader)
        check(entryPoint.declaringClass.protectionDomain.codeSource.location == inputType.protectionDomain.codeSource.location)
        check(resolvePrepared() === prepared)
    }

    /**
     * Returns new complete layer descriptions; timing includes the identical reflective invocation on each side.
     */
    internal fun partition(): List<*> = checkNotNull(entryPoint.invoke(null, commands, viewport, 1, false, false) as? List<*>)

    /**
     * Returns the primed actual frame inputs through their all-available source-resolution path.
     */
    internal fun resolvePrepared(): Any = checkNotNull(resolve.invoke(prepared, available, available))

    /**
     * Constructs deterministic clip scenes consumed by the actual packaged Fabric boundary.
     */
    internal companion object {
        /**
         * Builds balanced original clips, ordinary blits/fills and ordered direct, fallback and platform barriers.
         */
        internal fun scene(
            depth: Int,
            primitives: Int,
            pattern: FrameClipBenchmark.ClipPattern,
            side: Int,
        ): FrameClipWorkload {
            val bounds = IntRect(0, 0, side, side)
            val image = createDrawImage(IntSize(4, 4), IntArray(16) { 0xFF223300.toInt() or it })
            val center = side / 2
            val destination = IntRect(center, center, center + 4, center + 4)
            val floating = FloatRect(center.toFloat(), center.toFloat(), center + 4f, center + 4f)
            val source = IntRect(0, 0, 4, 4)
            val commands =
                buildList {
                    repeat(depth) { index ->
                        val inset = index % 3
                        if (pattern == FrameClipBenchmark.ClipPattern.Integer || (pattern == FrameClipBenchmark.ClipPattern.Mixed && index % 2 == 0)) {
                            add(DrawCommand.PushClip(IntRect(inset, inset, side - inset, side - inset)))
                        } else {
                            val edge = inset + 0.25f
                            add(DrawCommand.PushFractionalClip(FloatRect(edge, edge, side - edge, side - edge)))
                        }
                    }
                    repeat(primitives) { index ->
                        add(
                            when (Primitive.entries[index % Primitive.entries.size]) {
                                Primitive.Fill -> DrawCommand.FillRectangle(bounds, ArgbColor(0x80456789.toInt()))
                                Primitive.LogicalImage -> DrawCommand.BlitImage(image, source, destination)
                                Primitive.PhysicalImage -> DrawCommand.BlitImagePixels(image, source, destination)
                            },
                        )
                        if (index == primitives / 2) {
                            add(DrawCommand.SampledImage(image, FloatRect(0f, 0f, 4f, 4f), floating, alphaCutoff = 0f))
                            add(DrawCommand.FillRectangle(destination, ArgbColor(0xFF112233.toInt())))
                            add(DrawCommand.SampledImage(image, FloatRect(0f, 0f, 4f, 4f), floating, tint = ArgbColor(0xFF8899AA.toInt()), alphaCutoff = 0f))
                            add(DrawCommand.Platform(Marker, destination))
                        }
                    }
                    repeat(depth) { add(DrawCommand.PopClip) }
                }
            check(commands.count { it is DrawCommand.PushClip || it is DrawCommand.PushFractionalClip } == depth)
            check(commands.count { it === DrawCommand.PopClip } == depth)
            return FrameClipWorkload(commands, IntSize(side, side)).also { check(it.partition().isNotEmpty()) }
        }

        /**
         * Builds bounded control commands with no image or native resource state.
         */
        internal fun edge(scenario: FrameClipBenchmark.EdgeCase): FrameClipWorkload {
            val bounds = IntRect(0, 0, 32, 32)
            val fill = DrawCommand.FillRectangle(bounds, ArgbColor(-1))
            val commands =
                when (scenario) {
                    FrameClipBenchmark.EdgeCase.EmptyFrame -> {
                        emptyList()
                    }

                    FrameClipBenchmark.EdgeCase.ClipOnly -> {
                        List(128) { DrawCommand.PushClip(bounds) } + List(128) { DrawCommand.PopClip }
                    }

                    FrameClipBenchmark.EdgeCase.EmptyClip -> {
                        listOf(DrawCommand.PushClip(IntRect(4, 4, 4, 8)), fill, DrawCommand.PopClip)
                    }

                    FrameClipBenchmark.EdgeCase.Offscreen -> {
                        listOf(DrawCommand.PushClip(IntRect(40, 40, 48, 48)), fill, DrawCommand.PopClip)
                    }

                    FrameClipBenchmark.EdgeCase.LargeEdges -> {
                        listOf(DrawCommand.PushClip(IntRect(Int.MIN_VALUE + 1, Int.MIN_VALUE + 1, Int.MAX_VALUE, Int.MAX_VALUE)), fill, DrawCommand.PopClip)
                    }

                    FrameClipBenchmark.EdgeCase.Siblings -> {
                        buildList {
                            add(DrawCommand.PushClip(bounds))
                            repeat(128) { index ->
                                val inset = index % 3
                                add(DrawCommand.PushFractionalClip(FloatRect(inset + 0.25f, inset + 0.25f, 31.75f - inset, 31.75f - inset)))
                                add(fill)
                                add(DrawCommand.PopClip)
                            }
                            add(DrawCommand.PopClip)
                        }
                    }
                }
            return FrameClipWorkload(commands, IntSize(32, 32)).also {
                val empty = scenario in setOf(FrameClipBenchmark.EdgeCase.EmptyFrame, FrameClipBenchmark.EdgeCase.ClipOnly, FrameClipBenchmark.EdgeCase.EmptyClip, FrameClipBenchmark.EdgeCase.Offscreen)
                check(it.partition().isEmpty() == empty)
            }
        }
    }

    /**
     * The three ordinary primitive contracts varied without decoding component kinds from scalar values.
     */
    private enum class Primitive {
        Fill,
        LogicalImage,
        PhysicalImage,
    }

    /**
     * An opaque portable-independent platform barrier that never initializes a native drawing implementation.
     */
    private object Marker : PlatformDrawCommand
}
