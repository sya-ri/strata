@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.projection.BuiltinProjection
import dev.s7a.strata.projection.DeclarationProjection
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/**
 * Supplemental native-free field snapshots through all four real factories and their complete public consumers.
 * Immutable inputs are prepared outside timing; construction/encoding and returned results remain inside each operation.
 */
public open class ProjectionFieldSnapshotBenchmark {
    /**
     * Constructs and encodes a fresh public positional declaration, retaining identical spread work on both revisions.
     */
    @Benchmark
    public fun properties(state: Fields): ProjectionValue = state.properties()

    /**
     * Unchanged ordinary one-copy sequence control with prepared caller-owned list membership.
     */
    @Benchmark
    public fun directSequence(state: Fields): ProjectionValue = state.sequence()

    /**
     * Encodes real keyboard, text, button/filter and unchanged pointer inputs.
     */
    @Benchmark
    public fun input(state: Inputs): ProjectionValue = state.encode()

    /**
     * Creates the public subscription and its captured policy without measuring endpoint registration again.
     */
    @Benchmark
    public fun modifierPolicy(state: Modifiers): Modifier = state.create()

    /**
     * Encodes a prepared public subscription using bounded invocation-local endpoint positions.
     */
    @Benchmark
    public fun modifierEndpoints(state: Modifiers): ProjectionValue = state.encode()

    /**
     * Encodes a prepared actual remote element or modifier, including all nested property records.
     */
    @Benchmark
    public fun profileProjection(state: Profiles): ProjectionValue = state.encode()

    /**
     * Evaluates the actual remote public DSL and encodes its resulting profile declaration.
     */
    @Benchmark
    public fun profileEvaluation(state: Profiles): ProjectionValue = state.evaluate()

    /**
     * Projects one unchanged real retained remote owner.
     */
    @Benchmark
    public fun ownerIdle(state: Owners): Int = state.idle()

    /**
     * Includes real scalar source publication and declaration projection.
     */
    @Benchmark
    public fun ownerUpdate(state: Owners): Int = state.update()

    /**
     * Includes a real scalar public input-policy change and declaration tick.
     */
    @Benchmark
    public fun ownerControl(state: Owners): Int = state.control()

    /**
     * Constructs, attaches, updates and closes a fresh owner of an independently prepared source.
     */
    @Benchmark
    public fun ownerLifecycle(state: Owners): Int = state.lifecycle()

    /**
     * Six fixed field counts shared by positional construction and the ordinary sequence control.
     */
    @State(Scope.Thread)
    public open class Fields {
        /**
         * Zero/singleton controls and representative small/large positional records.
         */
        @JvmField
        @Param("0", "1", "2", "4", "8", "128")
        public var size: Int = 0
        private lateinit var values: Array<ProjectionValue>
        private lateinit var list: List<ProjectionValue>
        private val scope = ProjectionFieldScope()

        /**
         * Prepares immutable field values and an independent caller-owned list before timing.
         */
        @Setup(Level.Trial)
        public fun setup() {
            values = Array(size) { ProjectionValue.Integer(it.toLong()) }
            list = values.toList()
        }

        /**
         * Executes the actual public vararg factory and its immediate encoder.
         * The public spread-copy cost is deliberately included on both revisions.
         */
        @Suppress("SpreadOperator")
        public fun properties(): ProjectionValue = BuiltinProjection.Grid.properties(*values).encode(scope)

        /**
         * Executes the ordinary defensive constructor without the changed vararg factories.
         */
        public fun sequence(): ProjectionValue = ProjectionValue.Sequence(list)
    }

    /**
     * Immutable events and independent decoder expectations, with no prior-output history.
     */
    @State(Scope.Thread)
    public open class Inputs {
        /**
         * Standard JMH labels decoded into a closed input family at setup.
         */
        @JvmField
        @Param("KeyboardPress", "KeyboardRelease", "CharacterAscii", "CharacterSupplementary", "PreeditEmpty", "PreeditShort", "PreeditMultipart", "FilterEmpty", "FilterSingle", "FilterMany", "Primary", "Secondary", "Middle", "Auxiliary", "PointerPress", "PointerRelease", "PointerMove", "PointerDrag", "PointerScroll")
        public var workload: String = "KeyboardPress"
        private lateinit var prepared: ProjectionFieldInputCase.Prepared

        /**
         * Prepares the public event/filter/button once on its actual worker.
         */
        @Setup(Level.Trial)
        public fun setup() {
            prepared = ProjectionFieldInputCase.valueOf(workload).prepare()
        }

