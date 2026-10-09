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
 * Compares real retained line construction with primed clean, caret and composition controls.
 * Fonts, profile assets, values, keys, sizes and input events are prepared before collection.
 * Every score includes its complete public state/input/frame opportunity with one fixed current owner.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class TextLineLayoutBenchmark {
    /**
     * Replaces a retained node, edits content or reflows width while keeping the primed font owner.
     */
    @Benchmark
    public fun layout(scene: LayoutScene): RuntimeUiFrame = scene.operation()

    /**
     * Measures clean reuse, a restoring caret cycle or a complete composition/focused-range cycle separately.
     */
    @Benchmark
    public fun control(scene: ControlScene): RuntimeUiFrame = scene.operation()

    /**
     * Complete construction cases; initial means a fresh retained component using an already prepared host/font owner.
     */
    public enum class LayoutCase(
        internal val consumer: Consumer,
        internal val shape: Shape,
        internal val operation: LayoutOperation,
    ) {
        TextAreaEmptyInitial(Consumer.TextArea, Shape.Empty, LayoutOperation.Initial),
        TextAreaShortInitial(Consumer.TextArea, Shape.Short, LayoutOperation.Initial),
        TextAreaBmp128Initial(Consumer.TextArea, Shape.Bmp128, LayoutOperation.Initial),
        TextAreaBmp32767Initial(Consumer.TextArea, Shape.Bmp32767, LayoutOperation.Initial),
        TextAreaSupplementary32767ScalarsInitial(Consumer.TextArea, Shape.Supplementary32767Scalars, LayoutOperation.Initial),
        TextAreaWrappedInitial(Consumer.TextArea, Shape.Wrapped, LayoutOperation.Initial),
        TextAreaHardBreaksInitial(Consumer.TextArea, Shape.HardBreaks, LayoutOperation.Initial),
        TextAreaSignedInitial(Consumer.TextArea, Shape.Signed, LayoutOperation.Initial),
        TextAreaZeroAdvanceInitial(Consumer.TextArea, Shape.ZeroAdvance, LayoutOperation.Initial),
        TextAreaMixedFontsInitial(Consumer.TextArea, Shape.MixedFonts, LayoutOperation.Initial),
        TextAreaExceptionalMetricsInitial(Consumer.TextArea, Shape.ExceptionalMetrics, LayoutOperation.Initial),
        TextAreaDisplayOrderInitial(Consumer.TextArea, Shape.DisplayOrder, LayoutOperation.Initial),
        MultilineTextEmptyInitial(Consumer.MultilineText, Shape.Empty, LayoutOperation.Initial),
        MultilineTextShortInitial(Consumer.MultilineText, Shape.Short, LayoutOperation.Initial),
        MultilineTextBmp128Initial(Consumer.MultilineText, Shape.Bmp128, LayoutOperation.Initial),
        MultilineTextBmp32767Initial(Consumer.MultilineText, Shape.Bmp32767, LayoutOperation.Initial),
        MultilineTextSupplementary32767ScalarsInitial(Consumer.MultilineText, Shape.Supplementary32767Scalars, LayoutOperation.Initial),
        MultilineTextWrappedInitial(Consumer.MultilineText, Shape.Wrapped, LayoutOperation.Initial),
        MultilineTextHardBreaksInitial(Consumer.MultilineText, Shape.HardBreaks, LayoutOperation.Initial),
        MultilineTextSignedInitial(Consumer.MultilineText, Shape.Signed, LayoutOperation.Initial),
        MultilineTextZeroAdvanceInitial(Consumer.MultilineText, Shape.ZeroAdvance, LayoutOperation.Initial),
        MultilineTextMixedFontsInitial(Consumer.MultilineText, Shape.MixedFonts, LayoutOperation.Initial),
        MultilineTextExceptionalMetricsInitial(Consumer.MultilineText, Shape.ExceptionalMetrics, LayoutOperation.Initial),
        MultilineTextDisplayOrderInitial(Consumer.MultilineText, Shape.DisplayOrder, LayoutOperation.Initial),
        MultilineTextEllipsisFirstInitial(Consumer.MultilineText, Shape.EllipsisFirst, LayoutOperation.Initial),
        MultilineTextEllipsisMiddleInitial(Consumer.MultilineText, Shape.EllipsisMiddle, LayoutOperation.Initial),
        MultilineTextEllipsisLastInitial(Consumer.MultilineText, Shape.EllipsisLast, LayoutOperation.Initial),
        TextAreaShortEdit(Consumer.TextArea, Shape.Short, LayoutOperation.Edit),
        TextAreaBmp128Edit(Consumer.TextArea, Shape.Bmp128, LayoutOperation.Edit),
        TextAreaBmp32767Edit(Consumer.TextArea, Shape.Bmp32767, LayoutOperation.Edit),
        TextAreaSupplementary32767ScalarsEdit(Consumer.TextArea, Shape.Supplementary32767Scalars, LayoutOperation.Edit),
        TextAreaWrappedEdit(Consumer.TextArea, Shape.Wrapped, LayoutOperation.Edit),
        TextAreaHardBreaksEdit(Consumer.TextArea, Shape.HardBreaks, LayoutOperation.Edit),
        TextAreaSignedEdit(Consumer.TextArea, Shape.Signed, LayoutOperation.Edit),
        TextAreaZeroAdvanceEdit(Consumer.TextArea, Shape.ZeroAdvance, LayoutOperation.Edit),
        TextAreaMixedFontsEdit(Consumer.TextArea, Shape.MixedFonts, LayoutOperation.Edit),
        TextAreaShortReflow(Consumer.TextArea, Shape.Short, LayoutOperation.Reflow),
        TextAreaBmp32767Reflow(Consumer.TextArea, Shape.Bmp32767, LayoutOperation.Reflow),
        TextAreaWrappedReflow(Consumer.TextArea, Shape.Wrapped, LayoutOperation.Reflow),
        TextAreaHardBreaksReflow(Consumer.TextArea, Shape.HardBreaks, LayoutOperation.Reflow),
        TextAreaSignedReflow(Consumer.TextArea, Shape.Signed, LayoutOperation.Reflow),
        TextAreaMixedFontsReflow(Consumer.TextArea, Shape.MixedFonts, LayoutOperation.Reflow),
        MultilineTextShortEdit(Consumer.MultilineText, Shape.Short, LayoutOperation.Edit),
        MultilineTextBmp128Edit(Consumer.MultilineText, Shape.Bmp128, LayoutOperation.Edit),
        MultilineTextBmp32767Edit(Consumer.MultilineText, Shape.Bmp32767, LayoutOperation.Edit),
        MultilineTextSupplementary32767ScalarsEdit(Consumer.MultilineText, Shape.Supplementary32767Scalars, LayoutOperation.Edit),
        MultilineTextWrappedEdit(Consumer.MultilineText, Shape.Wrapped, LayoutOperation.Edit),
        MultilineTextHardBreaksEdit(Consumer.MultilineText, Shape.HardBreaks, LayoutOperation.Edit),
        MultilineTextSignedEdit(Consumer.MultilineText, Shape.Signed, LayoutOperation.Edit),
        MultilineTextZeroAdvanceEdit(Consumer.MultilineText, Shape.ZeroAdvance, LayoutOperation.Edit),
        MultilineTextMixedFontsEdit(Consumer.MultilineText, Shape.MixedFonts, LayoutOperation.Edit),
        MultilineTextShortReflow(Consumer.MultilineText, Shape.Short, LayoutOperation.Reflow),
        MultilineTextBmp32767Reflow(Consumer.MultilineText, Shape.Bmp32767, LayoutOperation.Reflow),
        MultilineTextWrappedReflow(Consumer.MultilineText, Shape.Wrapped, LayoutOperation.Reflow),
        MultilineTextHardBreaksReflow(Consumer.MultilineText, Shape.HardBreaks, LayoutOperation.Reflow),
        MultilineTextSignedReflow(Consumer.MultilineText, Shape.Signed, LayoutOperation.Reflow),
        MultilineTextMixedFontsReflow(Consumer.MultilineText, Shape.MixedFonts, LayoutOperation.Reflow),
        MultilineTextEllipsisFirstReflow(Consumer.MultilineText, Shape.EllipsisFirst, LayoutOperation.Reflow),
        MultilineTextEllipsisMiddleReflow(Consumer.MultilineText, Shape.EllipsisMiddle, LayoutOperation.Reflow),
        MultilineTextEllipsisLastReflow(Consumer.MultilineText, Shape.EllipsisLast, LayoutOperation.Reflow),
    }

    /**
     * Explicit unchanged and editing controls; ellipsis belongs only to display text.
     */
    public enum class ControlCase(
        internal val consumer: Consumer,
        internal val shape: Shape,
        internal val operation: ControlOperation,
        internal val region: CompositionRegion = CompositionRegion.Before,
    ) {
        TextAreaEmptyClean(Consumer.TextArea, Shape.Empty, ControlOperation.Clean),
        TextAreaShortClean(Consumer.TextArea, Shape.Short, ControlOperation.Clean),
        TextAreaBmp32767Clean(Consumer.TextArea, Shape.Bmp32767, ControlOperation.Clean),
        TextAreaWrappedClean(Consumer.TextArea, Shape.Wrapped, ControlOperation.Clean),
        TextAreaSignedClean(Consumer.TextArea, Shape.Signed, ControlOperation.Clean),
        TextAreaMixedFontsClean(Consumer.TextArea, Shape.MixedFonts, ControlOperation.Clean),
        MultilineTextEmptyClean(Consumer.MultilineText, Shape.Empty, ControlOperation.Clean),
        MultilineTextShortClean(Consumer.MultilineText, Shape.Short, ControlOperation.Clean),
        MultilineTextBmp32767Clean(Consumer.MultilineText, Shape.Bmp32767, ControlOperation.Clean),
        MultilineTextWrappedClean(Consumer.MultilineText, Shape.Wrapped, ControlOperation.Clean),
        MultilineTextSignedClean(Consumer.MultilineText, Shape.Signed, ControlOperation.Clean),
        MultilineTextMixedFontsClean(Consumer.MultilineText, Shape.MixedFonts, ControlOperation.Clean),
        TextAreaShortCaret(Consumer.TextArea, Shape.Short, ControlOperation.Caret),
        TextAreaBmp32767Caret(Consumer.TextArea, Shape.Bmp32767, ControlOperation.Caret),
        TextAreaWrappedCaret(Consumer.TextArea, Shape.Wrapped, ControlOperation.Caret),
        TextAreaSignedCaret(Consumer.TextArea, Shape.Signed, ControlOperation.Caret),
        TextAreaMixedFontsCompositionBefore(Consumer.TextArea, Shape.MixedFonts, ControlOperation.Composition, CompositionRegion.Before),
        TextAreaMixedFontsCompositionInside(Consumer.TextArea, Shape.MixedFonts, ControlOperation.Composition, CompositionRegion.Inside),
        TextAreaMixedFontsCompositionAfter(Consumer.TextArea, Shape.MixedFonts, ControlOperation.Composition, CompositionRegion.After),
        MultilineTextEllipsisFirstClean(Consumer.MultilineText, Shape.EllipsisFirst, ControlOperation.Clean),
        MultilineTextEllipsisMiddleClean(Consumer.MultilineText, Shape.EllipsisMiddle, ControlOperation.Clean),
        MultilineTextEllipsisLastClean(Consumer.MultilineText, Shape.EllipsisLast, ControlOperation.Clean),
    }

    /**
     * Actual public component whose current layout owns the private line arrays.
     */
    internal enum class Consumer {
        TextArea,
        MultilineText,
    }

    /**
     * Prepared scalar/metric families; supplementary long input uses sufficient explicit UTF-16 state capacity.
     * DisplayOrder uses a deterministic custom backend ordering probe and does not claim native bidi acceptance.
     */
    internal enum class Shape {
        Empty,
        Short,
        Bmp128,
        Bmp32767,
        Supplementary32767Scalars,
        Wrapped,
        HardBreaks,
        Signed,
        ZeroAdvance,
        MixedFonts,
        ExceptionalMetrics,
        DisplayOrder,
        EllipsisFirst,
        EllipsisMiddle,
        EllipsisLast,
    }

    /**
     * Three distinct real construction opportunities with identical ownership and prepared inputs.
     */
    internal enum class LayoutOperation {
        Initial,
        Edit,
        Reflow,
    }

    /**
     * Clean frames retain the current arrays; caret and composition cycles restore their public starting opportunity.
     */
    internal enum class ControlOperation {
        Clean,
        Caret,
        Composition,
    }

    /**
     * Caret/focused-block regions within the fixed supplementary composition.
     */
    internal enum class CompositionRegion {
        Before,
        Inside,
        After,
    }

    /**
     * One real retained construction owner, verified before standard JMH trial sampling.
     */
    @State(Scope.Thread)
    public open class LayoutScene {
        /**
         * Exact generated construction case.
         */
        @JvmField
        @Param
        public var workload: LayoutCase = LayoutCase.TextAreaEmptyInitial

        private lateinit var fixture: TextLineLayoutFixture

        /**
         * Prepares fonts/values and independently checks the loaded line metrics and replacement lifetime.
         */
        @Setup(Level.Trial)
        public fun setUp() {
            fixture = TextLineLayoutFixture(workload.consumer, workload.shape)
            fixture.verifyLayout(workload.operation)
        }

        /**
         * Includes the public opportunity and settled frame without reflective access or oracle copying.
         */
        public fun operation(): RuntimeUiFrame = fixture.layout(workload.operation)

        /**
         * Releases the current host and requires zero face/backend ownership.
         */
        @TearDown(Level.Trial)
        public fun close(): Unit = fixture.close()
    }

    /**
     * One primed control owner with fixed public input events.
     */
    @State(Scope.Thread)
    public open class ControlScene {
        /**
         * Exact generated control case.
         */
        @JvmField
        @Param
        public var workload: ControlCase = ControlCase.TextAreaEmptyClean

        private lateinit var fixture: TextLineLayoutFixture

        /**
         * Verifies current layout reuse, restored pixels, scalar placement and composition lifetime outside timing.
         */
        @Setup(Level.Trial)
        public fun setUp() {
            fixture = TextLineLayoutFixture(workload.consumer, workload.shape, workload.region)
            fixture.verifyControl(workload.operation)
        }

        /**
         * Includes each complete public control cycle with the same CPU/allocation boundary.
         */
        public fun operation(): RuntimeUiFrame = fixture.control(workload.operation)

        /**
         * Releases the current font owner after all measured use.
         */
        @TearDown(Level.Trial)
        public fun close(): Unit = fixture.close()
    }

    /**
     * Generic fixture verification without a corpus-specific Gradle task or launcher branch.
     */
    public companion object {
        /**
         * Requires all 82 generated cases, real scalar/pixel/lifetime oracles and complete release before collection.
         */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(TextLineLayoutBenchmark::class.java), setOf("avgt")).size == 82)
            for (workload in LayoutCase.entries) {
                TextLineLayoutFixture(workload.consumer, workload.shape).use { fixture -> fixture.verifyLayout(workload.operation) }
            }
            for (workload in ControlCase.entries) {
                TextLineLayoutFixture(workload.consumer, workload.shape, workload.region).use { fixture -> fixture.verifyControl(workload.operation) }
            }
        }
    }
}
