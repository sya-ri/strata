package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.SliderState
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
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.runtime.spi.RuntimeUiSession
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.MutableState
import dev.s7a.strata.state.StateObservation
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription
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
 * Complete public setter batches and retained frames with frozen membership, failure and component controls.
 * Every Observe callback actually reads the caller-owned measured state; its unchanged source is only a required argument.
 * Raster verification and declarations/assets are outside sampled operations.
 */
@OptIn(InternalStrataRuntimeApi::class)
@Suppress("LargeClass") // The complete frozen row inventory and its independent verifier share one generic fixture.
public open class MutableStateRoutingBenchmark {
    /**
     * Runs the row's complete disclosed operation and consumes comparison, retained output and notification work.
     */
    @Benchmark
    public fun operation(scene: AssignmentScene): Long = scene.step()

    /**
     * Operation boundaries; stable batches have 64 setters, FirstAdmission and single-frame rows have one, and clean rows have none.
     * Coalesced performs 63 alternating changes plus one equal setter and one final frame per screen.
     * Cycles performs 64 alternating changes with a completed frame per screen after each write.
     * Cold/churn/close rows include their complete admission, removal, rebuild and release operation.
     * GuardFailure is a privileged observation-contract control; other observed rows use public retained sessions.
     */
    public enum class Kind {
        EqualAlias,
        DistinctEqual,
        Unequal,
        FrameAlias,
        FrameEqual,
        FrameUnequal,
        Coalesced,
        Cycles,
        ColdAdmission,
        FirstAdmission,
        SparseRemoval,
        Replacement,
        LastCloseReuse,
        OwnerReversal,
        EqualityFailure,
        GuardFailure,
        ComponentEqual,
        ComponentUnequal,
        ComponentFrame,
        CleanFrame,
    }

