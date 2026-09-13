package dev.s7a.strata.integration.docs

import java.util.Locale

/**
 * Extracts links and GitHub-style heading anchors from documentation text without shared mutable state or external I/O.
 * Fenced code is excluded so examples cannot advertise paths or satisfy prose anchors.
 */
internal object DocumentationMarkdown {
    /**
     * Extracts inline Markdown destinations and quoted HTML href/src attributes from unfenced prose.
     * Optional Markdown link titles and angle brackets do not form part of the returned URI text.
     *
     * @param text complete source document owned by the caller.
     * @return destinations in source-kind order, with HTML ampersand escapes decoded.
     */
    internal fun targets(text: String): Sequence<String> {
        val prose = prose(text)
        val markdown = MARKDOWN_TARGET.findAll(prose).map { match -> match.groupValues[1].removeSurrounding("<", ">") }
        val html = HTML_TARGET.findAll(prose).map { match -> match.groupValues[2] }
        return (markdown + html).map { target -> target.replace("&amp;", "&") }
    }

    /**
     * Collects explicit HTML id/name anchors and, for Markdown, ATX and setext heading slugs with collision suffixes.
     * All computation is local to the call, and HTML documents require explicit anchors.
     *
     * @param text complete source document owned by the caller.
     * @param markdown whether GitHub-rendered Markdown heading anchors are available.
     * @return exact case-sensitive anchor identifiers present in the document.
     */
    internal fun anchors(
        text: String,
        markdown: Boolean,
    ): Set<String> {
        val prose = prose(text)
        val explicit = HTML_ANCHOR.findAll(prose).map { match -> match.groupValues[2] }.toSet()
        return if (markdown) explicit + headingAnchors(prose) else explicit
    }

    private fun prose(text: String): String {
        var fence: String? = null
        return text.lineSequence().joinToString("\n") { line ->
            val marker = FENCE.find(line)?.value?.trimStart()
            val current = fence
            when {
                current == null && marker != null -> {
                    fence = marker
                    ""
                }

                current != null -> {
                    val matchingFence = marker != null && marker.first() == current.first() && current.length <= marker.length
                    if (matchingFence && line.trim() == marker) fence = null
                    ""
                }

                else -> {
                    line
                }
            }
        }
    }

    private fun headingAnchors(prose: String): Set<String> {
        val anchors = linkedSetOf<String>()
        val lines = prose.lines()
        lines.forEachIndexed { index, line ->
            val atx =
                ATX_HEADING
                    .matchEntire(line)
                    ?.groupValues
                    ?.get(1)
                    ?.replace(CLOSING_HASHES, "")
            val setext = if (0 < index && SETEXT_HEADING.matches(line)) lines[index - 1].trim().takeIf { previous -> previous.isNotEmpty() } else null
            val heading = atx ?: setext ?: return@forEachIndexed
            val slug = headingSlug(heading)
            var unique = slug
            var suffix = 0
            while (unique in anchors) {
                suffix += 1
                unique = "$slug-$suffix"
            }
            anchors.add(unique)
        }
        return anchors
    }

    private fun headingSlug(heading: String): String =
        heading
            .replace(MARKDOWN_LABEL, "$1")
            .replace(HTML_TAG, "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .lowercase(Locale.ROOT)
            .replace(SLUG_PUNCTUATION, "")
            .replace(' ', '-')

    private val MARKDOWN_TARGET = Regex("!?\\[(?:[^\\[\\]]|\\[[^]]*])*]\\((<[^>]+>|[^\\s)]+)(?:\\s+[^)]*)?\\)")
    private val MARKDOWN_LABEL = Regex("!?\\[([^]]*)]\\([^)]+\\)")
    private val HTML_TARGET = Regex("(?:src|href)\\s*=\\s*([\"'])(.*?)\\1", RegexOption.IGNORE_CASE)
    private val HTML_ANCHOR = Regex("(?:id|name)\\s*=\\s*([\"'])(.*?)\\1", RegexOption.IGNORE_CASE)
    private val HTML_TAG = Regex("<[^>]*>")
    private val FENCE = Regex("^ {0,3}(?:`{3,}|~{3,})")
    private val ATX_HEADING = Regex("^ {0,3}#{1,6}(?:[ \\t]+(.*?)|)[ \\t]*$")
    private val CLOSING_HASHES = Regex("[ \\t]+#+[ \\t]*$")
    private val SETEXT_HEADING = Regex("^ {0,3}(?:=+|-+)[ \\t]*$")
    private val SLUG_PUNCTUATION = Regex("[^\\p{L}\\p{M}\\p{N}_ -]")
}
