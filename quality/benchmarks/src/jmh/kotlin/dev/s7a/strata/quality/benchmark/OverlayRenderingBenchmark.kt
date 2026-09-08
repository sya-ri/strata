package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/**
 * Measures command regeneration separately from full source-over recomposition beneath retained foregrounds.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class OverlayRenderingBenchmark {
    /**
     * Updates the lower layer and assembles all commands without rasterizing them.
     */
    @Benchmark
    public fun commands(state: OverlayState): RuntimeUiFrame = state.scene.nextFrame()

    /**
     * Updates the lower layer and rasterizes every retained foreground over the new background.
     */
    @Benchmark
    public fun composition(state: OverlayState): HeadlessImage = state.scene.rasterizeNext()

    /**
     * Owns a scene on one worker; both monitoring modes use the same pixels and update sequence.
     */
    @State(Scope.Thread)
    public open class OverlayState {
        /**
         * Logical width; the scene uses a 16:9 aspect ratio at physical scale one.
         */
        @JvmField
        @Param("320", "1920")
        public var width: Int = 320

        /**
         * Number of full-area translucent foreground layers.
         */
        @JvmField
        @Param("1", "16", "64")
        public var layers: Int = 1

        /**
         * Enables bounded diagnostics while keeping the workload unchanged.
         */
        @JvmField
        @Param("false", "true")
        public var monitoring: Boolean = false

        internal lateinit var scene: OverlayScene

        /**
         * Opens and settles the owned session before timing starts.
         */
        @Setup(Level.Trial)
        public fun setup() {
            scene = OverlayScene(width, layers, monitoring)
        }

        /**
         * Releases the monitor, session, and upstream subscription on the worker thread.
         */
        @TearDown(Level.Trial)
        public fun close() {
            scene.close()
        }
    }
}
