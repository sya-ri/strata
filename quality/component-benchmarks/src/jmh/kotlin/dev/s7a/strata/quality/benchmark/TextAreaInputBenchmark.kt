package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.input.InputResult
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
import java.util.function.IntUnaryOperator

/**
 * Measures actual retained TextArea input cycles, complete initial-layout ownership, clean frames and isolated logical lookup separately.
 * Immutable synthetic glyph metrics and resources are prepared outside timing; the shared kit owns forks and allocation collection.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class TextAreaInputBenchmark {
    /**
     * Includes public opportunity restoration and one prebuilt target input, so CPU and allocation share the same cycle boundary.
     */
    @Benchmark
    public fun input(scene: InputScene): InputResult = scene.input()

    /**
     * Creates, attaches, lays out and closes a fresh host using prepared resources, exposing admission/construction cost separately.
     */
    @Benchmark
    public fun initialLayout(scene: LayoutScene): RuntimeUiFrame = scene.initialLayout()

    /**
     * Reuses the prepared frame without input or layout replacement.
     */
    @Benchmark
    public fun clean(scene: LayoutScene): RuntimeUiFrame = scene.clean()

    /**
     * Calls the real line through a prebound primitive handle; handle invocation is common to both runtime sides.
     */
    @Benchmark
    public fun lookup(scene: LookupScene): Int = scene.lookup()

    /**
     * Fixed whole-input matrix, rather than a Cartesian product with mostly redundant rows.
     */
    public enum class Workload(
        internal val shape: Shape,
        internal val operation: Operation,
        internal val point: Point,
        internal val composed: Boolean = false,
    ) {
        ShortPressBeginning(Shape.ShortWrapped, Operation.Primary, Point.Beginning),
        ShortPressMiddle(Shape.ShortWrapped, Operation.Primary, Point.Middle),
        ShortPressEnd(Shape.ShortWrapped, Operation.Primary, Point.End),
        BmpPressBeginning(Shape.LongBmp, Operation.Primary, Point.Beginning),
        BmpPressMiddle(Shape.LongBmp, Operation.Primary, Point.Middle),
        BmpPressEnd(Shape.LongBmp, Operation.Primary, Point.End),
        SupplementaryPressBeginning(Shape.Supplementary, Operation.Primary, Point.Beginning),
        SupplementaryPressMiddle(Shape.Supplementary, Operation.Primary, Point.Middle),
        SupplementaryPressEnd(Shape.Supplementary, Operation.Primary, Point.End),
        UpBeginning(Shape.TwoLines, Operation.Up, Point.Beginning),
        UpMiddle(Shape.TwoLines, Operation.Up, Point.Middle),
        UpEnd(Shape.TwoLines, Operation.Up, Point.End),
        DownBeginning(Shape.TwoLines, Operation.Down, Point.Beginning),
        DownMiddle(Shape.TwoLines, Operation.Down, Point.Middle),
        DownEnd(Shape.TwoLines, Operation.Down, Point.End),
        PageUpBeginning(Shape.TwoLines, Operation.PageUp, Point.Beginning),
        PageUpMiddle(Shape.TwoLines, Operation.PageUp, Point.Middle),
        PageUpEnd(Shape.TwoLines, Operation.PageUp, Point.End),
        PageDownBeginning(Shape.TwoLines, Operation.PageDown, Point.Beginning),
        PageDownMiddle(Shape.TwoLines, Operation.PageDown, Point.Middle),
        PageDownEnd(Shape.TwoLines, Operation.PageDown, Point.End),
        PlateauPressBeginning(Shape.Plateau, Operation.Primary, Point.Beginning),
        PlateauPressMiddle(Shape.Plateau, Operation.Primary, Point.Middle),
        PlateauPressEnd(Shape.Plateau, Operation.Primary, Point.End),
        SignedPressBeginning(Shape.Signed, Operation.Primary, Point.Beginning),
        SignedPressMiddle(Shape.Signed, Operation.Primary, Point.Middle),
        SignedPressEnd(Shape.Signed, Operation.Primary, Point.End),
        ComposedBefore(Shape.LongBmp, Operation.Primary, Point.Beginning, composed = true),
        ComposedInside(Shape.LongBmp, Operation.Primary, Point.Middle, composed = true),
        ComposedAfter(Shape.LongBmp, Operation.Primary, Point.End, composed = true),
    }

    /**
     * Prepared source and actual face metrics; every source respects the default UTF-16 capacity.
     */
    public enum class Shape {
        ShortWrapped,
        LongBmp,
        Supplementary,
        TwoLines,
        Plateau,
        Signed,
    }

    /**
     * Logical starting column, or before/inside/after the fixed composition.
     */
    public enum class Point {
        Beginning,
        Middle,
        End,
    }

    /**
     * One public input operation, with preparation of its opposite direction excluded from sampling.
     */
    internal enum class Operation {
        Primary,
        Up,
        Down,
        PageUp,
        PageDown,
    }

    /**
     * Actual public input owner; each measured cycle restores its opportunity through public input and frame settlement.
     */
    @State(Scope.Thread)
    public open class InputScene {
        /**
         * Exact input case generated by JMH.
         */
        @JvmField
        @Param
        public var workload: Workload = Workload.ShortPressBeginning

        private lateinit var fixture: TextAreaInputFixture

        /**
         * Prepares and independently verifies this real editor before sampling.
         */
        @Setup(Level.Trial)
        public fun setUp() {
            fixture = TextAreaInputFixture(workload.shape, workload.point, workload.operation, workload.composed)
            fixture.verifyInput()
        }

        /**
         * Includes fixed preparation and target dispatch without per-invocation setup timestamps or hidden profiler allocation.
         */
        public fun input(): InputResult = fixture.inputCycle()

        /**
         * Closes the host and requires zero face/backend ownership and a released state observer.
         */
        @TearDown(Level.Trial)
        public fun close(): Unit = fixture.close()
    }

    /**
     * Prepared initial-layout and clean-frame controls for every metric/source family.
     */
    @State(Scope.Thread)
    public open class LayoutScene {
        /**
         * Source family shared with the input corpus.
         */
        @JvmField
        @Param
        public var shape: Shape = Shape.ShortWrapped

        private lateinit var fixture: TextAreaInputFixture

        /**
         * Prepares immutable resources and the independent clean control.
         */
        @Setup(Level.Trial)
        public fun setUp() {
            fixture = TextAreaInputFixture(shape)
            fixture.verifyClean()
        }

        /**
         * Returns an unchanged frame.
         */
        public fun clean(): RuntimeUiFrame = fixture.frame()

        /**
         * Includes all ownership around a first real layout, excluding resource-byte generation.
         */
        public fun initialLayout(): RuntimeUiFrame = fixture.initialLayout()

        /**
         * Releases the current control host.
         */
        @TearDown(Level.Trial)
        public fun close(): Unit = fixture.close()
    }

    /**
     * One real current-line lookup with common primitive handle dispatch and no session preparation inside timing.
     */
    @State(Scope.Thread)
    public open class LookupScene {
        /**
         * Source/metric family; small, plateau and signed rows remain controls.
         */
        @JvmField
        @Param
        public var shape: Shape = Shape.ShortWrapped

        /**
         * Three independent compiled probe positions.
         */
        @JvmField
        @Param
        public var point: Point = Point.Beginning

        private lateinit var fixture: TextAreaInputFixture
        private lateinit var operation: IntUnaryOperator
        private var x = 0

        /**
         * Resolves the existing line method and checks it against the complete scan outside timing.
         */
        @Setup(Level.Trial)
        public fun setUp() {
            fixture = TextAreaInputFixture(shape, point)
            val line = fixture.currentLine()
            x = fixture.lookupX(line, point)
            operation = TextAreaLookupAccess.create(line)
            fixture.verifyLookup(line, x, operation)
        }

        /**
         * Returns a primitive offset without reflective argument/result boxing.
         */
        public fun lookup(): Int = operation.applyAsInt(x)

        /**
         * Releases the owning host after the line's final use.
         */
        @TearDown(Level.Trial)
        public fun close() {
            fixture.close()
            operation = IntUnaryOperator.identity()
        }
    }

    /**
     * Untimed acceptance discovered automatically by the generic shared collector.
     */
    public companion object {
        /**
         * Requires the complete 60-case registry, input oracle, clean reuse and exact resource release.
         */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(TextAreaInputBenchmark::class.java), setOf("avgt")).size == 60)
            for (workload in Workload.entries) {
                TextAreaInputFixture(workload.shape, workload.point, workload.operation, workload.composed).use { fixture -> fixture.verifyInput() }
            }
            for (shape in Shape.entries) verifyControls(shape)
        }

        private fun verifyControls(shape: Shape) {
            TextAreaInputFixture(shape).use { fixture ->
                fixture.verifyClean()
                fixture.initialLayout()
            }
            for (point in Point.entries) {
                TextAreaInputFixture(shape, point).use { fixture ->
                    val line = fixture.currentLine()
                    fixture.verifyLookup(line, fixture.lookupX(line, point), TextAreaLookupAccess.create(line))
                }
            }
        }
    }
}