    /**
     * Fixed full row inventory; regions N and distinct owners U are declared inputs rather than cache-derived expectations.
     * Expensive values inspect 2,048 prepared integer entries per equality; repeated reads do not admit new membership.
     */
    public enum class Workload(
        public val kind: Kind,
        public val regions: Int,
        public val owners: Int,
        public val expensive: Boolean = false,
        public val reads: Int = 1,
        public val failingOwner: Int? = null,
    ) {
        EqualAlias0(Kind.EqualAlias, 0, 0),
        EqualAlias1(Kind.EqualAlias, 1, 1),
        EqualAlias128(Kind.EqualAlias, 128, 1),
        EqualAlias4096(Kind.EqualAlias, 4096, 1),
        DistinctEqual0(Kind.DistinctEqual, 0, 0),
        DistinctEqual1(Kind.DistinctEqual, 1, 1),
        DistinctEqual128(Kind.DistinctEqual, 128, 1),
        DistinctEqual4096(Kind.DistinctEqual, 4096, 1),
        Unequal0(Kind.Unequal, 0, 0),
        Unequal1(Kind.Unequal, 1, 1),
        Unequal128(Kind.Unequal, 128, 1),
        Unequal4096(Kind.Unequal, 4096, 1),
        ExpensiveEqual0(Kind.DistinctEqual, 0, 0, expensive = true),
        ExpensiveEqual1(Kind.DistinctEqual, 1, 1, expensive = true),
        ExpensiveEqual128(Kind.DistinctEqual, 128, 1, expensive = true),
        ExpensiveEqual4096(Kind.DistinctEqual, 4096, 1, expensive = true),
        ExpensiveUnequal0(Kind.Unequal, 0, 0, expensive = true),
        ExpensiveUnequal1(Kind.Unequal, 1, 1, expensive = true),
        ExpensiveUnequal128(Kind.Unequal, 128, 1, expensive = true),
        ExpensiveUnequal4096(Kind.Unequal, 4096, 1, expensive = true),
        EqualAlias128Owners4(Kind.EqualAlias, 128, 4),
        Unequal128Owners4(Kind.Unequal, 128, 4),
        EqualAlias4096Owners4(Kind.EqualAlias, 4096, 4),
        Unequal4096Owners4(Kind.Unequal, 4096, 4),
        EqualAlias128Owners128(Kind.EqualAlias, 128, 128),
        Unequal128Owners128(Kind.Unequal, 128, 128),
        FrameAlias1(Kind.FrameAlias, 1, 1),
        FrameAlias128(Kind.FrameAlias, 128, 1),
        FrameAlias4096(Kind.FrameAlias, 4096, 1),
        FrameEqual1(Kind.FrameEqual, 1, 1),
        FrameEqual128(Kind.FrameEqual, 128, 1),
        FrameEqual4096(Kind.FrameEqual, 4096, 1),
        FrameUnequal1(Kind.FrameUnequal, 1, 1),
        FrameUnequal128(Kind.FrameUnequal, 128, 1),
        FrameUnequal4096(Kind.FrameUnequal, 4096, 1),
        Coalesced1(Kind.Coalesced, 1, 1),
        Coalesced128(Kind.Coalesced, 128, 1),
        Coalesced4096(Kind.Coalesced, 4096, 1),
        Cycles1(Kind.Cycles, 1, 1),
        Cycles128(Kind.Cycles, 128, 1),
        Cycles4096(Kind.Cycles, 4096, 1),
        DuplicateReads128(Kind.EqualAlias, 128, 1, reads = 4),
        DuplicateReads4096(Kind.EqualAlias, 4096, 1, reads = 4),
        ColdAdmission1(Kind.ColdAdmission, 1, 1),
        ColdAdmission128(Kind.ColdAdmission, 128, 1),
        ColdAdmission4096(Kind.ColdAdmission, 4096, 1),
        SparseRemoval128(Kind.SparseRemoval, 128, 1),
        SparseRemoval4096(Kind.SparseRemoval, 4096, 1),
        Replacement128(Kind.Replacement, 128, 1),
        Replacement4096(Kind.Replacement, 4096, 1),
        LastCloseReuse128(Kind.LastCloseReuse, 128, 1),
        LastCloseReuse4096(Kind.LastCloseReuse, 4096, 1),
        OwnerReversal3(Kind.OwnerReversal, 3, 2),
        EqualityFailure128Owners1(Kind.EqualityFailure, 128, 1),
        EqualityFailure128Owners4(Kind.EqualityFailure, 128, 4),
        EqualityFailure4096Owners1(Kind.EqualityFailure, 4096, 1),
        GuardFailureFirst(Kind.GuardFailure, 128, 4, failingOwner = 0),
        GuardFailureMiddle(Kind.GuardFailure, 128, 4, failingOwner = 2),
        GuardFailureLast(Kind.GuardFailure, 128, 4, failingOwner = 3),
        ComponentEqual0(Kind.ComponentEqual, 0, 0),
        ComponentEqual1(Kind.ComponentEqual, 1, 1),
        ComponentUnequal128(Kind.ComponentUnequal, 128, 1),
        ComponentFrame128(Kind.ComponentFrame, 128, 1),
        CleanFrame0(Kind.CleanFrame, 0, 0),
        CleanFrame1(Kind.CleanFrame, 1, 1),
        CleanFrame128(Kind.CleanFrame, 128, 1),
        CleanFrame4096(Kind.CleanFrame, 4096, 1),
        FrameEqual128Owners4(Kind.FrameEqual, 128, 4),
        FrameUnequal128Owners4(Kind.FrameUnequal, 128, 4),
        FrameEqual4096Owners4(Kind.FrameEqual, 4096, 4),
        FrameUnequal4096Owners4(Kind.FrameUnequal, 4096, 4),
        FrameEqual128Owners128(Kind.FrameEqual, 128, 128),
        FrameUnequal128Owners128(Kind.FrameUnequal, 128, 128),
        FrameAlias0(Kind.FrameAlias, 0, 0),
        FrameEqual0(Kind.FrameEqual, 0, 0),
        FrameUnequal0(Kind.FrameUnequal, 0, 0),
        ExpensiveFrameEqual4096(Kind.FrameEqual, 4096, 1, expensive = true),
        ExpensiveFrameUnequal4096(Kind.FrameUnequal, 4096, 1, expensive = true),
        FirstAdmission1(Kind.FirstAdmission, 1, 1),
        FirstAdmission128(Kind.FirstAdmission, 128, 1),
        FirstAdmission4096(Kind.FirstAdmission, 4096, 1),
    }

