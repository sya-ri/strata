package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.input.TextInputEvent

/**
 * Frozen whole-Issue corpus: 41 normalize/editor cases and four complete-frame controls.
 * Length is raw UTF-16 length; all events, blocks and 64-delivery traces are prepared before collection.
 */
public enum class PreeditCase(
    private val units: Int,
    private val kind: Kind,
) {
    Ascii32(32, Kind.Ascii),
    Ascii4096(4096, Kind.Ascii),
    Ascii16384(16384, Kind.Ascii),
    Cjk32(32, Kind.Cjk),
    Cjk4096(4096, Kind.Cjk),
    Cjk16384(16384, Kind.Cjk),
    Supplementary32(32, Kind.Supplementary),
    Supplementary4096(4096, Kind.Supplementary),
    Supplementary16384(16384, Kind.Supplementary),
    Lf32(32, Kind.Lf),
    Lf4096(4096, Kind.Lf),
    Lf16384(16384, Kind.Lf),
    Caret32(32, Kind.Caret),
    Caret4096(4096, Kind.Caret),
    Caret16384(16384, Kind.Caret),
    FocusedBlock32(32, Kind.FocusedBlock),
    FocusedBlock4096(4096, Kind.FocusedBlock),
    FocusedBlock16384(16384, Kind.FocusedBlock),
    Replacement32(32, Kind.Replacement),
    Replacement4096(4096, Kind.Replacement),
    Replacement16384(16384, Kind.Replacement),
    Cr32(32, Kind.Cr),
    Cr4096(4096, Kind.Cr),
    Crlf32(32, Kind.Crlf),
    Crlf4096(4096, Kind.Crlf),
    MixedBreaks32(32, Kind.MixedBreaks),
    MixedBreaks4096(4096, Kind.MixedBreaks),
    LateConversion32(32, Kind.LateConversion),
    LateConversion4096(4096, Kind.LateConversion),
    EmptyClear(0, Kind.Control),
    ExactRemainingBudget(2, Kind.Control),
    OneUnitOverBudget(2, Kind.Control),
    RawTextOverTwiceBudget(3, Kind.Control),
    BlockTotalOverTwiceBudget(1, Kind.Control),
    BlockCountOverBudget(1, Kind.Control),
    SupplementarySplitCaret(2, Kind.Control),
    SplitSurrogateBlocks(2, Kind.Control),
    UnsupportedScalar(2, Kind.Control),
    BlockMismatch(1, Kind.Control),
    EmptyBoundaryBlocks(1, Kind.Control),
    CommittedCapacityAtZero(1, Kind.Control),
    CleanFrameNoComposition(0, Kind.Frame),
    CleanFrameActiveComposition(32, Kind.Frame),
    CommittedCanonicalEdit(1, Kind.Frame),
    CommittedNewlineEdit(1, Kind.Frame),
    ;

    /**
     * Every kind is a fixture construction rule, never a runtime domain discriminator.
     */
    private enum class Kind {
        Ascii,
        Cjk,
        Supplementary,
        Lf,
        Caret,
        FocusedBlock,
        Replacement,
        Cr,
        Crlf,
        MixedBreaks,
        LateConversion,
        Control,
        Frame,
    }

    /**
     * Immutable frozen inputs; current host ownership belongs exclusively to a benchmark worker.
     */
    internal data class Inputs(
        val committed: String,
        val maximum: Int,
        val seed: TextInputEvent.Preedit?,
        val events: List<TextInputEvent.Preedit>,
        val edit: TextInputEvent.Character? = null,
    ) {
        /**
         * Complete normalized capacity beside the unchanged committed prefix.
         */
        val remaining: Int get() = maximum - committed.length
    }

    /**
     * Frame controls never enter the normalizer-only matrix.
     */
    internal val frameOnly: Boolean get() = kind == Kind.Frame

    /**
     * Materializes the frozen bounded trace outside all timing.
     */
    internal fun inputs(): Inputs {
        if (kind == Kind.Control || kind == Kind.Frame) return control()
        val raw = rawText()
        val blocks = if (kind == Kind.Caret || kind == Kind.FocusedBlock) listOf(raw.substring(0, units / 2), raw.substring(units / 2)) else listOf(raw)
        val first = event(raw, if (kind == Kind.Caret) 0 else raw.length, blocks, 0)
        val second = alternate(first, raw, blocks)
        return Inputs("P", units + 1, first, List(64) { if (it % 2 == 0) second else first })
    }

    private fun rawText(): String =
        when (kind) {
            Kind.Cjk -> "日".repeat(units)
            Kind.Supplementary -> "🙂".repeat(units / 2)
            Kind.Lf -> "ABC\n".repeat(units / 4)
            Kind.Cr -> "\r".repeat(units)
            Kind.Crlf -> "\r\n".repeat(units / 2)
            Kind.MixedBreaks -> "\r\n\u000B\u000C\u0085\u2028\u2029A".repeat(units / 8)
            Kind.LateConversion -> "A".repeat(units - 1) + "\r"
            else -> "A".repeat(units)
        }

    private fun alternate(
        first: TextInputEvent.Preedit,
        raw: String,
        blocks: List<String>,
    ): TextInputEvent.Preedit =
        when (kind) {
            Kind.Caret -> event(raw, units / 2, blocks, 0)
            Kind.FocusedBlock -> event(raw, raw.length, blocks, 1)
            Kind.Replacement -> event("B" + raw.substring(1))
            else -> first
        }

    private fun control(): Inputs {
        val raw = controlText()
        val capacity = controlCapacity()
        val blocks = controlBlocks(raw)
        val input = event(raw, if (this == SupplementarySplitCaret) 1 else raw.length, blocks, if (this == EmptyBoundaryBlocks) 1 else 0)
        val seed =
            when (this) {
                EmptyClear -> event("Q")
                CleanFrameActiveComposition -> input
                else -> if (0 < capacity && frameOnly.not()) event("Q") else null
            }
        val remaining = if (this == EmptyClear) 1 else capacity
        val edit =
            when (this) {
                CommittedCanonicalEdit -> TextInputEvent.Character('A'.code)
                CommittedNewlineEdit -> TextInputEvent.Character('\r'.code)
                else -> null
            }
        return Inputs("P", remaining + 1, seed, List(64) { input }, edit)
    }

    private fun controlText(): String =
        when (this) {
            EmptyClear, CleanFrameNoComposition, CommittedCanonicalEdit, CommittedNewlineEdit -> ""
            ExactRemainingBudget, SupplementarySplitCaret, SplitSurrogateBlocks -> "🙂"
            OneUnitOverBudget -> "AA"
            RawTextOverTwiceBudget -> "AAA"
            UnsupportedScalar -> "A\u0001"
            CleanFrameActiveComposition -> "A".repeat(32)
            else -> "A"
        }

    private fun controlCapacity(): Int =
        when (this) {
            EmptyClear, CleanFrameNoComposition, CommittedCapacityAtZero -> 0
            ExactRemainingBudget, SupplementarySplitCaret, SplitSurrogateBlocks, UnsupportedScalar -> 2
            CleanFrameActiveComposition -> 32
            else -> 1
        }

    private fun controlBlocks(raw: String): List<String> =
        when (this) {
            BlockTotalOverTwiceBudget -> listOf("AAA")
            BlockCountOverBudget -> List(4) { "" }
            SplitSurrogateBlocks -> listOf("\uD83D", "\uDE42")
            BlockMismatch -> listOf("B")
            EmptyBoundaryBlocks -> listOf("", "A", "")
            else -> listOf(raw)
        }

    private fun event(
        value: String,
        caret: Int = value.length,
        blocks: List<String> = listOf(value),
        focused: Int = 0,
    ): TextInputEvent.Preedit = TextInputEvent.Preedit(value, caret, blocks, focused)
}
