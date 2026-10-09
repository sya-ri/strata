package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.TextLayout
import dev.s7a.strata.text.TextOverflow
import dev.s7a.strata.text.TextWrap
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import dev.s7a.strata.quality.benchmark.TextLineLayoutBenchmark.Shape as MetricShape

/**
 * Complete bounded display-range corpus through public retained Text and unchanged editable controls.
 * Initial/edit/reflow/height scores include both restoring opportunities and both frames; clean scores include one frame.
 * All content, fonts, policies, sizes and identities are prepared before measurement.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class DisplayLineLayoutBenchmark {
    /**
     * Executes the declared complete public cycle with the same CPU and allocation boundary.
     */
    @Benchmark
    public fun layout(scene: Scene): RuntimeUiFrame = scene.operation()

    /**
     * Every fixed source case, including limits, fonts, ordering and unbounded/unwrapped/editable controls.
     * Dimensions are logical pixels; Int.MAX_VALUE explicitly requests an unbounded structural limit.
     */
    public enum class Case(
        internal val shape: Shape,
        internal val operation: Operation,
        internal val policy: TextLayout.Multiline,
        internal val viewport: IntSize,
        internal val consumer: Consumer,
    ) {
        LongCharacterLine1Initial(Shape.Long, Operation.Initial, TextLayout.Multiline(TextWrap.Character, 1, TextOverflow.Clip, 2), IntSize(48, 64), Consumer.Display),
        LongCharacterLine3Initial(Shape.Long, Operation.Initial, TextLayout.Multiline(TextWrap.Character, 3, TextOverflow.Clip, 2), IntSize(48, 64), Consumer.Display),
        LongCharacterHeight0Initial(Shape.Long, Operation.Initial, TextLayout.Multiline(TextWrap.Character, Int.MAX_VALUE, TextOverflow.Clip, 2), IntSize(48, 0), Consumer.Display),
        LongCharacterHeight1Initial(Shape.Long, Operation.Initial, TextLayout.Multiline(TextWrap.Character, Int.MAX_VALUE, TextOverflow.Clip, 2), IntSize(48, 1), Consumer.Display),
        LongCharacterHeight9Initial(Shape.Long, Operation.Initial, TextLayout.Multiline(TextWrap.Character, Int.MAX_VALUE, TextOverflow.Clip, 2), IntSize(48, 9), Consumer.Display),
        LongCharacterHeight10Initial(Shape.Long, Operation.Initial, TextLayout.Multiline(TextWrap.Character, Int.MAX_VALUE, TextOverflow.Clip, 2), IntSize(48, 10), Consumer.Display),
        LongWordLine1Initial(Shape.Word, Operation.Initial, TextLayout.Multiline(TextWrap.Word, 1, TextOverflow.Clip, 2), IntSize(48, 64), Consumer.Display),
        LongWordHeight12Initial(Shape.Word, Operation.Initial, TextLayout.Multiline(TextWrap.Word, Int.MAX_VALUE, TextOverflow.Clip, 2), IntSize(48, 12), Consumer.Display),
        SupplementaryLine1Initial(Shape.Supplementary, Operation.Initial, TextLayout.Multiline(TextWrap.Character, 1, TextOverflow.Clip, 2), IntSize(48, 64), Consumer.Display),
        SupplementaryHeight12Initial(Shape.Supplementary, Operation.Initial, TextLayout.Multiline(TextWrap.Character, Int.MAX_VALUE, TextOverflow.Clip, 2), IntSize(48, 12), Consumer.Display),
        HardBreakLine1Initial(Shape.HardBreaks, Operation.Initial, TextLayout.Multiline(TextWrap.None, 1, TextOverflow.Clip, 2), IntSize(48, 64), Consumer.Display),
        HardBreakHeight12Initial(Shape.HardBreaks, Operation.Initial, TextLayout.Multiline(TextWrap.None, Int.MAX_VALUE, TextOverflow.Clip, 2), IntSize(48, 12), Consumer.Display),
        MandatoryBreakLine3Initial(Shape.MandatoryBreaks, Operation.Initial, TextLayout.Multiline(TextWrap.None, 3, TextOverflow.Clip, 2), IntSize(48, 64), Consumer.Display),
        MixedFontsLine1Initial(Shape.MixedFonts, Operation.Initial, TextLayout.Multiline(TextWrap.Word, 1, TextOverflow.Clip, 2), IntSize(48, 64), Consumer.Display),
        MixedFontsHeight12Initial(Shape.MixedFonts, Operation.Initial, TextLayout.Multiline(TextWrap.Word, Int.MAX_VALUE, TextOverflow.Clip, 2), IntSize(48, 12), Consumer.Display),
        BidiLine1Initial(Shape.DisplayOrder, Operation.Initial, TextLayout.Multiline(TextWrap.Word, 1, TextOverflow.Clip, 2), IntSize(48, 64), Consumer.Display),
        SignedLine1Initial(Shape.Signed, Operation.Initial, TextLayout.Multiline(TextWrap.Character, 1, TextOverflow.Clip, 2), IntSize(48, 64), Consumer.Display),
        ZeroLine1Initial(Shape.Zero, Operation.Initial, TextLayout.Multiline(TextWrap.Character, 1, TextOverflow.Clip, 2), IntSize(48, 64), Consumer.Display),
        ExceptionalLine1Initial(Shape.Exceptional, Operation.Initial, TextLayout.Multiline(TextWrap.Character, 1, TextOverflow.Clip, 2), IntSize(48, 64), Consumer.Display),
        EllipsisFirstInitial(Shape.Long, Operation.Initial, TextLayout.Multiline(TextWrap.Character, 1, TextOverflow.Ellipsis, 2), IntSize(9, 64), Consumer.Display),
        EllipsisMiddleInitial(Shape.Long, Operation.Initial, TextLayout.Multiline(TextWrap.Character, 1, TextOverflow.Ellipsis, 2), IntSize(12, 64), Consumer.Display),
        EllipsisLastInitial(Shape.Long, Operation.Initial, TextLayout.Multiline(TextWrap.Character, 1, TextOverflow.Ellipsis, 2), IntSize(48, 64), Consumer.Display),
        EmptyZeroHeightInitial(Shape.Empty, Operation.Initial, TextLayout.Multiline(TextWrap.Character, 1, TextOverflow.Clip, 2), IntSize(48, 0), Consumer.Display),
        ShortLine1Initial(Shape.Short, Operation.Initial, TextLayout.Multiline(TextWrap.Character, 1, TextOverflow.Clip, 2), IntSize(48, 64), Consumer.Display),
        ZeroWidthInitial(Shape.Short, Operation.Initial, TextLayout.Multiline(TextWrap.Character, 1, TextOverflow.Clip, 2), IntSize(0, 64), Consumer.Display),
        UnwrappedLongInitial(Shape.Long, Operation.Initial, TextLayout.Multiline(TextWrap.None, 1, TextOverflow.Clip, 2), IntSize(48, 64), Consumer.Display),
        UnboundedLongInitial(Shape.Long, Operation.Initial, TextLayout.Multiline(TextWrap.Character, Int.MAX_VALUE, TextOverflow.Clip, 2), IntSize(48, Int.MAX_VALUE), Consumer.Display),
        SingleLineLongInitial(Shape.Long, Operation.Initial, TextLayout.Multiline(TextWrap.None, 1, TextOverflow.Clip, 2), IntSize(30000, 9), Consumer.SingleLine),
        TextAreaLongInitial(Shape.Long, Operation.Initial, TextLayout.Multiline(TextWrap.Character, Int.MAX_VALUE, TextOverflow.Clip, 2), IntSize(48, 64), Consumer.TextArea),
        CharacterEdit(Shape.Long, Operation.Edit, TextLayout.Multiline(TextWrap.Character, 1, TextOverflow.Clip, 2), IntSize(48, 64), Consumer.Display),
        WordEdit(Shape.Word, Operation.Edit, TextLayout.Multiline(TextWrap.Word, 1, TextOverflow.Clip, 2), IntSize(48, 64), Consumer.Display),
        MixedEdit(Shape.MixedFonts, Operation.Edit, TextLayout.Multiline(TextWrap.Word, 1, TextOverflow.Clip, 2), IntSize(48, 64), Consumer.Display),
        BidiEdit(Shape.DisplayOrder, Operation.Edit, TextLayout.Multiline(TextWrap.Word, 1, TextOverflow.Clip, 2), IntSize(48, 64), Consumer.Display),
        UnwrappedEdit(Shape.Long, Operation.Edit, TextLayout.Multiline(TextWrap.None, 1, TextOverflow.Clip, 2), IntSize(48, 64), Consumer.Display),
        TextAreaEdit(Shape.Long, Operation.Edit, TextLayout.Multiline(TextWrap.Character, Int.MAX_VALUE, TextOverflow.Clip, 2), IntSize(48, 64), Consumer.TextArea),
        CharacterReflow(Shape.Long, Operation.Reflow, TextLayout.Multiline(TextWrap.Character, 1, TextOverflow.Clip, 2), IntSize(48, 64), Consumer.Display),
        WordReflow(Shape.Word, Operation.Reflow, TextLayout.Multiline(TextWrap.Word, 1, TextOverflow.Clip, 2), IntSize(48, 64), Consumer.Display),
        MixedReflow(Shape.MixedFonts, Operation.Reflow, TextLayout.Multiline(TextWrap.Word, 1, TextOverflow.Clip, 2), IntSize(48, 64), Consumer.Display),
        UnboundedReflow(Shape.Long, Operation.Reflow, TextLayout.Multiline(TextWrap.Character, Int.MAX_VALUE, TextOverflow.Clip, 2), IntSize(48, Int.MAX_VALUE), Consumer.Display),
        TextAreaReflow(Shape.Long, Operation.Reflow, TextLayout.Multiline(TextWrap.Character, Int.MAX_VALUE, TextOverflow.Clip, 2), IntSize(48, 64), Consumer.TextArea),
        CharacterHeight(Shape.Long, Operation.Height, TextLayout.Multiline(TextWrap.Character, Int.MAX_VALUE, TextOverflow.Clip, 2), IntSize(48, 12), Consumer.Display),
        WordHeight(Shape.Word, Operation.Height, TextLayout.Multiline(TextWrap.Word, Int.MAX_VALUE, TextOverflow.Clip, 2), IntSize(48, 12), Consumer.Display),
        SupplementaryHeight(Shape.Supplementary, Operation.Height, TextLayout.Multiline(TextWrap.Character, Int.MAX_VALUE, TextOverflow.Clip, 2), IntSize(48, 12), Consumer.Display),
        HardBreakHeight(Shape.HardBreaks, Operation.Height, TextLayout.Multiline(TextWrap.None, Int.MAX_VALUE, TextOverflow.Clip, 2), IntSize(48, 12), Consumer.Display),
        CharacterClean(Shape.Long, Operation.Clean, TextLayout.Multiline(TextWrap.Character, 1, TextOverflow.Clip, 2), IntSize(48, 64), Consumer.Display),
        WordClean(Shape.Word, Operation.Clean, TextLayout.Multiline(TextWrap.Word, 1, TextOverflow.Clip, 2), IntSize(48, 64), Consumer.Display),
        EmptyClean(Shape.Empty, Operation.Clean, TextLayout.Multiline(TextWrap.Character, 1, TextOverflow.Clip, 2), IntSize(48, 64), Consumer.Display),
        ShortClean(Shape.Short, Operation.Clean, TextLayout.Multiline(TextWrap.Character, 1, TextOverflow.Clip, 2), IntSize(48, 64), Consumer.Display),
        UnwrappedClean(Shape.Long, Operation.Clean, TextLayout.Multiline(TextWrap.None, 1, TextOverflow.Clip, 2), IntSize(48, 64), Consumer.Display),
        UnboundedClean(Shape.Long, Operation.Clean, TextLayout.Multiline(TextWrap.Character, Int.MAX_VALUE, TextOverflow.Clip, 2), IntSize(48, Int.MAX_VALUE), Consumer.Display),
        SingleLineClean(Shape.Long, Operation.Clean, TextLayout.Multiline(TextWrap.None, 1, TextOverflow.Clip, 2), IntSize(30000, 9), Consumer.SingleLine),
        TextAreaClean(Shape.Long, Operation.Clean, TextLayout.Multiline(TextWrap.Character, Int.MAX_VALUE, TextOverflow.Clip, 2), IntSize(48, 64), Consumer.TextArea),
    }

    /**
     * Prepared scalar and metric families; whole-line display ordering remains a deterministic backend probe.
     */
    internal enum class Shape(
        val metrics: MetricShape,
    ) {
        Empty(MetricShape.Empty),
        Short(MetricShape.Short),
        Long(MetricShape.Bmp32767),
        Word(MetricShape.Wrapped),
        Supplementary(MetricShape.Supplementary32767Scalars),
        HardBreaks(MetricShape.HardBreaks),
        MandatoryBreaks(MetricShape.HardBreaks),
        MixedFonts(MetricShape.MixedFonts),
        DisplayOrder(MetricShape.DisplayOrder),
        Signed(MetricShape.Signed),
        Zero(MetricShape.ZeroAdvance),
        Exceptional(MetricShape.ExceptionalMetrics),
    }

    /**
     * Full public opportunity, with initial meaning keyed fresh components inside a primed font owner.
     */
    internal enum class Operation {
        Initial,
        Edit,
        Reflow,
        Height,
        Clean,
    }

    /**
     * Real public consumer; editable layout and the original single-line path remain controls.
     */
    internal enum class Consumer {
        Display,
        SingleLine,
        TextArea,
    }

    /**
     * One current host whose verification and release run only at trial boundaries.
     */
    @State(Scope.Thread)
    public open class Scene {
        /**
         * Exact generated workload with no fixture-specific launch selection.
         */
        @JvmField
        @Param
        public var workload: Case = Case.LongCharacterLine1Initial

        private lateinit var fixture: DisplayLineLayoutFixture

        /**
         * Prepares and verifies complete layout, scalar, work, pixel and release invariants outside collection.
         */
        @Setup(Level.Trial)
        public fun setUp() {
            fixture = DisplayLineLayoutFixture(workload)
            fixture.verify()
        }

        /**
         * Delivers the complete declared cycle using already prepared values and resources.
         */
        public fun operation(): RuntimeUiFrame = fixture.operation()

        /**
         * Releases the retained owner and borrowed font service.
         */
        @TearDown(Level.Trial)
        public fun close(): Unit = fixture.close()
    }

    /**
     * Generic all-fixture work verification; no Gradle registry, flag or benchmark launcher branch is added.
     */
    public companion object {
        /**
         * Requires all 52 generated cases and independent full-layout/pixel/lifetime verification.
         */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(DisplayLineLayoutBenchmark::class.java), setOf("avgt")).size == 52)
            Case.entries.forEach { workload -> DisplayLineLayoutFixture(workload).use { fixture -> fixture.verify() } }
        }
    }
}
