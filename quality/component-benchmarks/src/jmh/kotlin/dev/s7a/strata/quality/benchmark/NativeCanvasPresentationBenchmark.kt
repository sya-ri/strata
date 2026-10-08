package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasPresentation
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/**
 * Separates detached membership publication from real device preparation with deterministic CPU-only driver callbacks.
 * GPU capture, uploads and consumption are unavailable here and require the separate native Canvas corpus.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class NativeCanvasPresentationBenchmark {
    /**
     * Publishes completed immutable command and receipt lists through the actual internal JVM constructor.
     * The identical pre-resolved method-handle dispatch is included on both runtime sides; list construction is untimed.
     * Canvas-free cases diagnose constructor storage only, since Fabric bypasses this path for those screens.
     */
    @Benchmark
    public fun publishMembership(state: Scene): NativeCanvasPresentation = state.publish()

    /**
     * Prepares and cancels a real batch, including mapping, validation, captures and nonblocking lifetime cleanup.
     * Canvas-free controls mirror Fabric's admission bypass; they return the original commands without executing the versioned presenter.
     */
    @Benchmark
    public fun prepareBatch(state: Scene): List<DrawCommand> = state.prepare()

    /**
     * Representative membership sizes and generation policies; deterministic verification checks their complete cross-product separately.
     *
     * @property canvasCount number of independent native attachments, bounded below the device quota.
     * @property portableCount number of surrounding portable fills; clips and a final overlay remain ordered.
     * @property changed whether each measured preparation commits another generation.
     * @property initiallyUnavailable whether no first native generation can commit.
     */
    public enum class Workload(
        public val canvasCount: Int,
        public val portableCount: Int,
        public val changed: Boolean = true,
        public val initiallyUnavailable: Boolean = false,
    ) {
        EmptyControl(0, 0),
        SmallPortableControl(0, 8),
        LargePortableControl(0, 10_000),
        SingleUnchangedSmall(1, 8, changed = false),
        SingleChangedLarge(1, 10_000),
        ManyUnchangedSmall(16, 8, changed = false),
        ManyChangedLarge(16, 10_000),
        InitialUnavailable(1, 8, initiallyUnavailable = true),
    }

    /**
     * One isolated device and immutable publication input per JMH worker, with no accumulated presentation history.
     */
    @State(Scope.Thread)
    public open class Scene {
        /**
         * Fixed compiled request shape supplied before setup.
         */
        @JvmField
        @Param
        public var workload: Workload = Workload.EmptyControl
        private lateinit var fixture: NativeCanvasPresentationWorkload

        /**
         * Primes matching generation receipts and resolves constructor dispatch outside measurement.
         */
        @Setup(Level.Trial)
        public fun setUp() {
            fixture = NativeCanvasPresentationWorkload(workload.canvasCount, workload.portableCount, workload.changed, workload.initiallyUnavailable)
        }

        /**
         * Transfers the fixture's completed read-only membership without resolving any live resource.
         */
        public fun publish(): NativeCanvasPresentation = fixture.publish()

        /**
         * Executes the request-admission and actual device path, releasing the unqueued batch before returning.
         */
        public fun prepare(): List<DrawCommand> = fixture.prepare()

        /**
         * Releases retained trees, producers and target permits outside the measured interval.
         */
        @TearDown(Level.Trial)
        public fun close() {
            fixture.close()
        }
    }

    /**
     * Untimed independent command, pixel and terminal-retention acceptance discovered by the generic fixture runner.
     */
    public companion object {
        /**
         * Checks the compiled matrix and every small/large, zero/one/many, unchanged/changed request combination.
         */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(NativeCanvasPresentationBenchmark::class.java), setOf("avgt")).size == 16)
            for (canvasCount in listOf(0, 1, 16)) {
                for (portableCount in listOf(0, 8, 10_000)) {
                    for (changed in listOf(false, true)) {
                        NativeCanvasPresentationWorkload(canvasCount, portableCount, changed, initiallyUnavailable = false).use { it.verify() }
                    }
                }
            }
            NativeCanvasPresentationWorkload(1, 8, changed = true, initiallyUnavailable = true).use { it.verify() }
        }
    }
}
