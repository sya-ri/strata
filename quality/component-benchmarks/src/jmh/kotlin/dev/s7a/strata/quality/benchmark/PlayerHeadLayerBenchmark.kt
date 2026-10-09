package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.IntConsumer
import java.util.function.LongSupplier

/**
 * Fixed 220-case public PlayerHead corpus, without timing assertions or candidate-internal entry points.
 * Actual read/construction instrumentation runs only in a separate untimed child loader.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class PlayerHeadLayerBenchmark {
    /**
     * First hidden-head frame after untimed attachment; requested layer preparation is included.
     */
    @Benchmark
    public fun coldHidden(state: ColdHidden): RuntimeUiFrame = state.frame()

    /**
     * First visible-head frame after untimed attachment.
     */
    @Benchmark
    public fun coldVisible(state: ColdVisible): RuntimeUiFrame = state.frame()

    /**
     * Replaces source identity with fixed immutable inputs, keeping the hat hidden.
     */
    @Benchmark
    public fun replaceHiddenSkin(state: Warm): RuntimeUiFrame = state.replace(false)

    /**
     * Replaces source identity while requesting both layers.
     */
    @Benchmark
    public fun replaceVisibleSkin(state: Warm): RuntimeUiFrame = state.replace(true)

    /**
     * Replaces logical extent, including both requested layers and real layout.
     */
    @Benchmark
    public fun replaceSize(state: Warm): RuntimeUiFrame = state.resize()

    /**
     * First hat enablement after an untimed hidden frame with the same complete cache key.
     */
    @Benchmark
    public fun enableHat(state: EnableHat): RuntimeUiFrame = state.frame()

    /**
     * Three actual false/true/false frames after both layers have been prepared.
     */
    @Benchmark
    public fun toggleCycle(state: Warm): RuntimeUiFrame = state.cycle()

    /**
     * One actual head-paint invalidation by layer selection, with both images already prepared.
     */
    @Benchmark
    public fun preparedDirtyRepaint(state: Warm): RuntimeUiFrame = state.dirty()

    /**
     * Clean immutable-frame reuse on a visible prepared host.
     */
    @Benchmark
    public fun cleanFrame(state: Warm): RuntimeUiFrame = state.frame()

    /**
     * Complete fresh hidden lifetime, including declaration, host, attachment, frame and terminal close.
     */
    @Benchmark
    public fun hiddenLifetime(state: Warm): RuntimeUiFrame = state.lifetime(false)

    /**
     * Complete fresh visible lifetime with the same immutable assets.
     */
    @Benchmark
    public fun visibleLifetime(state: Warm): RuntimeUiFrame = state.lifetime(true)

    /**
     * Prepared independent host per JMH worker; all operations keep bounded current state.
     */
    @State(Scope.Thread)
    public open class Warm {
        /**
         * Complete fixed workload selected by generated JMH parameters.
         */
        @JvmField
        @Param
        public var workload: PlayerHeadWorkload = PlayerHeadWorkload.SyncOne10

        private lateinit var assets: PlayerHeadFixture.Assets
        private lateinit var fixture: PlayerHeadFixture

        /**
         * Builds assets and prepares both layers outside timing.
         */
        @Setup(Level.Trial)
        public fun setup() {
            assets = PlayerHeadFixture.Assets()
            fixture = PlayerHeadFixture(workload, assets, true)
            fixture.attach()
            fixture.frame()
        }

        /**
         * Actual clean frame extraction.
         */
        public fun frame(): RuntimeUiFrame = fixture.frame()

        /**
         * Actual source commit and resulting frame.
         */
        public fun replace(visible: Boolean): RuntimeUiFrame {
            fixture.replace(visible)
            return fixture.frame()
        }

        /**
         * Actual size replacement and resulting frame.
         */
        public fun resize(): RuntimeUiFrame {
            fixture.resize()
            return fixture.frame()
        }

        /**
         * Actual prepared dirty frame.
         */
        public fun dirty(): RuntimeUiFrame = fixture.dirty()

        /**
         * Actual complete selection cycle with stable source/size.
         */
        public fun cycle(): RuntimeUiFrame {
            fixture.hat(false)
            fixture.frame()
            fixture.hat(true)
            fixture.frame()
            fixture.hat(false)
            return fixture.frame()
        }

        /**
         * Fresh independent complete lifetime using the same immutable assets.
         */
        public fun lifetime(visible: Boolean): RuntimeUiFrame =
            PlayerHeadFixture(workload, assets, visible).use { fresh ->
                fresh.attach()
                fresh.frame()
            }

        /**
         * Terminal cleanup outside retained-operation timing.
         */
        @TearDown(Level.Trial)
        public fun close(): Unit = fixture.close()
    }

    /**
     * Fresh hidden preparation per invocation, with assets shared only within the owner trial.
     */
    @State(Scope.Thread)
    public open class ColdHidden {
        /**
         * Same complete fixed workload used by every operation.
         */
        @JvmField
        @Param
        public var workload: PlayerHeadWorkload = PlayerHeadWorkload.SyncOne10

        private lateinit var assets: PlayerHeadFixture.Assets
        private lateinit var fixture: PlayerHeadFixture

        /**
         * Creates identical immutable inputs outside timing.
         */
        @Setup(Level.Trial)
        public fun setup() {
            assets = PlayerHeadFixture.Assets()
        }

        /**
         * Attaches one fresh host outside timing.
         */
        @Setup(Level.Invocation)
        public fun begin() {
            fixture = PlayerHeadFixture(workload, assets, false)
            fixture.attach()
        }

        /**
         * Times only the first actual frame.
         */
        public fun frame(): RuntimeUiFrame = fixture.frame()

        /**
         * Releases the fresh host outside cold-frame timing.
         */
        @TearDown(Level.Invocation)
        public fun close(): Unit = fixture.close()
    }

    /**
     * Fresh visible preparation per invocation, with assets shared only within the owner trial.
     */
    @State(Scope.Thread)
    public open class ColdVisible {
        /**
         * Same complete fixed workload used by every operation.
         */
        @JvmField
        @Param
        public var workload: PlayerHeadWorkload = PlayerHeadWorkload.SyncOne10

        private lateinit var assets: PlayerHeadFixture.Assets
        private lateinit var fixture: PlayerHeadFixture

        /**
         * Creates identical immutable inputs outside timing.
         */
        @Setup(Level.Trial)
        public fun setup() {
            assets = PlayerHeadFixture.Assets()
        }

        /**
         * Attaches one fresh host outside timing.
         */
        @Setup(Level.Invocation)
        public fun begin() {
            fixture = PlayerHeadFixture(workload, assets, true)
            fixture.attach()
        }

        /**
         * Times only the first actual frame.
         */
        public fun frame(): RuntimeUiFrame = fixture.frame()

        /**
         * Releases the fresh host outside cold-frame timing.
         */
        @TearDown(Level.Invocation)
        public fun close(): Unit = fixture.close()
    }

    /**
     * Fresh hidden then enabled preparation per invocation, with assets shared only within the owner trial.
     */
    @State(Scope.Thread)
    public open class EnableHat {
        /**
         * Same complete fixed workload used by every operation.
         */
        @JvmField
        @Param
        public var workload: PlayerHeadWorkload = PlayerHeadWorkload.SyncOne10

        private lateinit var assets: PlayerHeadFixture.Assets
        private lateinit var fixture: PlayerHeadFixture

        /**
         * Creates identical immutable inputs outside timing.
         */
        @Setup(Level.Trial)
        public fun setup() {
            assets = PlayerHeadFixture.Assets()
        }

        /**
         * Attaches one fresh host and prepares only the hidden request outside timing.
         */
        @Setup(Level.Invocation)
        public fun begin() {
            fixture = PlayerHeadFixture(workload, assets, false)
            fixture.attach()
            fixture.frame()
        }

        /**
         * Times only first enablement and its resulting frame.
         */
        public fun frame(): RuntimeUiFrame {
            fixture.hat(true)
            return fixture.frame()
        }

        /**
         * Releases the fresh host outside cold-frame timing.
         */
        @TearDown(Level.Invocation)
        public fun close(): Unit = fixture.close()
    }

    /**
     * Untimed immutable-corpus admission called by the existing generated-fixture discovery.
     */
    public companion object {
        /**
         * Launches the bundled source probe with this exact runtime/classpath and propagates every failure.
         * Temporary source is owned by this invocation and is removed without recursive deletion.
         */
        @JvmStatic
        public fun verifyWork() {
            val directory = Files.createTempDirectory("strata-player-head-probe-")
            val source = directory.resolve("PlayerHeadReadProbe.java")
            try {
                checkNotNull(PlayerHeadLayerBenchmark::class.java.getResourceAsStream("/PlayerHeadReadProbe.java")).use { Files.copy(it, source) }
                val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
                val process = ProcessBuilder(java, "--class-path", System.getProperty("java.class.path"), source.toString()).inheritIO().start()
                check(process.waitFor() == 0) { "Untimed PlayerHead source-read/pixel/ownership admission failed." }
            } finally {
                Files.deleteIfExists(source)
                Files.deleteIfExists(directory)
            }
        }

        /**
         * Bootstrap callback boundary for the separate instrumented loader; no Strata type crosses that boundary.
         */
        @JvmStatic
        public fun verifyReadWork(
            begin: IntConsumer,
            reads: LongSupplier,
            images: LongSupplier,
            stop: Runnable,
        ): Unit = PlayerHeadWorkProof.verify(begin, reads, images, stop)
    }
}
