package dev.s7a.strata.quality.benchmark

/**
 * Frozen whole-Issue single-line preedit corpus: 24 main fixtures with two operations and 30 independent controls.
 * Each enum entry is exactly one completed-host-operation row; no invalid Cartesian combinations enter JMH.
 *
 * @property length main composition's UTF-16 length, or zero for a control.
 * @property partition immutable producer block arrangement.
 * @property focus which distinct nonempty block is selected.
 * @property operation real input or dirty-paint boundary.
 * @property control independent transition when this is a control row.
 */
public enum class TextFieldPreeditRow(
    public val length: Int = 0,
    public val partition: Partition = Partition.TwoHalves,
    public val focus: Focus = Focus.FirstNonempty,
    public val operation: Operation = Operation.Control,
    public val control: Control? = null,
) {
    Ascii32TwoHalvesFirstNonemptyInputAndCompletedFrame(32, Partition.TwoHalves, Focus.FirstNonempty, Operation.InputAndCompletedFrame),
    Ascii32TwoHalvesFirstNonemptyUnchangedPreeditDirtyFieldFrame(32, Partition.TwoHalves, Focus.FirstNonempty, Operation.UnchangedPreeditDirtyFieldFrame),
    Ascii32TwoHalvesLastNonemptyInputAndCompletedFrame(32, Partition.TwoHalves, Focus.LastNonempty, Operation.InputAndCompletedFrame),
    Ascii32TwoHalvesLastNonemptyUnchangedPreeditDirtyFieldFrame(32, Partition.TwoHalves, Focus.LastNonempty, Operation.UnchangedPreeditDirtyFieldFrame),
    Ascii32Fixed8BlocksFirstNonemptyInputAndCompletedFrame(32, Partition.Fixed8Blocks, Focus.FirstNonempty, Operation.InputAndCompletedFrame),
    Ascii32Fixed8BlocksFirstNonemptyUnchangedPreeditDirtyFieldFrame(32, Partition.Fixed8Blocks, Focus.FirstNonempty, Operation.UnchangedPreeditDirtyFieldFrame),
    Ascii32Fixed8BlocksLastNonemptyInputAndCompletedFrame(32, Partition.Fixed8Blocks, Focus.LastNonempty, Operation.InputAndCompletedFrame),
    Ascii32Fixed8BlocksLastNonemptyUnchangedPreeditDirtyFieldFrame(32, Partition.Fixed8Blocks, Focus.LastNonempty, Operation.UnchangedPreeditDirtyFieldFrame),
    Ascii32OneScalarPerBlockFirstNonemptyInputAndCompletedFrame(32, Partition.OneScalarPerBlock, Focus.FirstNonempty, Operation.InputAndCompletedFrame),
    Ascii32OneScalarPerBlockFirstNonemptyUnchangedPreeditDirtyFieldFrame(32, Partition.OneScalarPerBlock, Focus.FirstNonempty, Operation.UnchangedPreeditDirtyFieldFrame),
    Ascii32OneScalarPerBlockLastNonemptyInputAndCompletedFrame(32, Partition.OneScalarPerBlock, Focus.LastNonempty, Operation.InputAndCompletedFrame),
    Ascii32OneScalarPerBlockLastNonemptyUnchangedPreeditDirtyFieldFrame(32, Partition.OneScalarPerBlock, Focus.LastNonempty, Operation.UnchangedPreeditDirtyFieldFrame),
    Ascii32EmptyBoundaryBlocksFirstNonemptyInputAndCompletedFrame(32, Partition.EmptyBoundaryBlocks, Focus.FirstNonempty, Operation.InputAndCompletedFrame),
    Ascii32EmptyBoundaryBlocksFirstNonemptyUnchangedPreeditDirtyFieldFrame(32, Partition.EmptyBoundaryBlocks, Focus.FirstNonempty, Operation.UnchangedPreeditDirtyFieldFrame),
    Ascii32EmptyBoundaryBlocksLastNonemptyInputAndCompletedFrame(32, Partition.EmptyBoundaryBlocks, Focus.LastNonempty, Operation.InputAndCompletedFrame),
    Ascii32EmptyBoundaryBlocksLastNonemptyUnchangedPreeditDirtyFieldFrame(32, Partition.EmptyBoundaryBlocks, Focus.LastNonempty, Operation.UnchangedPreeditDirtyFieldFrame),
    Ascii4096TwoHalvesFirstNonemptyInputAndCompletedFrame(4096, Partition.TwoHalves, Focus.FirstNonempty, Operation.InputAndCompletedFrame),
    Ascii4096TwoHalvesFirstNonemptyUnchangedPreeditDirtyFieldFrame(4096, Partition.TwoHalves, Focus.FirstNonempty, Operation.UnchangedPreeditDirtyFieldFrame),
    Ascii4096TwoHalvesLastNonemptyInputAndCompletedFrame(4096, Partition.TwoHalves, Focus.LastNonempty, Operation.InputAndCompletedFrame),
    Ascii4096TwoHalvesLastNonemptyUnchangedPreeditDirtyFieldFrame(4096, Partition.TwoHalves, Focus.LastNonempty, Operation.UnchangedPreeditDirtyFieldFrame),
    Ascii4096Fixed8BlocksFirstNonemptyInputAndCompletedFrame(4096, Partition.Fixed8Blocks, Focus.FirstNonempty, Operation.InputAndCompletedFrame),
    Ascii4096Fixed8BlocksFirstNonemptyUnchangedPreeditDirtyFieldFrame(4096, Partition.Fixed8Blocks, Focus.FirstNonempty, Operation.UnchangedPreeditDirtyFieldFrame),
    Ascii4096Fixed8BlocksLastNonemptyInputAndCompletedFrame(4096, Partition.Fixed8Blocks, Focus.LastNonempty, Operation.InputAndCompletedFrame),
    Ascii4096Fixed8BlocksLastNonemptyUnchangedPreeditDirtyFieldFrame(4096, Partition.Fixed8Blocks, Focus.LastNonempty, Operation.UnchangedPreeditDirtyFieldFrame),
    Ascii4096OneScalarPerBlockFirstNonemptyInputAndCompletedFrame(4096, Partition.OneScalarPerBlock, Focus.FirstNonempty, Operation.InputAndCompletedFrame),
    Ascii4096OneScalarPerBlockFirstNonemptyUnchangedPreeditDirtyFieldFrame(4096, Partition.OneScalarPerBlock, Focus.FirstNonempty, Operation.UnchangedPreeditDirtyFieldFrame),
    Ascii4096OneScalarPerBlockLastNonemptyInputAndCompletedFrame(4096, Partition.OneScalarPerBlock, Focus.LastNonempty, Operation.InputAndCompletedFrame),
    Ascii4096OneScalarPerBlockLastNonemptyUnchangedPreeditDirtyFieldFrame(4096, Partition.OneScalarPerBlock, Focus.LastNonempty, Operation.UnchangedPreeditDirtyFieldFrame),
    Ascii4096EmptyBoundaryBlocksFirstNonemptyInputAndCompletedFrame(4096, Partition.EmptyBoundaryBlocks, Focus.FirstNonempty, Operation.InputAndCompletedFrame),
    Ascii4096EmptyBoundaryBlocksFirstNonemptyUnchangedPreeditDirtyFieldFrame(4096, Partition.EmptyBoundaryBlocks, Focus.FirstNonempty, Operation.UnchangedPreeditDirtyFieldFrame),
    Ascii4096EmptyBoundaryBlocksLastNonemptyInputAndCompletedFrame(4096, Partition.EmptyBoundaryBlocks, Focus.LastNonempty, Operation.InputAndCompletedFrame),
    Ascii4096EmptyBoundaryBlocksLastNonemptyUnchangedPreeditDirtyFieldFrame(4096, Partition.EmptyBoundaryBlocks, Focus.LastNonempty, Operation.UnchangedPreeditDirtyFieldFrame),
    Ascii16384TwoHalvesFirstNonemptyInputAndCompletedFrame(16384, Partition.TwoHalves, Focus.FirstNonempty, Operation.InputAndCompletedFrame),
    Ascii16384TwoHalvesFirstNonemptyUnchangedPreeditDirtyFieldFrame(16384, Partition.TwoHalves, Focus.FirstNonempty, Operation.UnchangedPreeditDirtyFieldFrame),
    Ascii16384TwoHalvesLastNonemptyInputAndCompletedFrame(16384, Partition.TwoHalves, Focus.LastNonempty, Operation.InputAndCompletedFrame),
    Ascii16384TwoHalvesLastNonemptyUnchangedPreeditDirtyFieldFrame(16384, Partition.TwoHalves, Focus.LastNonempty, Operation.UnchangedPreeditDirtyFieldFrame),
    Ascii16384Fixed8BlocksFirstNonemptyInputAndCompletedFrame(16384, Partition.Fixed8Blocks, Focus.FirstNonempty, Operation.InputAndCompletedFrame),
    Ascii16384Fixed8BlocksFirstNonemptyUnchangedPreeditDirtyFieldFrame(16384, Partition.Fixed8Blocks, Focus.FirstNonempty, Operation.UnchangedPreeditDirtyFieldFrame),
    Ascii16384Fixed8BlocksLastNonemptyInputAndCompletedFrame(16384, Partition.Fixed8Blocks, Focus.LastNonempty, Operation.InputAndCompletedFrame),
    Ascii16384Fixed8BlocksLastNonemptyUnchangedPreeditDirtyFieldFrame(16384, Partition.Fixed8Blocks, Focus.LastNonempty, Operation.UnchangedPreeditDirtyFieldFrame),
    Ascii16384OneScalarPerBlockFirstNonemptyInputAndCompletedFrame(16384, Partition.OneScalarPerBlock, Focus.FirstNonempty, Operation.InputAndCompletedFrame),
    Ascii16384OneScalarPerBlockFirstNonemptyUnchangedPreeditDirtyFieldFrame(16384, Partition.OneScalarPerBlock, Focus.FirstNonempty, Operation.UnchangedPreeditDirtyFieldFrame),
    Ascii16384OneScalarPerBlockLastNonemptyInputAndCompletedFrame(16384, Partition.OneScalarPerBlock, Focus.LastNonempty, Operation.InputAndCompletedFrame),
    Ascii16384OneScalarPerBlockLastNonemptyUnchangedPreeditDirtyFieldFrame(16384, Partition.OneScalarPerBlock, Focus.LastNonempty, Operation.UnchangedPreeditDirtyFieldFrame),
    Ascii16384EmptyBoundaryBlocksFirstNonemptyInputAndCompletedFrame(16384, Partition.EmptyBoundaryBlocks, Focus.FirstNonempty, Operation.InputAndCompletedFrame),
    Ascii16384EmptyBoundaryBlocksFirstNonemptyUnchangedPreeditDirtyFieldFrame(16384, Partition.EmptyBoundaryBlocks, Focus.FirstNonempty, Operation.UnchangedPreeditDirtyFieldFrame),
    Ascii16384EmptyBoundaryBlocksLastNonemptyInputAndCompletedFrame(16384, Partition.EmptyBoundaryBlocks, Focus.LastNonempty, Operation.InputAndCompletedFrame),
    Ascii16384EmptyBoundaryBlocksLastNonemptyUnchangedPreeditDirtyFieldFrame(16384, Partition.EmptyBoundaryBlocks, Focus.LastNonempty, Operation.UnchangedPreeditDirtyFieldFrame),
    NoCompositionDirtyField(control = Control.NoCompositionDirtyField),
    CleanActiveComposition(control = Control.CleanActiveComposition),
    EmptyClear(control = Control.EmptyClear),
    NoFocusedBlock(control = Control.NoFocusedBlock),
    MismatchedTotalLength(control = Control.MismatchedTotalLength),
    MismatchedSameLength(control = Control.MismatchedSameLength),
    FocusedEmptyInterior(control = Control.FocusedEmptyInterior),
    FocusedEmptyLeading(control = Control.FocusedEmptyLeading),
    FocusedEmptyTrailing(control = Control.FocusedEmptyTrailing),
    ValidSupplementaryBlocks(control = Control.ValidSupplementaryBlocks),
    SplitSupplementaryCaret(control = Control.SplitSupplementaryCaret),
    IsolatedSurrogateFull(control = Control.IsolatedSurrogateFull),
    IsolatedSurrogateBlock(control = Control.IsolatedSurrogateBlock),
    RejectedFullControl(control = Control.RejectedFullControl),
    RejectedBlockControl(control = Control.RejectedBlockControl),
    PreeditBeyondRemainingCapacity(control = Control.PreeditBeyondRemainingCapacity),
    IdenticalPreeditNoOp(control = Control.IdenticalPreeditNoOp),
    CaretOnlyChange(control = Control.CaretOnlyChange),
    FocusedIndexOnlyChange(control = Control.FocusedIndexOnlyChange),
    BlocksOnlyChange(control = Control.BlocksOnlyChange),
    AppearanceOnlyPaint(control = Control.AppearanceOnlyPaint),
    WidthAndFontReplacement(control = Control.WidthAndFontReplacement),
    NegativeAdvanceClippedUnderline(control = Control.NegativeAdvanceClippedUnderline),
    FocusLoss(control = Control.FocusLoss),
    DisabledUpdate(control = Control.DisabledUpdate),
    StateReplacement(control = Control.StateReplacement),
    ExternalStateMutation(control = Control.ExternalStateMutation),
    CommittedInputAndCursorClear(control = Control.CommittedInputAndCursorClear),
    DetachReattach(control = Control.DetachReattach),
    DisposeCloseRelease(control = Control.DisposeCloseRelease);

    /**
     * Stable fixture identity shared by both main operation modes.
     */
    public val fixtureId: String get() = control?.name ?: "Ascii${length}${partition.name}${focus.name}"

    /**
     * Producer partitions all accepted main text without retaining mutable native input.
     */
    public enum class Partition {
        TwoHalves,
        Fixed8Blocks,
        OneScalarPerBlock,
        EmptyBoundaryBlocks,
    }

    /**
     * Both choices select different nonempty blocks, including TwoHalves.
     */
    public enum class Focus {
        FirstNonempty,
        LastNonempty,
    }

    /**
     * Boundaries include source publication or focused input and the completed production frame.
     */
    public enum class Operation {
        InputAndCompletedFrame,
        UnchangedPreeditDirtyFieldFrame,
        Control,
    }

    /**
     * Every independent Issue control has one measured completed-operation row.
     */
    public enum class Control {
        NoCompositionDirtyField,
        CleanActiveComposition,
        EmptyClear,
        NoFocusedBlock,
        MismatchedTotalLength,
        MismatchedSameLength,
        FocusedEmptyInterior,
        FocusedEmptyLeading,
        FocusedEmptyTrailing,
        ValidSupplementaryBlocks,
        SplitSupplementaryCaret,
        IsolatedSurrogateFull,
        IsolatedSurrogateBlock,
        RejectedFullControl,
        RejectedBlockControl,
        PreeditBeyondRemainingCapacity,
        IdenticalPreeditNoOp,
        CaretOnlyChange,
        FocusedIndexOnlyChange,
        BlocksOnlyChange,
        AppearanceOnlyPaint,
        WidthAndFontReplacement,
        NegativeAdvanceClippedUnderline,
        FocusLoss,
        DisabledUpdate,
        StateReplacement,
        ExternalStateMutation,
        CommittedInputAndCursorClear,
        DetachReattach,
        DisposeCloseRelease,
    }
}
