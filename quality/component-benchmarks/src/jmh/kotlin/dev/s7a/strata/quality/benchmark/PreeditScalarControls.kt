package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.input.TextInputEvent

/**
 * Twenty-one independently specified scalar, budget, boundary, metadata and exception controls.
 */
internal object PreeditScalarControls {
    /**
     * Executes every golden control before matrix timing; none is fabricated as an editor operation.
     */
    internal fun verify(runtime: PreeditRuntime): List<String> {
        val controls = Golden(runtime).controls
        check(controls.size == 21)
        controls.values.forEach { it() }
        return controls.keys.toList()
    }

    private class Golden(runtime: PreeditRuntime) {
        val controls = linkedMapOf<String, () -> Unit>(
            "CanonicalAscii" to { PreeditScalarControls.accepted(runtime, PreeditScalarControls.event("ABC", 1), 3, "ABC", 1, 0..2) },
            "CanonicalCjk" to { PreeditScalarControls.accepted(runtime, PreeditScalarControls.event("日한", 1), 2, "日한", 1, 0..1) },
            "CanonicalSupplementary" to { PreeditScalarControls.accepted(runtime, PreeditScalarControls.event("🙂𐐀", 2), 4, "🙂𐐀", 2, 0..3) },
            "CanonicalLf" to { PreeditScalarControls.accepted(runtime, PreeditScalarControls.event("A\nB", 2), 3, "A\nB", 2, 0..2) },
            "MandatoryNonLf" to {
                listOf("\r", "\u000B", "\u000C", "\u0085", "\u2028", "\u2029").forEach {
                    PreeditScalarControls.accepted(runtime, PreeditScalarControls.event("A" + it + "B", 2), 3, "A\nB", 2, 0..2)
                }
            },
            "CrlfContraction" to { PreeditScalarControls.accepted(runtime, PreeditScalarControls.event("\r\n\r\n"), 2, "\n\n", 2, 0..1) },
            "EveryCrlfCaret" to {
                listOf(0, 1, 1, 2, 2).forEachIndexed { caret, expected ->
                    PreeditScalarControls.accepted(runtime, PreeditScalarControls.event("\r\n\r\n", caret), 2, "\n\n", expected, 0..1)
                }
            },
            "CrlfAcrossBlocks" to {
                val blocks = listOf("A\r", "\n", "B")
                for (caret in 0..4) PreeditScalarControls.accepted(runtime, PreeditScalarControls.event("A\r\nB", caret, blocks, 1), 3, "A\nB", listOf(0, 1, 2, 2, 3)[caret], 2 until 2)
            },
            "ExactNormalizedBudget" to { PreeditScalarControls.accepted(runtime, PreeditScalarControls.event("🙂\r\n"), 3, "🙂\n", 3, 0..2) },
            "OneUnitOverflow" to { PreeditScalarControls.rejected(runtime, PreeditScalarControls.event("🙂A"), 2) },
            "ZeroCapacityEmptyClear" to {
                PreeditScalarControls.accepted(runtime, TextInputEvent.Preedit("", 0, emptyList(), -1), 0, "", 0, null)
                PreeditScalarControls.rejected(runtime, PreeditScalarControls.event("A"), 0)
            },
            "RawTwiceBudget" to {
                PreeditScalarControls.accepted(runtime, PreeditScalarControls.event("\r\n"), 1, "\n", 1, 0..0)
                PreeditScalarControls.rejected(runtime, PreeditScalarControls.event("\r\nA"), 1)
                PreeditScalarControls.accepted(runtime, PreeditScalarControls.event("A"), Int.MAX_VALUE, "A", 1, 0..0)
            },
            "BlockTotalTwiceBudget" to {
                PreeditScalarControls.accepted(runtime, PreeditScalarControls.event("A", blocks = listOf("\r\n")), 1, "A", 1, null)
                PreeditScalarControls.rejected(runtime, PreeditScalarControls.event("A", blocks = listOf("\r\nA")), 1)
            },
            "BlockCountBudget" to {
                PreeditScalarControls.accepted(runtime, PreeditScalarControls.event("A", blocks = listOf("", "A", ""), focused = 1), 1, "A", 1, 0..0)
                PreeditScalarControls.rejected(runtime, PreeditScalarControls.event("A", blocks = List(4) { "" }), 1)
            },
            "InvalidFullText" to {
                listOf("\u0000", "\u001F", "\u007F", "§", "\uD83D", "\uDE42").forEach { PreeditScalarControls.rejected(runtime, PreeditScalarControls.event("A" + it, blocks = listOf("B")), 2) }
            },
            "InvalidBlocksDespiteMismatch" to {
                listOf("\u0000", "\u001F", "\u007F", "§", "\uD83D", "\uDE42").forEach {
                    PreeditScalarControls.rejected(runtime, PreeditScalarControls.event("A", blocks = listOf("B", it)), 2)
                }
            },
            "SurrogateSplitCaret" to { PreeditScalarControls.rejected(runtime, PreeditScalarControls.event("🙂", 1), 2) },
            "SurrogateSplitBlock" to { PreeditScalarControls.rejected(runtime, PreeditScalarControls.event("🙂", blocks = listOf("\uD83D", "\uDE42")), 2) },
            "MismatchDropsDecoration" to {
                PreeditScalarControls.accepted(runtime, PreeditScalarControls.event("A\r\nB", blocks = listOf("A\nB")), 3, "A\nB", 3, null)
                PreeditScalarControls.accepted(runtime, PreeditScalarControls.event("A", blocks = listOf("B")), 1, "A", 1, null)
            },
            "FocusedEmptyBoundaries" to {
                for (focused in 0..2) {
                    val range =
                        when (focused) {
                            0 -> 0 until 0
                            1 -> 0..1
                            else -> 2 until 2
                        }
                    PreeditScalarControls.accepted(runtime, PreeditScalarControls.event("🙂", blocks = listOf("", "🙂", ""), focused = focused), 2, "🙂", 2, range)
                }
            },
            "ConstructorAndNegativeCapacity" to {
                listOf<() -> Unit>(
                    { TextInputEvent.Preedit("A", -1, emptyList(), -1) },
                    { TextInputEvent.Preedit("A", 2, emptyList(), -1) },
                    { TextInputEvent.Preedit("A", 1, emptyList(), 0) },
                    { TextInputEvent.Preedit("A", 1, listOf("A"), -2) },
                    { runtime.normalize(PreeditScalarControls.event("A"), -1) },
                ).forEach { check(runCatching(it).exceptionOrNull() is IllegalArgumentException) }
            },
        )
    }

    private fun accepted(
        runtime: PreeditRuntime,
        event: TextInputEvent.Preedit,
        remaining: Int,
        text: String,
        caret: Int,
        focused: IntRange?,
    ) {
        // Compare endpoints explicitly so empty focused ranges preserve their mapped boundary.
        val expected = Triple(text, caret, focused?.let { it.first to it.last })
        val reference = checkNotNull(PreeditReference.normalize(event, remaining))
        check(Triple(reference.text, reference.caret, reference.focused?.let { it.first to it.last }) == expected)
        val actual = runtime.output(checkNotNull(runtime.normalize(event, remaining)))
        check(Triple(actual.first, actual.second, actual.third?.let { it.first to it.last }) == expected)
    }

    private fun rejected(
        runtime: PreeditRuntime,
        event: TextInputEvent.Preedit,
        remaining: Int,
    ) {
        check(PreeditReference.normalize(event, remaining) == null)
        check(runtime.normalize(event, remaining) == null)
    }

    private fun event(
        value: String,
        caret: Int = value.length,
        blocks: List<String> = listOf(value),
        focused: Int = 0,
    ): TextInputEvent.Preedit =
        TextInputEvent.Preedit(value, caret, blocks, focused)
}