    /**
     * Immutable visible expectations and prepared value identity, independent of the production routing plan.
     */
    private enum class Tone(
        val width: Int,
        val color: ArgbColor,
        val label: UiText,
    ) {
        First(16, ArgbColor(0xff234567.toInt()), UiText.literal("First")),
        Second(17, ArgbColor(0xff789abc.toInt()), UiText.literal("Second")),
    }

    /**
     * Prepared descriptions choose dependency reads and stable or disjoint region-key families.
     */
    private enum class Membership { Full, Sparse, Replacement, InitialOwners, ReversedOwners }

    /**
     * Typed sibling identity whose replacement family is explicit.
     */
    private data class RegionKey(
        val index: Int,
        val membership: Membership,
    )

    /**
     * Arbitrary caller equality with original operand direction and no reference-identity shortcut.
     */
    private class Value(
        val tone: Tone,
        private val payload: IntArray,
        private val compare: () -> Unit,
    ) {
        override fun equals(other: Any?): Boolean {
            compare()
            if (other !is Value) return false
            var same = tone == other.tone
            for (index in payload.indices) {
                if (payload[index] != other.payload[index]) same = false
            }
            return same
        }

        override fun hashCode(): Int = error("Caller values must not be hashed for routing")
    }

    /**
     * One owner-thread scene with current sessions only; stable setup is outside sampling and churn is inside it.
     */
    @State(Scope.Thread)
    @Suppress("TooManyFunctions") // One fixture owns the entire per-operation accounting and terminal release boundary.
    public open class AssignmentScene {
        /**
         * Explicit frozen input selection shared by every baseline and candidate collection.
         */
        @JvmField
        @Param(
            "EqualAlias0",
            "EqualAlias1",
            "EqualAlias128",
            "EqualAlias4096",
            "DistinctEqual0",
            "DistinctEqual1",
            "DistinctEqual128",
            "DistinctEqual4096",
            "Unequal0",
            "Unequal1",
            "Unequal128",
            "Unequal4096",
            "ExpensiveEqual0",
            "ExpensiveEqual1",
            "ExpensiveEqual128",
            "ExpensiveEqual4096",
            "ExpensiveUnequal0",
            "ExpensiveUnequal1",
            "ExpensiveUnequal128",
            "ExpensiveUnequal4096",
            "EqualAlias128Owners4",
            "Unequal128Owners4",
            "EqualAlias4096Owners4",
            "Unequal4096Owners4",
            "EqualAlias128Owners128",
            "Unequal128Owners128",
            "FrameAlias1",
            "FrameAlias128",
            "FrameAlias4096",
            "FrameEqual1",
            "FrameEqual128",
            "FrameEqual4096",
            "FrameUnequal1",
            "FrameUnequal128",
            "FrameUnequal4096",
            "Coalesced1",
            "Coalesced128",
            "Coalesced4096",
            "Cycles1",
            "Cycles128",
            "Cycles4096",
            "DuplicateReads128",
            "DuplicateReads4096",
            "ColdAdmission1",
            "ColdAdmission128",
            "ColdAdmission4096",
            "SparseRemoval128",
            "SparseRemoval4096",
            "Replacement128",
            "Replacement4096",
            "LastCloseReuse128",
            "LastCloseReuse4096",
            "OwnerReversal3",
            "EqualityFailure128Owners1",
            "EqualityFailure128Owners4",
            "EqualityFailure4096Owners1",
            "GuardFailureFirst",
            "GuardFailureMiddle",
            "GuardFailureLast",
            "ComponentEqual0",
            "ComponentEqual1",
            "ComponentUnequal128",
            "ComponentFrame128",
            "CleanFrame0",
            "CleanFrame1",
            "CleanFrame128",
            "CleanFrame4096",
            "FrameEqual128Owners4",
            "FrameUnequal128Owners4",
            "FrameEqual4096Owners4",
            "FrameUnequal4096Owners4",
            "FrameEqual128Owners128",
            "FrameUnequal128Owners128",
            "FrameAlias0",
            "FrameEqual0",
            "FrameUnequal0",
            "ExpensiveFrameEqual4096",
            "ExpensiveFrameUnequal4096",
            "FirstAdmission1",
            "FirstAdmission128",
            "FirstAdmission4096",
        )
        public var workload: Workload = Workload.EqualAlias0

