package dev.s7a.strata.quality.benchmark

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State

/**
 * Measures the actual Fabric changed-frame partition boundary and a primed native-free frame-input borrow.
 * Both runtime sides use identical reflection through the normal JMH application classloader.
 */
public open class FrameClipBenchmark {
    /** Returns every changed-frame layer, including tile descriptions and balanced raw clip commands. */
    @Benchmark
    public fun partition(state: Scene): Any = state.workload.partition()

    /** Resolves already prepared inputs with every source available; no partition or raster is rebuilt. */
    @Benchmark
    public fun resolvePrepared(state: Scene): Any = state.workload.resolvePrepared()

    /** Measures shallow, empty, offscreen and sibling-stack controls independently of the scene matrix. */
    @Benchmark
    public fun partitionEdges(state: Edges): Any = state.workload.partition()

    /** Selects the original clip representation, preserving fractional commands even with integer enclosures. */
    public enum class ClipPattern {
        Integer,
        Fractional,
        Mixed,
    }

    /** Identifies boundary cases whose command counts are fixed by the immutable fixture. */
    public enum class EdgeCase {
        EmptyFrame,
        ClipOnly,
        EmptyClip,
        Offscreen,
        LargeEdges,
        Siblings,
    }

    /** Owns one current immutable scene and prepared control, without retaining changed-frame results. */
    @State(Scope.Thread)
    public open class Scene {
        /** Active clip depth around all scene primitives. */
        @JvmField
        @Param("0", "1", "4", "32", "128")
        public var depth: Int = 0

        /** Ordinary primitive count; sampled and platform barriers are additional fixed work. */
        @JvmField
        @Param("8", "512")
        public var primitives: Int = 8

        /** Original clip representation. */
        @JvmField
        @Param("Integer", "Fractional", "Mixed")
        public var pattern: ClipPattern = ClipPattern.Integer

        /** Logical square side at GUI scale one, distinguishing small and large tiled runs. */
        @JvmField
        @Param("32", "768")
        public var side: Int = 32

        internal lateinit var workload: FrameClipWorkload

        /** Prepares images, clips, reflective entry points and the unchanged borrow before timing. */
        @Setup(Level.Trial)
        public fun setup() {
            workload = FrameClipWorkload.scene(depth, primitives, pattern, side)
        }
    }

    /** Owns one deterministic empty/offscreen/sibling input outside measurement. */
    @State(Scope.Thread)
    public open class Edges {
        /** Boundary scenario. */
        @JvmField
        @Param("EmptyFrame", "ClipOnly", "EmptyClip", "Offscreen", "LargeEdges", "Siblings")
        public var scenario: EdgeCase = EdgeCase.EmptyFrame

        internal lateinit var workload: FrameClipWorkload

        /** Resolves the actual helper and validates a primed borrow before timing. */
        @Setup(Level.Trial)
        public fun setup() {
            workload = FrameClipWorkload.edge(scenario)
        }
    }
}
