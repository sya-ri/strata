package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import java.lang.reflect.Method

/**
 * Independently controls dense clip-stack work and the unchanged small-run branch using the actual Fabric archive.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class PortableTileControlBenchmark {
    /**
     * Returns complete partition descriptions, including the same reflective invocation on both revisions.
     */
    @Benchmark
    public fun partition(state: TileState): Any = state.partition()

    /**
     * Required controls distinguish repeated sparse clip stacks from the untouched small-run path.
     */
    public enum class Scenario {
        /**
         * Every dispersed primitive has four original nested clips and four matching pops.
         */
        NestedClips,

        /**
         * All primitives remain within one tightly bounded 128-by-128-pixel run.
         */
        SingleTile,
    }

    /**
     * Owns immutable geometry and resolves the partition entry point outside measured operations.
     */
    @State(Scope.Thread)
    public open class TileState {
        /**
         * Number of fill primitives, with clip-stack commands counted separately.
         */
        @JvmField
        @Param("128", "4096")
        public var primitives: Int = 128

        /**
         * Clip-heavy distribution or the single-tile control.
         */
        @JvmField
        @Param("NestedClips", "SingleTile")
        public var scenario: Scenario = Scenario.NestedClips

        private lateinit var bounds: IntRect
        private lateinit var commands: List<DrawCommand>
        private lateinit var entryPoint: Method

        /**
         * Prepares fixed commands and verifies the intended tile count before JMH starts sampling.
         */
        @Setup(Level.Trial)
        public fun setup() {
            val integer = checkNotNull(Int::class.javaPrimitiveType)
            entryPoint =
                Class
                    .forName("dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftPortableTilesKt")
                    .getDeclaredMethod("tileFabricMinecraftPortable", List::class.java, IntRect::class.java, integer, integer, integer, integer, integer)
            bounds = if (scenario == Scenario.SingleTile) IntRect(0, 0, 128, 128) else IntRect(0, 0, 2048, 2048)
            commands =
                buildList {
                    repeat(primitives) { index ->
                        when (scenario) {
                            Scenario.NestedClips -> {
                                val left = (index % 8) * 256
                                val top = ((index / 8) % 8) * 256
                                add(DrawCommand.PushClip(IntRect(left, top, left + 256, top + 256)))
                                add(DrawCommand.PushFractionalClip(FloatRect(left + 8.25f, top + 8.5f, left + 248.75f, top + 248.5f)))
                                add(DrawCommand.PushClip(IntRect(left + 16, top + 16, left + 240, top + 240)))
                                add(DrawCommand.PushFractionalClip(FloatRect(left + 24.25f, top + 24.5f, left + 231.75f, top + 231.5f)))
                                add(DrawCommand.FillRectangle(IntRect(left + 32, top + 32, left + 48, top + 48), ArgbColor(0x80456789.toInt())))
                                repeat(4) { add(DrawCommand.PopClip) }
                            }

                            Scenario.SingleTile -> {
                                add(DrawCommand.FillRectangle(bounds, ArgbColor(0x80456789.toInt())))
                            }
                        }
                    }
                }
            check((partition() as List<*>).size == if (scenario == Scenario.SingleTile) 1 else 64)
        }

        /**
         * Invokes one complete partition without retaining previous measured outputs.
         */
        internal fun partition(): Any = checkNotNull(entryPoint.invoke(null, commands, bounds, 1, 0, 0, 0, 0))
    }
}
