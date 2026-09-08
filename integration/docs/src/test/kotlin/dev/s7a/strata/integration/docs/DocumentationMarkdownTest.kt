package dev.s7a.strata.integration.docs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifies GitHub heading collisions, explicit anchors, and fenced-example handling with in-memory documents.
 */
internal class DocumentationMarkdownTest {
    @Test
    fun generatesUnicodeAndFormattedHeadingSlugsWithCollisionSuffixes() {
        val source =
            """
            # Overview
            ## Overview
            ## Overview-1
            ## Overview
            ## `Row` & [Column](columns.md): **layout**! ###
            ## 日本語の見出し
            Setext heading
            --------------
            <h2 id="custom-anchor">Explicit</h2>
            <a name='named-anchor'></a>
            """.trimIndent()

        assertEquals(
            setOf("overview", "overview-1", "overview-1-1", "overview-2", "row--column-layout", "日本語の見出し", "setext-heading", "custom-anchor", "named-anchor"),
            DocumentationMarkdown.anchors(source, markdown = true),
        )
    }

    @Test
    fun extractsTitledAndHtmlLinksWithoutIncludingFencedExamples() {
        val source =
            """
            [Guide](guide.md#usage "Guide title")
            ![Image](<images/preview.png>)
            <a href='guide.md?theme=light&amp;view=full#usage'>Guide</a>
            ~~~markdown
            [Missing](missing.md)
            # Hidden
            ~~~
            ````markdown
            ```
            [Nested example](nested-missing.md)
            # Also hidden
            ```
            ````
            # Visible
            [Visible](#visible)
            """.trimIndent()

        assertEquals(
            listOf("guide.md#usage", "images/preview.png", "#visible", "guide.md?theme=light&view=full#usage"),
            DocumentationMarkdown.targets(source).toList(),
        )
        assertEquals(setOf("visible"), DocumentationMarkdown.anchors(source, markdown = true))
    }

    @Test
    fun requiresExplicitAnchorsInHtmlDocuments() {
        assertEquals(setOf("present"), DocumentationMarkdown.anchors("# Markdown\n<h1>Heading</h1><h2 id='present'>Present</h2>", markdown = false))
    }
}
