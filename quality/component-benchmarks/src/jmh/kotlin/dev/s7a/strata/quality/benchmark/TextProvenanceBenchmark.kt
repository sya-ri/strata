package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhWorkloadInventory
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/**
 * Actual complete content, lookup, slice, description, layout and retained public-consumer opportunities.
 * Prepared Unicode structures, fonts, traces and handle bindings remain outside sampled calls.
 * Every declared case is retained in both frozen runtime variants, including empty, short, dense and clean controls.
 */
public open class TextProvenanceBenchmark {
    /**
     * Runs the complete declared operation, returning its actual detached result or folded provenance query.
     */
    @Benchmark
    public fun operate(state: Session): Any = state.perform()

    /**
     * One primed owner-thread fixture with exact prepared input and complete operation restoration.
     */
    @State(Scope.Thread)
    public open class Session {
        /**
         * Compiled full workload matrix, without runtime string dispatch or speculative parameter combinations.
         */
        @JvmField
        @Param
        public var case: Case = Case.ContentEmpty

        private lateinit var fixture: TextProvenanceFixture

        /**
         * Prepares actual profile resources, direct method bindings, traces and public retained ownership.
         */
        @Setup(Level.Trial)
        public fun setup() {
            fixture = TextProvenanceFixture(case)
        }

        /**
         * Includes all target work for this case and restores retained changes before returning.
         */
        public fun perform(): Any = fixture.perform()

        /**
         * Releases both independently owned renderers after proving terminal current-state release.
         */
        @TearDown(Level.Trial)
        public fun close() {
            fixture.close()
        }
    }

    /**
     * Original logical text and font-composition patterns, including independent numeric/shaping controls.
     */
    public enum class Shape {
        Empty,
        Short,
        Single128,
        Single16384,
        Sparse128,
        Sparse16384,
        Dense128,
        Dense16384,
        EqualAdjacent,
        NestedEmpty,
        Supplementary,
        MixedBreaks,
        Wrapped,
        DisplayIndices,
        Signed,
        Zero,
        Exceptional,
    }

    /**
     * Actual public retained consumer or corresponding complete direct-layout contract.
     * TextArea admits one inherited editor font, so mixed per-scalar styling applies only to display Text.
     */
    public enum class Consumer {
        MultilineText,
        SingleLineText,
        TextArea,
    }

    /**
     * Separate original content work and public-owner phases; comparison controls preserve full semantic equality.
     */
    public enum class Operation {
        Content,
        Lookup,
        Slice,
        Equivalent,
        StructureDifferent,
        InheritedDifferent,
        FontDifferent,
        Description,
        Layout,
        Initial,
        Edit,
        Reflow,
        FontChange,
        Preedit,
        Clean,
        Redeclaration,
    }

