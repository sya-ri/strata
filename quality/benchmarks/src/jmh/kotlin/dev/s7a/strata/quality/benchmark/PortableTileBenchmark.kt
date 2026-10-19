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
 * Measures portable partitioning from an explicitly supplied Fabric runtime without initializing the game.
 * The independent corpus leaves the historical runtime classpath and benchmark matrix unchanged.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class PortableTileBenchmark {
    /**
     * Includes the same one reflective JVM-synthetic entry-point invocation on both runtime revisions.
     * Complete partition descriptions and their temporary storage belong to each measured operation.
     */
    @Benchmark
    public fun partition(state: TileState): Any = state.partition()

    /**
     * Placement patterns distinguish narrow primitives from the unavoidable all-tile output workload.
     */
    public enum class Coverage {
        /**
         * Each primitive lies within one of the 64 tiles.
         */
        Spread,

        /**
         * Each primitive covers every tile.
         */
        Full,
    }

    /**
     * Owns immutable commands and the native-free entry point before JMH timing starts.
     */
    @State(Scope.Thread)
    public open class TileState {
        /**
         * Number of ordered fill primitives, excluding the balanced enclosing clips.
         */
        @JvmField
        @Param("128", "4096")
        public var primitives: Int = 128

        /**
         * Whether primitives are distributed across tiles or intersect every tile.
         */
        @JvmField
        @Param("Spread", "Full")
        public var coverage: Coverage = Coverage.Spread

        private val bounds = IntRect(0, 0, 2048, 2048)
        private lateinit var commands: List<DrawCommand>
        private lateinit var entryPoint: Method

        /**
         * Resolves only the common partition entry point and prepares the complete fixed command list.
         */
        @Setup(Level.Trial)
        public fun setup() {
            val integer = checkNotNull(Int::class.javaPrimitiveType)
            entryPoint =
                Class
                    .forName("dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftPortableTilesKt")
                    .getDeclaredMethod("tileFabricMinecraftPortable", List::class.java, IntRect::class.java, integer, integer, integer, integer, integer)
            commands =
                buildList {
                    add(DrawCommand.PushClip(bounds))
                    add(DrawCommand.PushFractionalClip(FloatRect(-0.25f, -0.25f, 2048.25f, 2048.25f)))
                    repeat(primitives) { index ->
                        val rectangle =
                            when (coverage) {
                                Coverage.Spread -> {
                                    val left = (index % 8) * 256 + 16
                                    val top = ((index / 8) % 8) * 256 + 16
                                    IntRect(left, top, left + 16, top + 16)
                                }

                                Coverage.Full -> {
                                    bounds
                                }
                            }
                        add(DrawCommand.FillRectangle(rectangle, ArgbColor(0x80456789.toInt())))
                    }
                    add(DrawCommand.PopClip)
                    add(DrawCommand.PopClip)
                }
            check((partition() as List<*>).size == 64)
        }

        /**
         * Returns one complete partition without retaining the measured result in fixture state.
         */
        internal fun partition(): Any = checkNotNull(entryPoint.invoke(null, commands, bounds, 1, 0, 0, 0, 0))
    }
}
