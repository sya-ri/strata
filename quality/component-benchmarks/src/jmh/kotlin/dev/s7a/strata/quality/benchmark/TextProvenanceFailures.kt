package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.quality.benchmark.TextProvenanceAssets.Family
import dev.s7a.strata.quality.benchmark.TextProvenanceBenchmark.Consumer
import dev.s7a.strata.quality.benchmark.TextProvenanceBenchmark.Shape
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.PlatformText
import dev.s7a.strata.text.UiText
import dev.s7a.strata.text.withFont

/**
 * Untimed original-construction failures with complete invalid tails and detached old-value retention.
 * Failure messages and classes come from the frozen original implementation, never candidate validation.
 */
internal object TextProvenanceFailures {
    /**
     * Requires failure parity before publication and before any glyph provider sees an invalid logical value.
     */
    @OptIn(InternalStrataRuntimeApi::class)
    internal fun verify() {
        val access = TextProvenanceAccess()
        val assets = TextProvenanceAssets(Shape.Single16384, Consumer.MultilineText)
        val owner = access.renderer(assets.profile) { assets.backend() }
        val old = access.create(assets.texts[0], Family.First.id, true)
        val original = TextProvenanceDenseReference.create(assets.texts[0], Family.First.id, true)
        val before = access.slice(old, 0, original.value.length)
        try {
            val prefix = UiText.Literal("A".repeat(16384)).withFont(Family.First.id)
            val invalid =
                listOf(
                    UiText.Literal("\uD800"),
                    UiText.Literal("\uDC00"),
                    UiText.Literal("A\uD800B"),
                    UiText.Literal("A§B"),
                    UiText.Translated("provenance.unresolved"),
                    UiText.Platform(UnresolvedPayload),
                )
            for (tail in invalid) {
                val text = UiText.concat(prefix, tail)
                for (multiline in listOf(false, true)) {
                    val expected = runCatching { TextProvenanceDenseReference.create(text, Family.First.id, multiline) }.exceptionOrNull()
                    val actual = checkNotNull(runCatching { access.create(text, Family.First.id, multiline) }.exceptionOrNull())
                    checkNotNull(expected)
                    check(actual.javaClass === expected.javaClass && actual.message == expected.message)
                }
                val calls = assets.glyphCalls
                val failure = runCatching { access.run(text, owner, Family.First.id, false) }.exceptionOrNull()
                check(failure is IllegalArgumentException && assets.glyphCalls == calls)
            }
            for (breakValue in listOf("\n", "\r", "\r\n", "\u000B", "\u000C", "\u0085", "\u2028", "\u2029")) {
                val text = UiText.concat(prefix, UiText.Literal(breakValue).withFont(Family.Second.id))
                val expected = runCatching { TextProvenanceDenseReference.create(text, Family.First.id, false) }.exceptionOrNull()
                val actual = checkNotNull(runCatching { access.create(text, Family.First.id, false) }.exceptionOrNull())
                checkNotNull(expected)
                check(actual.javaClass === expected.javaClass && actual.message == expected.message)
                val valid = access.create(text, Family.First.id, true)
                val reference = TextProvenanceDenseReference.create(text, Family.First.id, true)
                check(access.slice(valid, 0, reference.value.length) == reference.slice(0, reference.value.length))
            }
            check(access.slice(old, 0, original.value.length) == before)
        } finally {
            access.closeRenderer(owner)
        }
        check(assets.faces == 0 && assets.releases == assets.backends)
        check(access.slice(old, 0, original.value.length) == before)
    }

    private data object UnresolvedPayload : PlatformText
}