        private val constraints = Constraints.fixed(20, 16)
        private val source = FixedSource()
        private val sessions = ArrayList<RuntimeUiSession>()
        private val guardRoots = ArrayList<StateObservation>()
        private val controls = ArrayList<MutableState<Membership>>()
        private val descriptions = ArrayList<Map<Membership, Element>>()
        private val frames = ArrayList<RuntimeUiFrame>()
        private val failure = IllegalStateException("Prepared assignment failure")
        private lateinit var state: MutableState<Value>
        private lateinit var slider: SliderState
        private lateinit var first: Value
        private lateinit var equal: Value
        private lateinit var second: Value
        private var subscription: AutoCloseable? = null
        private var tone = Tone.First
        private var componentTone = Tone.Second
        private var membership = Membership.Full
        private var failComparison = false
        private var failOwner: Int? = null
        private var comparisons = 0L
        private var writes = 0L
        private var roots = 0L
        private var evaluations = 0L
        private var completedFrames = 0L
        private var controlWrites = 0L
        private var notifications = 0L
        private var begins = 0L
        private var ends = 0L
        private var invalidations = 0L
        private val entered = LinkedHashSet<Int>()

        /**
         * Prepares immutable values, keys and all visible declarations, then admits and settles the initial dependencies.
         */
        @Setup(Level.Trial)
        @Suppress("LongMethod", "NestedBlockDepth") // Immutable public DSL declarations are prepared together outside sampling.
        public fun setUp() {
            val length = if (workload.expensive) 2048 else 0
            first = Value(Tone.First, IntArray(length) { it }, ::compared)
            equal = Value(Tone.First, IntArray(length) { it }, ::compared)
            second = Value(Tone.Second, IntArray(length) { it }, ::compared)
            state = mutableStateOf(first)
            slider = SliderState(1.0)
            if (isComponent()) subscription = slider.observe { notifications += 1 }
            if (workload.kind == Kind.GuardFailure) {
                createGuards()
            } else {
                val leaves =
                    Tone.entries.associateWith { value ->
                        evaluateComponentTree {
                            Spacer(
                                modifier =
                                    Modifier.Empty
                                        .size(value.width, 16)
                                        .background(value.color)
                                        .semantics(Semantics(label = value.label)),
                            )
                        }
                    }
                repeat(screenCount()) { screen ->
                    controls.add(mutableStateOf(Membership.Full))
                    descriptions.add(
                        Membership.entries.associateWith { mode ->
                            evaluateComponentTree {
                                Stack {
                                    indices(screen).forEach { index ->
                                        val family = if (mode == Membership.Replacement) Membership.Replacement else Membership.Full
                                        Observe(source, key = ElementKey(RegionKey(index, family))) {
                                            evaluations += 1
                                            val visible =
                                                if (active(index, mode)) {
                                                    if (isComponent()) {
                                                        if (slider.value == 0.0) Tone.First else Tone.Second
                                                    } else {
                                                        var current = first
                                                        repeat(workload.reads) { current = state.value }
                                                        current.tone
                                                    }
                                                } else {
                                                    Tone.First
                                                }
                                            element(leaves.getValue(visible))
                                        }
                                    }
                                }
                            }
                        },
                    )
                }
                if (workload.kind == Kind.OwnerReversal) membership = Membership.InitialOwners
                openScreens()
            }
            // Admission happens once outside warm rows. Cold/churn rows separately include its complete cost in step().
            state.value = first
            if (isComponent()) slider.value = 1.0
            verifyCurrentStorage()
        }