        /**
         * Returns the actual complete public encoder result.
         */
        public fun encode(): ProjectionValue = prepared.encode()
    }

    /**
     * Captured policy construction and later endpoint projection use the same public subscription cases.
     */
    @State(Scope.Thread)
    public open class Modifiers {
        /**
         * Fixed keyboard, pointer, text and capture subscription variants.
         */
        @JvmField
        @Param("KeyEvery", "KeyPress", "KeyRelease", "PointerEvery", "PointerPress", "PointerRelease", "PointerMove", "PointerDrag", "PointerScroll", "TextEvery", "Character", "Preedit", "Capture")
        public var workload: String = "KeyEvery"
        private lateinit var selected: ProjectionFieldModifierCase
        private lateinit var projection: DeclarationProjection<*>
        private val scope = ProjectionFieldScope()

        /**
         * Prepares the endpoint row's immutable public modifier outside timing.
         */
        @Setup(Level.Trial)
        public fun setup() {
            selected = ProjectionFieldModifierCase.valueOf(workload)
            projection =
                checkNotNull(
                    selected
                        .create()
                        .elements()
                        .single()
                        .projection,
                )
        }

        /**
         * Includes real policy and modifier construction, using the already prepared filter/callbacks.
         */
        public fun create(): Modifier = selected.create()

        /**
         * Includes the immediate field snapshot and resets the finite endpoint positions each operation.
         */
        public fun encode(): ProjectionValue {
            scope.reset()
            return projection.encode(scope)
        }
    }

    /**
     * Prepared caller-owned text state and complete public remote profile evaluation.
     */
    @State(Scope.Thread)
    public open class Profiles {
        /**
         * Zero/singleton outer controls and nested scalar/text/editor records.
         */
        @JvmField
        @Param("MenuBackground", "LoadingIndicator", "ProgressBar", "Button", "Text", "TextArea")
        public var workload: String = "MenuBackground"
        private lateinit var prepared: ProjectionFieldProfileCase.Prepared
        private lateinit var projection: DeclarationProjection<*>
        private val scope = ProjectionFieldScope()

        /**
         * Prepares the execution owner, content, state and projection-only declaration before timing.
         */
        @Setup(Level.Trial)
        public fun setup() {
            prepared = ProjectionFieldProfileCase.valueOf(workload).prepare()
            projection = prepared.projection(prepared.evaluate())
        }

        /**
         * Encodes the prepared actual profile properties with bounded binding positions.
         */
        public fun encode(): ProjectionValue {
            scope.reset()
            return projection.encode(scope)
        }

        /**
         * Includes fresh public declaration construction and the complete property encoding boundary.
         */
        public fun evaluate(): ProjectionValue {
            scope.reset()
            return prepared.projection(prepared.evaluate()).encode(scope)
        }
    }

    /**
     * One steady retained owner and independent lifecycle inputs, confined to the trial's worker.
     */
    @State(Scope.Thread)
    public open class Owners {
        private lateinit var steady: ProjectionFieldOwner
        private lateinit var lifecycleInputs: ProjectionFieldOwner.Inputs

        /**
         * Prepares two independent sources and all immutable callbacks outside operations.
         */
        @Setup(Level.Trial)
        public fun setup() {
            steady = ProjectionFieldOwner(ProjectionFieldOwner.Inputs())
            lifecycleInputs = ProjectionFieldOwner.Inputs()
        }

        /**
         * Executes the actual unchanged owner tick.
         */
        public fun idle(): Int = steady.idle()

        /**
         * Executes scalar source publication and the retained cutoff.
         */
        public fun update(): Int = steady.update()

        /**
         * Executes a public scalar control and retained projection.
         */
        public fun control(): Int = steady.control()

        /**
         * Includes fresh session ownership and terminal release without reusing a retained owner.
         */
        public fun lifecycle(): Int = ProjectionFieldOwner(lifecycleInputs).use(ProjectionFieldOwner::update)

        /**
         * Releases the steady trial owner and requires no lifecycle source subscription to remain.
         */
        @TearDown(Level.Trial)
        public fun close() {
            steady.close()
            steady.verifyClosed()
            check(lifecycleInputs.source.subscriptions == 0 && lifecycleInputs.latest == null)
        }
    }

    /**
     * Untimed work admission shared by ordinary verification and opt-in selected collection.
     */
    public companion object {
        /**
         * Requires complete generated registration, exact codec/schema results and real terminal owner release.
         */
        @JvmStatic
        public fun verifyWork() {
            ProjectionFieldWorkEvidence.verify()
        }
    }
}
