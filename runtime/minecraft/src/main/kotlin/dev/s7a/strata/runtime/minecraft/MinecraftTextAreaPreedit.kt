package dev.s7a.strata.runtime.minecraft

/**
 * One budgeted canonical composition and its precomputed focused source range.
 *
 * The editor replaces this value on input and drops it on focus loss, state replacement, or detach.
 * It retains no raw event or block strings, making wheel painting independent of the complete composition's length.
 * The value owns no renderer, caller state, callback, or history.
 *
 * @property fullText complete canonical composition, inserted only for presentation until committed input arrives.
 * @property caretPosition canonical UTF-16 caret offset within [fullText], always at a scalar boundary.
 * @property focusedRange canonical focused block range, or null when blocks do not describe the complete composition.
 * Empty focused blocks produce an empty range and no underline.
 */
internal data class MinecraftTextAreaPreedit(
    @get:JvmSynthetic internal val fullText: String,
    @get:JvmSynthetic internal val caretPosition: Int,
    @get:JvmSynthetic internal val focusedRange: IntRange?,
)
