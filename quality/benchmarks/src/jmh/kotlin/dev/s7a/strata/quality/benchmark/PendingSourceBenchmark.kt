package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.semantics
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.runtime.spi.RuntimeUiSession
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription
import dev.s7a.strata.state.map
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.text.UiText
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/**
 * Frozen source-cutoff controls; one measured invocation includes publications and its complete retained frame batch.
 * Source values, descriptions and declarations are caller-owned; setup and terminal release remain outside timing.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class PendingSourceBenchmark {
    /**
     * Completes one declared update batch and returns its immutable visible frame.
     */
    @Benchmark
    public fun sourceFrame(scene: SourceScene): RuntimeUiFrame = scene.nextFrame()

    /**
     * Explicit cardinalities and independent controls admitted through generic benchmark parameter selection.
     */
    public enum class Workload(
        internal val kind: Kind,
        internal val roots: Int,
    ) {
        Idle1(Kind.Idle, 1),
        Idle128(Kind.Idle, 128),
        Idle1024(Kind.Idle, 1_024),
        One1(Kind.One, 1),
        One128(Kind.One, 128),
        One1024(Kind.One, 1_024),
        All1(Kind.All, 1),
        All128(Kind.All, 128),
        All1024(Kind.All, 1_024),
        EqualValue1(Kind.EqualValue, 1),
        EqualValue128(Kind.EqualValue, 128),
        EqualValue1024(Kind.EqualValue, 1_024),
        MapEqual1(Kind.MapEqual, 1),
        MapEqual128(Kind.MapEqual, 128),
        MapEqual1024(Kind.MapEqual, 1_024),
        MapChanged1(Kind.MapChanged, 1),
        MapChanged128(Kind.MapChanged, 128),
        MapChanged1024(Kind.MapChanged, 1_024),
        FanOut128(Kind.FanOut, 1),
        DeepDerived8(Kind.DeepDerived, 1),
        Churn128(Kind.Churn, 128),
        DuringEquality128(Kind.DuringEquality, 128),
    }

    /**
     * Source operation classification without string or numeric domain discriminators.
     */
    internal enum class Kind {
        Idle,
        One,
        All,
        EqualValue,
        MapEqual,
        MapChanged,
        FanOut,
        DeepDerived,
        Churn,
        DuringEquality,
    }

    /**
     * Two immutable visible outputs reused for every source in the fixture.
     */
    private enum class Presentation(
        val color: ArgbColor,
        val label: UiText,
    ) {
        First(ArgbColor(0xFF204060.toInt()), UiText.Literal("first")),
        Second(ArgbColor(0xFF608020.toInt()), UiText.Literal("second")),
        ;

        fun alternate(): Presentation = if (this == First) Second else First
    }

    /**
     * Worker-owned source graph, counters and retained session; no historical scene or monitor survives close.
     */
    @State(Scope.Thread)
    @Suppress("TooManyFunctions") // Fixed source controls and their untimed evidence share one owner.
    public open class SourceScene {
        /**
         * Complete batch selected by JMH; equal-value controls still publish strictly increasing revisions.
         */
        @JvmField
        @Param(
            "Idle1",
            "Idle128",
            "Idle1024",
            "One1",
            "One128",
            "One1024",
            "All1",
            "All128",
            "All1024",
            "EqualValue1",
            "EqualValue128",
            "EqualValue1024",
            "MapEqual1",
            "MapEqual128",
            "MapEqual1024",
            "MapChanged1",
            "MapChanged128",
            "MapChanged1024",
            "FanOut128",
            "DeepDerived8",
            "Churn128",
            "DuringEquality128",
        )
        public var workload: Workload = Workload.Idle1

        private val constraints = Constraints.fixed(16, 16)
        private lateinit var session: RuntimeUiSession
        private lateinit var initial: RuntimeUiFrame
        private lateinit var latest: RuntimeUiFrame
        private var afterEquality: RuntimeUiFrame? = null
        private lateinit var sources: List<Source>
        private lateinit var replacements: List<Source>
        private var presentation = Presentation.First
        private var useReplacement = false
        private var latePublication = false
        private var evaluations = 0L
        private var projections = 0L
        private var comparisons = 0L
        private var acquisitions = 0L
        private var releases = 0L
        private var batches = 0L

        /**
         * Builds sources and immutable visible descriptions, establishes subscriptions and settles one frame.
         */
        @Setup(Level.Trial)
        public fun setUp() {
            sources = List(workload.roots) { Source(Presentation.First) }
            replacements = if (workload.kind == Kind.Churn) List(workload.roots) { Source(Presentation.Second) } else emptyList()
            val observed = observedSources()
            val currentSources = mutableStateOf(observed)
            val leaves: Map<Presentation, Element> =
                Presentation.entries.associateWith { value ->
                    evaluateComponentTree {
                        Spacer(
                            modifier =
                                Modifier.Empty
                                    .size(16, 16)
                                    .background(value.color)
                                    .semantics(Semantics(label = value.label)),
                        )
                    }
                }
            session =
                createRuntimeUiSession {
                    val current = currentSources.value
                    evaluateComponentTree {
                        Stack {
                            current.forEachIndexed { index, source ->
                                Observe(source, key = ElementKey(index)) { value ->
                                    evaluations += 1
                                    element(leaves.getValue(value.presentation))
                                }
                            }
                        }
                    }
                }
            replaceSources = { currentSources.value = it }
            session.attach()
            initial = session.frame(constraints)
            latest = initial
        }

        private var replaceSources: ((List<StateSource<Value>>) -> Unit)? = null

        private fun observedSources(): List<StateSource<Value>> =
            when (workload.kind) {
                Kind.MapEqual -> {
                    sources.map { source -> source.map { projected(Presentation.First) } }
                }

                Kind.MapChanged -> {
                    sources.map { source -> source.map { projected(it.presentation) } }
                }

                Kind.DeepDerived -> {
                    var source: StateSource<Value> = sources.single()
                    repeat(8) { source = source.map { projected(it.presentation) } }
                    listOf(source)
                }

                Kind.FanOut -> {
                    List(128) { sources.single() }
                }

                else -> {
                    sources
                }
            }

        private fun projected(value: Presentation): Value {
            projections += 1
            return Value(value, ::compared)
        }

        private fun compared() {
            comparisons += 1
            if (latePublication) {
                latePublication = false
                sources.last().publish(Presentation.First)
            }
        }

        /**
         * Publishes the fixed control's input; equality publication owns three frames and resets to its original state.
         */
        public fun nextFrame(): RuntimeUiFrame {
            batches += 1
            presentation = presentation.alternate()
            when (workload.kind) {
                Kind.Idle -> {
                    Unit
                }

                Kind.All -> {
                    sources.forEach { it.publish(presentation) }
                }

                Kind.EqualValue -> {
                    sources.forEach { it.publish(Presentation.First) }
                }

                Kind.Churn -> {
                    useReplacement = useReplacement.not()
                    checkNotNull(replaceSources)(if (useReplacement) replacements else sources)
                }

                Kind.DuringEquality -> {
                    sources.first().publish(Presentation.Second)
                    sources.last().publish(Presentation.Second)
                    latePublication = true
                }

                else -> {
                    sources.last().publish(presentation)
                }
            }
            val frame = session.frame(constraints)
            if (workload.kind == Kind.DuringEquality) {
                afterEquality = session.frame(constraints)
                sources.first().publish(Presentation.First)
                latest = session.frame(constraints)
            } else {
                latest = frame
            }
            return frame
        }

        /**
         * Independently checks output snapshots, actual publication/subscription/derived/consumer counts and release.
         */
        public fun verifyWork() {
            val monitor = session.startRenderMonitoring()
            try {
                repeat(4) {
                    val frame = nextFrame()
                    verifyOutput(frame)
                }
                verifyInitialSnapshot()
                val outputCount = if (workload.kind == Kind.FanOut) 128 else workload.roots
                val changedPerBatch = changedRootsPerBatch()
                val evaluationsPerBatch = evaluationsPerBatch()
                val projectionPerBatch = projectionsPerBatch()
                val initialProjections = initialProjections()
                val evidence = monitor.snapshot()
                check(evidence.counts[UiRenderMetric.RootValueChange] == changedPerBatch * batches)
                check(evaluations == outputCount + evaluationsPerBatch * batches)
                check(projections == initialProjections + projectionPerBatch * batches)
                check(evidence.counts[UiRenderMetric.Projection] == projectionPerBatch * batches)
                check(sources.sumOf { it.publications } == publicationsPerBatch() * batches)
                check(acquisitions - releases == workload.roots.toLong())
                check(acquisitions == workload.roots * (if (workload.kind == Kind.Churn) batches + 1 else 1))
                check(releases == if (workload.kind == Kind.Churn) workload.roots * batches else 0L)
                if (workload.kind == Kind.Idle) check(comparisons == 0L)
                println(
                    "${workload.name}: roots=${workload.roots}, batches=$batches, publications=${sources.sumOf { it.publications }}, " +
                        "rootChanges=${changedPerBatch * batches}, evaluations=$evaluations, projections=$projections, comparisons=$comparisons, " +
                        "subscriptions=$acquisitions, releases=$releases, active=${acquisitions - releases}",
                )
            } finally {
                monitor.close()
            }
        }

        private fun changedRootsPerBatch(): Int =
            when (workload.kind) {
                Kind.Idle, Kind.EqualValue, Kind.Churn -> 0
                Kind.All -> workload.roots
                Kind.DuringEquality -> 4
                else -> 1
            }

        private fun evaluationsPerBatch(): Int =
            when (workload.kind) {
                Kind.Idle, Kind.EqualValue, Kind.MapEqual -> 0
                Kind.All, Kind.Churn -> workload.roots
                Kind.FanOut -> 128
                Kind.DuringEquality -> 4
                else -> 1
            }

        private fun projectionsPerBatch(): Int =
            when (workload.kind) {
                Kind.MapEqual, Kind.MapChanged -> 1
                Kind.DeepDerived -> 8
                else -> 0
            }

        private fun initialProjections(): Int =
            when (workload.kind) {
                Kind.MapEqual, Kind.MapChanged -> workload.roots
                Kind.DeepDerived -> 8
                else -> 0
            }

        private fun publicationsPerBatch(): Int =
            when (workload.kind) {
                Kind.Idle, Kind.Churn -> 0
                Kind.All, Kind.EqualValue -> workload.roots
                Kind.DuringEquality -> 4
                else -> 1
            }

        private fun verifyOutput(frame: RuntimeUiFrame) {
            val expected =
                when (workload.kind) {
                    Kind.MapEqual, Kind.Idle, Kind.EqualValue -> List(workload.roots) { Presentation.First }
                    Kind.All -> List(workload.roots) { presentation }
                    Kind.FanOut -> List(128) { presentation }
                    Kind.Churn -> List(workload.roots) { if (useReplacement) Presentation.Second else Presentation.First }
                    Kind.DuringEquality -> List(workload.roots) { index -> if (index == 0 || index == workload.roots - 1) Presentation.Second else Presentation.First }
                    else -> List(workload.roots) { index -> if (index == workload.roots - 1) presentation else Presentation.First }
                }
            verifyFrame(frame, expected)
            if (workload.kind == Kind.Idle || workload.kind == Kind.EqualValue || workload.kind == Kind.MapEqual) check(frame === initial)
            if (workload.kind == Kind.DuringEquality) {
                verifyFrame(
                    checkNotNull(afterEquality),
                    List(workload.roots) { index -> if (index == 0) Presentation.Second else Presentation.First },
                )
                verifyFrame(latest, List(workload.roots) { Presentation.First })
            }
        }

        private fun verifyInitialSnapshot() {
            verifyFrame(initial, List(if (workload.kind == Kind.FanOut) 128 else workload.roots) { Presentation.First })
        }

        private fun verifyFrame(frame: RuntimeUiFrame, expected: List<Presentation>) {
            check(frame.size == IntSize(16, 16))
            check(frame.drawCommands == expected.map { DrawCommand.FillRectangle(IntRect(0, 0, 16, 16), it.color) })
            check(frame.semantics.map { it.semantics.label } == expected.map { it.label })
            check(frame.semantics.all { it.bounds == IntRect(0, 0, 16, 16) })
        }

        /**
         * Releases the current graph, drops scene mutation captures and verifies every acquired subscription closed once.
         */
        @TearDown(Level.Trial)
        public fun close() {
            replaceSources = null
            afterEquality = null
            if (this::session.isInitialized) session.close()
            check(acquisitions == releases)
            check(sources.all { it.observer == null } && replacements.all { it.observer == null })
        }

        /**
         * Single physical worker publisher; every callback revision strictly increases and subscriptions can be readmitted.
         */
        private inner class Source(initial: Presentation) : StateSource<Value> {
            private var snapshot = StateSnapshot(StateRevision(0), Value(initial, ::compared))
            var observer: ((StateSnapshot<Value>) -> Unit)? = null
                private set
            var publications = 0L
                private set

            override fun subscribe(observer: (StateSnapshot<Value>) -> Unit): StateSubscription<Value> {
                check(this.observer == null)
                this.observer = observer
                acquisitions += 1
                return StateSubscription(snapshot) {
                    check(this.observer === observer)
                    this.observer = null
                    releases += 1
                }
            }

            fun publish(value: Presentation) {
                publications += 1
                snapshot = StateSnapshot(StateRevision(snapshot.revision.value + 1), Value(value, ::compared))
                observer?.invoke(snapshot)
            }
        }

        /**
         * Immutable authoritative fixture value; equality can count work or publish a following cutoff without frame reentry.
         */
        private class Value(
            val presentation: Presentation,
            private val onCompare: () -> Unit,
        ) {
            override fun equals(other: Any?): Boolean {
                onCompare()
                return other is Value && presentation == other.presentation
            }

            override fun hashCode(): Int = presentation.hashCode()
        }
    }

    /**
     * Generic collector invokes this verifier automatically without a fixture-specific registry or build flag.
     */
    public companion object {
        /**
         * Exercises the complete declared corpus outside benchmark timing with independent output and lifecycle checks.
         */
        @JvmStatic
        public fun verifyWork() {
            for (workload in Workload.entries) {
                val scene = SourceScene()
                scene.workload = workload
                try {
                    scene.setUp()
                    scene.verifyWork()
                } finally {
                    scene.close()
                }
            }
        }
    }
}