        private fun compared() {
            comparisons += 1
            if (failComparison) throw failure
        }

        private fun screenCount(): Int = workload.owners.coerceAtLeast(1)

        private fun indices(screen: Int): List<Int> =
            if (workload.kind == Kind.OwnerReversal) {
                if (screen == 0) listOf(0, 2) else listOf(1)
            } else {
                (0 until workload.regions).filter { it % screenCount() == screen }
            }

        private fun active(
            index: Int,
            mode: Membership,
        ): Boolean =
            when (mode) {
                Membership.Full, Membership.Replacement -> true
                Membership.Sparse -> index % 17 != 0
                Membership.InitialOwners -> index != 2
                Membership.ReversedOwners -> index != 0
            }

        private fun isComponent(): Boolean =
            workload.kind == Kind.ComponentEqual || workload.kind == Kind.ComponentUnequal || workload.kind == Kind.ComponentFrame

        private fun openScreens() {
            check(sessions.isEmpty())
            repeat(screenCount()) { screen ->
                controlWrites += 1
                controls[screen].value = membership
                val session =
                    createRuntimeUiSession {
                        roots += 1
                        descriptions[screen].getValue(controls[screen].value)
                    }
                sessions.add(session)
                session.attach()
            }
            completeFrames()
        }

        private fun closeScreens() {
            sessions.forEach(RuntimeUiSession::close)
            sessions.clear()
            frames.clear()
            verifyReleasedStorage()
            check(source.active == 0)
        }

        private fun completeFrames() {
            frames.clear()
            sessions.forEach {
                frames.add(it.frame(constraints))
                completedFrames += 1
            }
        }

        private fun changeMembership(next: Membership) {
            membership = next
            controls.forEach {
                controlWrites += 1
                it.value = next
            }
            completeFrames()
        }

        private fun createGuards() {
            repeat(workload.owners) { index ->
                guardRoots.add(
                    StateObservation(
                        {
                            begins += 1
                            if (index == failOwner) throw failure
                            check(entered.add(index))
                        },
                        {
                            check(entered.remove(index))
                            ends += 1
                        },
                        {},
                        {},
                    ),
                )
            }
            repeat(workload.regions) { index ->
                guardRoots[index % workload.owners].fork { invalidations += 1 }.evaluate { state.value }
            }
        }

        private fun assign(value: Value) {
            writes += 1
            state.value = value
        }

        private fun alternate() {
            tone = if (tone == Tone.First) Tone.Second else Tone.First
            assign(if (tone == Tone.First) first else second)
        }

        private fun assignFailure() {
            writes += 1
            val caught =
                try {
                    state.value = second
                    error("Prepared assignment must fail")
                } catch (caught: IllegalStateException) {
                    caught
                }
            check(caught === failure)
            check(entered.isEmpty())
        }

