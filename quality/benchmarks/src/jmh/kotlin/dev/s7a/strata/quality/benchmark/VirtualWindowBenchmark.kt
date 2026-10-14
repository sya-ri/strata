package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhWorkloadInventory
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
 * Measures moving-window declaration work together with its unchanged retained-frame responsibilities.
 * The compiled immutable recipe and shared collector are identical for historical and candidate runtime archives.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class VirtualWindowBenchmark {
    /**
     * Applies one fixed scroll or invalidation operation and returns the committed frame.
     */
    @Benchmark
    public fun windowFrame(scene: Scene): RuntimeUiFrame = scene.nextFrame()

    /**
     * Fixed materialized cardinalities at an interior integral offset, including two overscan rows.
     */
    public enum class Window(
        public val rows: Int,
    ) {
        Eight(8),
        Forty(40),
        OneTwentyEight(128),
    }

    /**
     * Independent row-builder cost and observation controls.
     */
    public enum class Factory {
        Simple,

        /**
         * Thirty-two real nested Stack declarations above the same painted and interactive leaf.
         */
        Deep32,

        /**
         * Direct state access requires full callback reevaluation when the window changes.
         */
        DirectState,

        /**
         * The row owns an independently observed child region rather than reading state in its factory.
         */
        Observed,
    }

    /**
     * Fixed clean, overlapping, disjoint and complete-invalidation operations.
     */
    public enum class Motion {
        Clean,
        OneRow,
        Fractional,
        FastJump,
        Refresh,
        DefinitionReplacement,
    }

    /**
     * One primed source, retained tree and immutable model recipe per JMH worker.
     */
    @State(Scope.Thread)
    public open class Scene {
        /**
         * Materialized interior cardinality injected by generated metadata.
         */
        @JvmField
        @Param
        public var window: Window = Window.Forty

        /**
         * Row construction and observation recipe injected before setup.
         */
        @JvmField
        @Param
        public var factory: Factory = Factory.Simple

        /**
         * Measured navigation or invalidation operation.
         */
        @JvmField
        @Param
        public var motion: Motion = Motion.OneRow

        private lateinit var fixture: VirtualWindowFixture

        /**
         * Establishes a settled window and runs independent acceptance outside timings.
         */
        @Setup(Level.Trial)
        public fun setUp() {
            fixture = VirtualWindowFixture(window, factory, motion)
            fixture.attach()
            fixture.verifyWork()
        }

        /**
         * Commits the next fixed operation without rasterization or diagnostic collection.
         */
        public fun nextFrame(): RuntimeUiFrame = fixture.nextFrame()

        /**
         * Releases the current window, retained tree and every observed subscription.
         */
        @TearDown(Level.Trial)
        public fun close() {
            if (::fixture.isInitialized) fixture.close()
        }
    }

    /**
     * Automatic generated-fixture work hook without another launcher registry or Gradle switch.
     */
    public companion object {
        /**
         * Verifies all 72 generated scenes, including cases outside the preselected timing matrix.
         */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(VirtualWindowBenchmark::class.java), setOf("avgt")).size == 72)
            for (window in Window.entries) {
                for (factory in Factory.entries) {
                    for (motion in Motion.entries) {
                        val scene = Scene()
                        scene.window = window
                        scene.factory = factory
                        scene.motion = motion
                        try {
                            scene.setUp()
                        } finally {
                            scene.close()
                        }
                    }
                }
            }
        }
    }
}