    /**
     * Reviewed full source matrix; constructor fields are typed fixture inputs, never screen or domain discriminators.
     */
    public enum class Case(
        internal val shape: Shape,
        internal val operation: Operation,
        internal val consumer: Consumer = Consumer.MultilineText,
    ) {
        ContentEmpty(Shape.Empty, Operation.Content),
        ContentShort(Shape.Short, Operation.Content),
        ContentSingle128(Shape.Single128, Operation.Content),
        ContentSingle16384(Shape.Single16384, Operation.Content),
        ContentSparse128(Shape.Sparse128, Operation.Content),
        ContentSparse16384(Shape.Sparse16384, Operation.Content),
        ContentDense128(Shape.Dense128, Operation.Content),
        ContentDense16384(Shape.Dense16384, Operation.Content),
        ContentEqualAdjacent(Shape.EqualAdjacent, Operation.Content),
        ContentNestedEmpty(Shape.NestedEmpty, Operation.Content),
        ContentSupplementary(Shape.Supplementary, Operation.Content),
        ContentMixedBreaks(Shape.MixedBreaks, Operation.Content),
        ContentWrapped(Shape.Wrapped, Operation.Content),
        ContentDisplayIndices(Shape.DisplayIndices, Operation.Content),
        ContentSigned(Shape.Signed, Operation.Content),
        ContentZero(Shape.Zero, Operation.Content),
        ContentExceptional(Shape.Exceptional, Operation.Content),
        LookupShort(Shape.Short, Operation.Lookup),
        LookupSingle128(Shape.Single128, Operation.Lookup),
        LookupSingle16384(Shape.Single16384, Operation.Lookup),
        LookupSparse128(Shape.Sparse128, Operation.Lookup),
        LookupSparse16384(Shape.Sparse16384, Operation.Lookup),
        LookupDense128(Shape.Dense128, Operation.Lookup),
        LookupDense16384(Shape.Dense16384, Operation.Lookup),
        LookupSupplementary(Shape.Supplementary, Operation.Lookup),
        LookupDisplayIndices(Shape.DisplayIndices, Operation.Lookup),
        SliceEmpty(Shape.Empty, Operation.Slice),
        SliceShort(Shape.Short, Operation.Slice),
        SliceSingle128(Shape.Single128, Operation.Slice),
        SliceSingle16384(Shape.Single16384, Operation.Slice),
        SliceSparse16384(Shape.Sparse16384, Operation.Slice),
        SliceDense16384(Shape.Dense16384, Operation.Slice),
        SliceEqualAdjacent(Shape.EqualAdjacent, Operation.Slice),
        SliceNestedEmpty(Shape.NestedEmpty, Operation.Slice),
        SliceSupplementary(Shape.Supplementary, Operation.Slice),
        SliceMixedBreaks(Shape.MixedBreaks, Operation.Slice),
        SliceWrapped(Shape.Wrapped, Operation.Slice),
        SliceDisplayIndices(Shape.DisplayIndices, Operation.Slice),
        EquivalentEmpty(Shape.Empty, Operation.Equivalent),
        EquivalentShort(Shape.Short, Operation.Equivalent),
        EquivalentSingle16384(Shape.Single16384, Operation.Equivalent),
        EquivalentSparse16384(Shape.Sparse16384, Operation.Equivalent),
        EquivalentDense16384(Shape.Dense16384, Operation.Equivalent),
        StructureDifferentEmpty(Shape.Empty, Operation.StructureDifferent),
        StructureDifferentShort(Shape.Short, Operation.StructureDifferent),
        StructureDifferentSingle16384(Shape.Single16384, Operation.StructureDifferent),
        StructureDifferentSparse16384(Shape.Sparse16384, Operation.StructureDifferent),
        StructureDifferentDense16384(Shape.Dense16384, Operation.StructureDifferent),
        InheritedDifferentEmpty(Shape.Empty, Operation.InheritedDifferent),
        InheritedDifferentShort(Shape.Short, Operation.InheritedDifferent),
        InheritedDifferentSingle16384(Shape.Single16384, Operation.InheritedDifferent),
        InheritedDifferentSparse16384(Shape.Sparse16384, Operation.InheritedDifferent),
        InheritedDifferentDense16384(Shape.Dense16384, Operation.InheritedDifferent),
        FontDifferentEmpty(Shape.Empty, Operation.FontDifferent),
        FontDifferentShort(Shape.Short, Operation.FontDifferent),
        FontDifferentSingle16384(Shape.Single16384, Operation.FontDifferent),
        FontDifferentSparse16384(Shape.Sparse16384, Operation.FontDifferent),
        FontDifferentDense16384(Shape.Dense16384, Operation.FontDifferent),
        DescriptionMultilineTextEmpty(Shape.Empty, Operation.Description, Consumer.MultilineText),
        DescriptionMultilineTextShort(Shape.Short, Operation.Description, Consumer.MultilineText),
        DescriptionMultilineTextSingle128(Shape.Single128, Operation.Description, Consumer.MultilineText),
        DescriptionMultilineTextSingle16384(Shape.Single16384, Operation.Description, Consumer.MultilineText),
        DescriptionMultilineTextSparse128(Shape.Sparse128, Operation.Description, Consumer.MultilineText),
        DescriptionMultilineTextSparse16384(Shape.Sparse16384, Operation.Description, Consumer.MultilineText),
        DescriptionMultilineTextDense128(Shape.Dense128, Operation.Description, Consumer.MultilineText),
        DescriptionMultilineTextDense16384(Shape.Dense16384, Operation.Description, Consumer.MultilineText),
        DescriptionSingleLineTextEmpty(Shape.Empty, Operation.Description, Consumer.SingleLineText),
        DescriptionSingleLineTextShort(Shape.Short, Operation.Description, Consumer.SingleLineText),
        DescriptionSingleLineTextSingle128(Shape.Single128, Operation.Description, Consumer.SingleLineText),
        DescriptionSingleLineTextSingle16384(Shape.Single16384, Operation.Description, Consumer.SingleLineText),
        DescriptionSingleLineTextSparse128(Shape.Sparse128, Operation.Description, Consumer.SingleLineText),
        DescriptionSingleLineTextSparse16384(Shape.Sparse16384, Operation.Description, Consumer.SingleLineText),
        DescriptionSingleLineTextDense128(Shape.Dense128, Operation.Description, Consumer.SingleLineText),
        DescriptionSingleLineTextDense16384(Shape.Dense16384, Operation.Description, Consumer.SingleLineText),
        DescriptionTextAreaEmpty(Shape.Empty, Operation.Description, Consumer.TextArea),
        DescriptionTextAreaShort(Shape.Short, Operation.Description, Consumer.TextArea),
        DescriptionTextAreaSingle16384(Shape.Single16384, Operation.Description, Consumer.TextArea),
        LayoutEmpty(Shape.Empty, Operation.Layout),
        LayoutShort(Shape.Short, Operation.Layout),
        LayoutSingle128(Shape.Single128, Operation.Layout),
        LayoutSingle16384(Shape.Single16384, Operation.Layout),
        LayoutSparse128(Shape.Sparse128, Operation.Layout),
        LayoutSparse16384(Shape.Sparse16384, Operation.Layout),
        LayoutDense128(Shape.Dense128, Operation.Layout),
        LayoutDense16384(Shape.Dense16384, Operation.Layout),
        LayoutEqualAdjacent(Shape.EqualAdjacent, Operation.Layout),
        LayoutNestedEmpty(Shape.NestedEmpty, Operation.Layout),
        LayoutSupplementary(Shape.Supplementary, Operation.Layout),
        LayoutMixedBreaks(Shape.MixedBreaks, Operation.Layout),
        LayoutWrapped(Shape.Wrapped, Operation.Layout),
        LayoutDisplayIndices(Shape.DisplayIndices, Operation.Layout),
        LayoutSigned(Shape.Signed, Operation.Layout),
        LayoutZero(Shape.Zero, Operation.Layout),
        LayoutExceptional(Shape.Exceptional, Operation.Layout),
        LayoutSingleLineTextSingle16384(Shape.Single16384, Operation.Layout, Consumer.SingleLineText),
        LayoutSingleLineTextSparse16384(Shape.Sparse16384, Operation.Layout, Consumer.SingleLineText),
        LayoutSingleLineTextDense16384(Shape.Dense16384, Operation.Layout, Consumer.SingleLineText),
        LayoutTextAreaEmpty(Shape.Empty, Operation.Layout, Consumer.TextArea),
        LayoutTextAreaSingle16384(Shape.Single16384, Operation.Layout, Consumer.TextArea),
        LayoutTextAreaMixedBreaks(Shape.MixedBreaks, Operation.Layout, Consumer.TextArea),
        InitialMultilineTextSingle16384(Shape.Single16384, Operation.Initial, Consumer.MultilineText),
        InitialMultilineTextSparse16384(Shape.Sparse16384, Operation.Initial, Consumer.MultilineText),
        InitialMultilineTextDense16384(Shape.Dense16384, Operation.Initial, Consumer.MultilineText),
        InitialSingleLineTextSingle16384(Shape.Single16384, Operation.Initial, Consumer.SingleLineText),
        InitialSingleLineTextSparse16384(Shape.Sparse16384, Operation.Initial, Consumer.SingleLineText),
        InitialSingleLineTextDense16384(Shape.Dense16384, Operation.Initial, Consumer.SingleLineText),
        InitialTextAreaSingle16384(Shape.Single16384, Operation.Initial, Consumer.TextArea),
        EditMultilineTextSingle16384(Shape.Single16384, Operation.Edit, Consumer.MultilineText),
        EditMultilineTextSparse16384(Shape.Sparse16384, Operation.Edit, Consumer.MultilineText),
        EditMultilineTextDense16384(Shape.Dense16384, Operation.Edit, Consumer.MultilineText),
        EditSingleLineTextSingle16384(Shape.Single16384, Operation.Edit, Consumer.SingleLineText),
        EditSingleLineTextSparse16384(Shape.Sparse16384, Operation.Edit, Consumer.SingleLineText),
        EditSingleLineTextDense16384(Shape.Dense16384, Operation.Edit, Consumer.SingleLineText),
        EditTextAreaSingle16384(Shape.Single16384, Operation.Edit, Consumer.TextArea),
        ReflowSingle16384(Shape.Single16384, Operation.Reflow),
        ReflowSparse16384(Shape.Sparse16384, Operation.Reflow),
        ReflowDense16384(Shape.Dense16384, Operation.Reflow),
        ReflowTextAreaSingle16384(Shape.Single16384, Operation.Reflow, Consumer.TextArea),
        FontChangeMultilineText(Shape.Single16384, Operation.FontChange, Consumer.MultilineText),
        FontChangeSingleLineText(Shape.Single16384, Operation.FontChange, Consumer.SingleLineText),
        FontChangeTextArea(Shape.Single16384, Operation.FontChange, Consumer.TextArea),
        PreeditTextAreaSingle16384(Shape.Single16384, Operation.Preedit, Consumer.TextArea),
        PreeditTextAreaMixedBreaks(Shape.MixedBreaks, Operation.Preedit, Consumer.TextArea),
        CleanMultilineTextEmpty(Shape.Empty, Operation.Clean, Consumer.MultilineText),
        CleanMultilineTextShort(Shape.Short, Operation.Clean, Consumer.MultilineText),
        CleanMultilineTextSingle128(Shape.Single128, Operation.Clean, Consumer.MultilineText),
        CleanMultilineTextSingle16384(Shape.Single16384, Operation.Clean, Consumer.MultilineText),
        CleanMultilineTextSparse128(Shape.Sparse128, Operation.Clean, Consumer.MultilineText),
        CleanMultilineTextSparse16384(Shape.Sparse16384, Operation.Clean, Consumer.MultilineText),
        CleanMultilineTextDense128(Shape.Dense128, Operation.Clean, Consumer.MultilineText),
        CleanMultilineTextDense16384(Shape.Dense16384, Operation.Clean, Consumer.MultilineText),
        CleanSingleLineTextEmpty(Shape.Empty, Operation.Clean, Consumer.SingleLineText),
        CleanSingleLineTextShort(Shape.Short, Operation.Clean, Consumer.SingleLineText),
        CleanSingleLineTextSingle128(Shape.Single128, Operation.Clean, Consumer.SingleLineText),
        CleanSingleLineTextSingle16384(Shape.Single16384, Operation.Clean, Consumer.SingleLineText),
        CleanSingleLineTextSparse128(Shape.Sparse128, Operation.Clean, Consumer.SingleLineText),
        CleanSingleLineTextSparse16384(Shape.Sparse16384, Operation.Clean, Consumer.SingleLineText),
        CleanSingleLineTextDense128(Shape.Dense128, Operation.Clean, Consumer.SingleLineText),
        CleanSingleLineTextDense16384(Shape.Dense16384, Operation.Clean, Consumer.SingleLineText),
        CleanTextAreaEmpty(Shape.Empty, Operation.Clean, Consumer.TextArea),
        CleanTextAreaShort(Shape.Short, Operation.Clean, Consumer.TextArea),
        CleanTextAreaSingle16384(Shape.Single16384, Operation.Clean, Consumer.TextArea),
        RedeclarationMultilineTextSingle16384(Shape.Single16384, Operation.Redeclaration, Consumer.MultilineText),
        RedeclarationMultilineTextSparse16384(Shape.Sparse16384, Operation.Redeclaration, Consumer.MultilineText),
        RedeclarationMultilineTextDense16384(Shape.Dense16384, Operation.Redeclaration, Consumer.MultilineText),
        RedeclarationSingleLineTextSingle16384(Shape.Single16384, Operation.Redeclaration, Consumer.SingleLineText),
        RedeclarationSingleLineTextSparse16384(Shape.Sparse16384, Operation.Redeclaration, Consumer.SingleLineText),
        RedeclarationSingleLineTextDense16384(Shape.Dense16384, Operation.Redeclaration, Consumer.SingleLineText),
        RedeclarationTextAreaSingle16384(Shape.Single16384, Operation.Redeclaration, Consumer.TextArea),
    }

    /**
     * Discovers the actual generated matrix and runs independent provenance, pixels, work and release acceptance.
     */
    public companion object {
        /**
         * Requires the generated full matrix and every independent contract check before collection.
         */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(TextProvenanceBenchmark::class.java), setOf("avgt")).size == Case.entries.size)
            TextProvenanceFailures.verify()
            for (case in Case.entries) {
                TextProvenanceFixture(case).use { fixture -> fixture.verify() }
            }
        }
    }
}