        /**
         * Executes the complete row with no pixel rasterization, expectation construction or history retained in the sample.
         */
        @Suppress("CyclomaticComplexMethod", "LongMethod") // The fixed row inventory has one explicit operation dispatcher.
        public fun step(): Long {
            when (workload.kind) {
                Kind.EqualAlias -> repeat(64) { assign(first) }
                Kind.DistinctEqual -> repeat(64) { assign(equal) }
                Kind.Unequal -> repeat(64) { alternate() }
                Kind.FrameAlias -> {
                    assign(first)
                    completeFrames()
                }

                Kind.FrameEqual -> {
                    assign(equal)
                    completeFrames()
                }

                Kind.FrameUnequal -> {
                    alternate()
                    completeFrames()
                }

                Kind.Coalesced -> {
                    repeat(63) { alternate() }
                    assign(if (tone == Tone.First) first else second)
                    completeFrames()
                }

                Kind.Cycles -> repeat(64) {
                    alternate()
                    completeFrames()
                }

                Kind.ColdAdmission -> {
                    closeScreens()
                    openScreens()
                    repeat(64) { alternate() }
                    completeFrames()
                }

                Kind.FirstAdmission -> {
                    closeScreens()
                    openScreens()
                    alternate()
                    completeFrames()
                }

                Kind.SparseRemoval, Kind.Replacement -> {
                    val changed = if (workload.kind == Kind.SparseRemoval) Membership.Sparse else Membership.Replacement
                    changeMembership(if (membership == Membership.Full) changed else Membership.Full)
                    repeat(64) { alternate() }
                    completeFrames()
                }

                Kind.LastCloseReuse -> {
                    alternate()
                    closeScreens()
                    repeat(63) { alternate() }
                    openScreens()
                }

                Kind.OwnerReversal -> {
                    closeScreens()
                    membership = Membership.InitialOwners
                    openScreens()
                    changeMembership(Membership.Full)
                    alternate()
                    changeMembership(Membership.ReversedOwners)
                    repeat(63) { alternate() }
                    completeFrames()
                }

                Kind.EqualityFailure -> {
                    failComparison = true
                    try {
                        repeat(64) { assignFailure() }
                    } finally {
                        failComparison = false
                    }
                }

                Kind.GuardFailure -> {
                    failOwner = workload.failingOwner
                    try {
                        repeat(64) { assignFailure() }
                    } finally {
                        failOwner = null
                    }
                }

                Kind.ComponentEqual -> repeat(64) {
                    writes += 1
                    slider.value = 2.0
                }

                Kind.ComponentUnequal -> repeat(64) {
                    writes += 1
                    slider.value = if (slider.value == 0.0) 2.0 else -1.0
                }

                Kind.ComponentFrame -> {
                    componentTone = if (componentTone == Tone.First) Tone.Second else Tone.First
                    writes += 1
                    slider.value = if (slider.value == 0.0) 2.0 else -1.0
                    completeFrames()
                }

                Kind.CleanFrame -> completeFrames()
            }
            return comparisons + evaluations + notifications + invalidations + frames.sumOf { it.drawCommands.size.toLong() } + state.value.tone.ordinal
        }

        private fun counters(): LongArray =
            longArrayOf(writes, comparisons, roots, evaluations, completedFrames, source.acquired, source.released, notifications, begins, ends, invalidations, controlWrites)

        /**
         * Checks two complete operations against literal independent work counts and full frame/pixel expectations.
         * Reflection reads only actual storage; no cache builder, token or owner projection calculates the reference.
         */
        public fun verifyWork(): JsonObject {
            val oldFrames = frames.toList()
            val oldTones = expectedTones()
            val before = counters()
            val initialMembership = membership
            step()
            val firstOperation = counters().zip(before).map { (after, previous) -> after - previous }
            verifyCounts(firstOperation, initialMembership)
            verifyCurrentStorage()
            frames.forEachIndexed { index, frame -> verifyFrame(frame, expectedTones()[index]) }
            oldFrames.forEachIndexed { index, frame -> verifyFrame(frame, oldTones[index]) }
            val secondBefore = counters()
            val nextMembership = membership
            step()
            verifyCounts(counters().zip(secondBefore).map { (after, previous) -> after - previous }, nextMembership)
            verifyCurrentStorage()
            frames.forEachIndexed { index, frame -> verifyFrame(frame, expectedTones()[index]) }
            check(state.value === first || state.value === second)
            if (workload.kind == Kind.EqualityFailure || workload.kind == Kind.GuardFailure) check(state.value === first)
            return JsonObject().apply {
                addProperty("workload", workload.name)
                addProperty("declaredRegions", workload.regions)
                addProperty("declaredOwners", workload.owners)
                addProperty("retainedScreens", if (workload.kind == Kind.GuardFailure) 0 else screenCount())
                addProperty("measuredStateSettersPerOperation", firstOperation[0])
                addProperty("controllerSettersPerOperation", firstOperation[11])
                addProperty("preparedValueComparisons", firstOperation[1])
                addProperty("rootEvaluations", firstOperation[2])
                addProperty("regionEvaluations", firstOperation[3])
                addProperty("completedFrames", firstOperation[4])
                addProperty("sourceAcquisitions", firstOperation[5])
                addProperty("sourceReleases", firstOperation[6])
                addProperty("componentNotifications", firstOperation[7])
                addProperty("contractGuardBegins", firstOperation[8])
                addProperty("contractGuardEnds", firstOperation[9])
                addProperty("contractInvalidations", firstOperation[10])
                addProperty("storageVerification", "Actual private storage checked when the selected runtime exposes a plan")
            }
        }

