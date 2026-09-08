package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.VirtualList
import dev.s7a.strata.component.VirtualListState
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.size
import dev.s7a.strata.runtime.diagnostics.UiRenderMonitor
import dev.s7a.strata.runtime.diagnostics.UiRenderSnapshot
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.runtime.spi.RuntimeUiSession
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.map
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/**
 * Measures reactive frame time/allocation with identical scenes and optional bounded diagnostic collection.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class ReactiveRenderingBenchmark {
    /**
     * Publishes one scenario input and returns a completed retained frame.
     */
    @Benchmark
    public fun reactiveFrame(state: ReactiveSession): RuntimeUiFrame = state.nextFrame()

    /**
     * One caller-owned retained session per JMH worker.
     */
    @State(Scope.Thread)
    public open class ReactiveSession {
        /**
         * Reactive workload selected by JMH.
         */
        @JvmField
        @Param("Static", "Single", "MapEqual", "MapChanged", "Nested", "Independent128", "FanOut128", "ListAppend", "ListPrepend")
        public var scenario: ReactiveWorkload = ReactiveWorkload.Static

        /**
         * Enables collection; both modes use the same API, geometry, and publication sequence.
         */
        @JvmField
        @Param("false", "true")
        public var monitoring: Boolean = false

        private lateinit var session: RuntimeUiSession
        private lateinit var source: BenchmarkStateSource<Int>
        private lateinit var items: BenchmarkStateSource<List<Int>>
        private var monitor: UiRenderMonitor? = null
        private var revision = 0
        private val normal = (0 until 200).toList()
        private val appended = (0..200).toList()
        private val prepended = (-1 until 200).toList()
        private val constraints = Constraints.fixed(320, 180)

        /**
         * Establishes sources, declarations, and the initial settled frame outside measured work.
         */
        @Setup(Level.Trial)
        public fun setup() {
            source = BenchmarkStateSource(0)
            items = BenchmarkStateSource(normal)
            val mapped = source.map { if (scenario == ReactiveWorkload.MapEqual) 0 else it % 2 }
            val independent = List(128) { if (it == 42) source else BenchmarkStateSource(0) }
            val navigation = VirtualListState<Int>()
            session =
                createRuntimeUiSession {
                    evaluateComponentTree {
                        Column {
                            when (scenario) {
                                ReactiveWorkload.Static, ReactiveWorkload.Single -> Observe(source) { Spacer(modifier = Modifier.Empty.size(it % 2 + 1, 1)) }
                                ReactiveWorkload.MapEqual, ReactiveWorkload.MapChanged -> Observe(mapped) { Spacer(modifier = Modifier.Empty.size(it + 1, 1)) }
                                ReactiveWorkload.Nested -> Observe(source) { parent -> Observe(source) { child -> Spacer(modifier = Modifier.Empty.size((parent + child) % 3 + 1, 1)) } }
                                ReactiveWorkload.Independent128 -> independent.forEach { value -> Observe(value) { Spacer(modifier = Modifier.Empty.size(it % 2 + 1, 1)) } }
                                ReactiveWorkload.FanOut128 -> repeat(128) { Observe(mapped) { Spacer(modifier = Modifier.Empty.size(it + 1, 1)) } }
                                ReactiveWorkload.ListAppend, ReactiveWorkload.ListPrepend -> VirtualList(items, keyOf = { it }, state = navigation, viewportSize = IntSize(160, 60), rowHeight = 12) { Spacer() }
                            }
                        }
                    }
                }
            session.attach()
            session.frame(constraints)
            if (monitoring) monitor = session.startRenderMonitoring()
        }

        /**
         * Measures source publication plus one frame; diagnostic intervals span 64 invocations.
         */
        public fun nextFrame(): RuntimeUiFrame {
            if (revision % 64 == 0) monitor?.checkpoint()
            revision += 1
            when (scenario) {
                ReactiveWorkload.Static -> Unit
                ReactiveWorkload.ListAppend -> items.publish(if (revision % 2 == 0) normal else appended)
                ReactiveWorkload.ListPrepend -> items.publish(if (revision % 2 == 0) normal else prepended)
                else -> source.publish(revision)
            }
            return session.frame(constraints)
        }

        /**
         * Returns detached work evidence outside timed invocations for the deterministic verification runner.
         */
        public fun workSnapshot(): UiRenderSnapshot = checkNotNull(monitor).snapshot()

        /**
         * Verifies complete interval evidence and releases every retained resource on the worker thread.
         */
        @TearDown(Level.Trial)
        public fun close() {
            monitor?.let {
                check(it.snapshot().overflowed.not())
                it.close()
            }
            session.close()
        }
    }
}