        @Suppress("CyclomaticComplexMethod", "LongMethod") // Literal row expectations stay independent of runtime routing.
        private fun verifyCounts(
            actual: List<Long>,
            previousMembership: Membership,
        ) {
            val n = workload.regions.toLong()
            val u = screenCount().toLong()
            val writes =
                when (workload.kind) {
                    Kind.FrameAlias, Kind.FrameEqual, Kind.FrameUnequal, Kind.ComponentFrame, Kind.FirstAdmission -> 1L
                    Kind.CleanFrame -> 0L
                    else -> 64L
                }
            val comparisons = if (workload.kind == Kind.GuardFailure || isComponent()) 0L else writes
            val roots =
                when (workload.kind) {
                    Kind.ColdAdmission, Kind.FirstAdmission, Kind.LastCloseReuse, Kind.SparseRemoval, Kind.Replacement -> u
                    Kind.OwnerReversal -> 6L
                    else -> 0L
                }
            val evaluated =
                when (workload.kind) {
                    Kind.FrameUnequal, Kind.Coalesced, Kind.ComponentFrame -> n
                    Kind.Cycles -> n * 64
                    Kind.ColdAdmission, Kind.FirstAdmission, Kind.Replacement -> n * 2
                    Kind.LastCloseReuse -> n
                    Kind.SparseRemoval -> {
                        val selected = if (previousMembership == Membership.Full) Membership.Sparse else Membership.Full
                        n + (0 until workload.regions).count { active(it, selected) }
                    }

                    Kind.OwnerReversal -> 11L
                    else -> 0L
                }
            val frames =
                when (workload.kind) {
                    Kind.FrameAlias, Kind.FrameEqual, Kind.FrameUnequal, Kind.Coalesced, Kind.CleanFrame, Kind.ComponentFrame -> u
                    Kind.Cycles -> u * 64
                    Kind.ColdAdmission, Kind.FirstAdmission, Kind.SparseRemoval, Kind.Replacement -> u * 2
                    Kind.LastCloseReuse -> u
                    Kind.OwnerReversal -> 8L
                    else -> 0L
                }
            val sourceChanges = if (workload.kind == Kind.ColdAdmission || workload.kind == Kind.FirstAdmission || workload.kind == Kind.LastCloseReuse || workload.kind == Kind.OwnerReversal) u else 0L
            val notified =
                when (workload.kind) {
                    Kind.ComponentUnequal -> 64L
                    Kind.ComponentFrame -> 1L
                    else -> 0L
                }
            val guardBegins = if (workload.kind == Kind.GuardFailure) (checkNotNull(workload.failingOwner) + 1) * 64L else 0L
            val guardEnds = if (workload.kind == Kind.GuardFailure) checkNotNull(workload.failingOwner) * 64L else 0L
            val controllerWrites =
                when (workload.kind) {
                    Kind.ColdAdmission, Kind.FirstAdmission, Kind.LastCloseReuse, Kind.SparseRemoval, Kind.Replacement -> u
                    Kind.OwnerReversal -> u * 3
                    else -> 0L
                }
            check(actual == listOf(writes, comparisons, roots, evaluated, frames, sourceChanges, sourceChanges, notified, guardBegins, guardEnds, 0L, controllerWrites)) {
                "Independent operation counts differ for ${workload}: actual=$actual"
            }
        }

        private fun expectedTones(): List<List<Tone>> =
            List(if (workload.kind == Kind.GuardFailure) 0 else screenCount()) { screen ->
                indices(screen).map { index ->
                    if (active(index, membership).not()) {
                        Tone.First
                    } else if (isComponent()) {
                        componentTone
                    } else {
                        tone
                    }
                }
            }

        private fun verifyFrame(
            frame: RuntimeUiFrame,
            expected: List<Tone>,
        ) {
            check(frame.size == IntSize(20, 16))
            check(frame.drawCommands == expected.map { DrawCommand.FillRectangle(IntRect(0, 0, it.width, 16), it.color) })
            check(frame.semantics.map { it.semantics.label } == expected.map { it.label })
            check(frame.semantics.map { it.bounds } == expected.map { IntRect(0, 0, it.width, 16) })
            for (scale in listOf(1, 2)) {
                val image = rasterizeHeadless(frame.drawCommands, frame.size, scale)
                for (y in 0 until 16 * scale) {
                    for (x in 0 until 20 * scale) {
                        val last = expected.lastOrNull { x < it.width * scale }
                        check(image.argbAt(x, y) == (last?.color?.value ?: 0))
                    }
                }
            }
        }

        private fun privateField(
            instance: Any,
            name: String,
        ): Any? {
            val field = instance.javaClass.declaredFields.singleOrNull { it.name == name } ?: return null
            field.isAccessible = true
            return field.get(instance)
        }

        private fun measuredState(): MutableState<*> =
            if (isComponent()) {
                val observable = checkNotNull(privateField(slider, "observable"))
                checkNotNull(privateField(observable, "currentValue")) as MutableState<*>
            } else {
                state
            }

        private fun verifyCurrentStorage() {
            if (isComponent()) check(slider.value == if (componentTone == Tone.First) 0.0 else 1.0)
            val current = measuredState()
            val members = privateField(current, "observations") as Set<*>
            val expected = (0 until workload.regions).count { active(it, membership) }
            check(members.size == expected)
            val plan = privateField(current, "observationPlan") ?: return
            check((privateField(plan, "observations") as List<*>).size == expected)
            check((privateField(plan, "owners") as List<*>).size == workload.owners)
        }

        private fun verifyReleasedStorage() {
            check((privateField(measuredState(), "observations") as Set<*>).isEmpty())
            check(privateField(measuredState(), "observationPlan") == null)
            check((privateField(state, "observations") as Set<*>).isEmpty())
            check(privateField(state, "observationPlan") == null)
        }

        /**
         * Releases current owners and callback captures without a future write, with balanced source/component handles.
         */
        @TearDown(Level.Trial)
        public fun close() {
            if (workload.kind != Kind.GuardFailure) closeScreens()
            guardRoots.forEach(StateObservation::close)
            guardRoots.clear()
            subscription?.close()
            subscription = null
            descriptions.clear()
            controls.clear()
            verifyReleasedStorage()
            check(source.acquired == source.released)
        }

        /**
         * Fixed unchanged public Observe argument supporting independent screen subscriptions on one worker.
         */
        private class FixedSource : StateSource<Int> {
            private val snapshot = StateSnapshot(StateRevision(0), 0)
            private val observers = LinkedHashSet<(StateSnapshot<Int>) -> Unit>()
            var acquired = 0L
                private set
            var released = 0L
                private set
            val active: Int
                get() = observers.size

            override fun subscribe(observer: (StateSnapshot<Int>) -> Unit): StateSubscription<Int> {
                check(observers.add(observer))
                acquired += 1
                return StateSubscription(snapshot) {
                    check(observers.remove(observer))
                    released += 1
                }
            }
        }
    }

    /**
     * Generic fixture-selection verifier; every frozen row runs before a collection is accepted.
     */
    public companion object {
        /**
         * Checks the full inventory and prints untimed work evidence, without treating it as measured CPU or allocation.
         */
        @JvmStatic
        public fun verifyWork() {
            val evidence = JsonArray()
            for (workload in Workload.entries) {
                val scene = AssignmentScene().apply { this.workload = workload }
                try {
                    scene.setUp()
                    evidence.add(scene.verifyWork())
                } finally {
                    scene.close()
                }
            }
            println(evidence)
        }
    }
}
