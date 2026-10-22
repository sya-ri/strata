package dev.s7a.strata.quality

import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifies visible declaration documentation and its intentional exemptions.
 */
internal class MultilineKDocRuleTest {
    @Test
    fun skipsEnumValuesButRequiresTheirMethods() {
        val source =
            DetektTestSources.read("multiline-kdoc-rule/skips-enum-values-but-requires-their-methods")

        assertEquals(1, MultilineKDocRule(Config.empty).lint(source).size)
    }

    @Test
    fun requiresDocumentationOnTheEnumType() {
        val source = DetektTestSources.read("multiline-kdoc-rule/requires-documentation-on-the-enum-type")

        assertEquals(1, MultilineKDocRule(Config.empty).lint(source).size)
    }

    @Test
    internal fun reportsMissingDocumentation() {
        val source =
            DetektTestSources.read("multiline-kdoc-rule/reports-missing-documentation")

        assertEquals(2, MultilineKDocRule(Config.empty).lint(source).size)
    }

    @Test
    internal fun acceptsDocumentedAndExemptDeclarations() {
        val source =
            DetektTestSources.read("multiline-kdoc-rule/accepts-documented-and-exempt-declarations")

        assertEquals(0, MultilineKDocRule(Config.empty).lint(source).size)
    }

    @Test
    internal fun skipsKnownJUnitAnnotations() {
        val source =
            DetektTestSources.read("multiline-kdoc-rule/skips-known-junit-annotations")

        assertEquals(0, MultilineKDocRule(Config.empty).lint(source).size)
    }

    @Test
    internal fun reportsUnknownTestSuffixAnnotation() {
        val source =
            DetektTestSources.read("multiline-kdoc-rule/reports-unknown-test-suffix-annotation")

        assertEquals(1, MultilineKDocRule(Config.empty).lint(source).size)
    }

    @Test
    internal fun skipsMembersOfPrivateTypes() {
        val source =
            DetektTestSources.read("multiline-kdoc-rule/skips-members-of-private-types")

        assertEquals(0, MultilineKDocRule(Config.empty).lint(source).size)
    }

    @Test
    internal fun rejectsOneLineKDoc() {
        val source =
            DetektTestSources.read("multiline-kdoc-rule/rejects-one-line-kdoc")

        assertEquals(1, MultilineKDocRule(Config.empty).lint(source).size)
    }

    @Test
    internal fun rejectsOneLineKDocOnPrivateProperty() {
        val source =
            DetektTestSources.read("multiline-kdoc-rule/rejects-one-line-kdoc-on-private-property")

        assertEquals(1, MultilineKDocRule(Config.empty).lint(source).size)
    }

    @Test
    internal fun rejectsOneLineKDocOnVisibleProperty() {
        val source =
            DetektTestSources.read("multiline-kdoc-rule/rejects-one-line-kdoc-on-visible-property")

        assertEquals(1, MultilineKDocRule(Config.empty).lint(source).size)
    }

    @Test
    fun rejectsUnmarkedBlankLinesAndEdgePadding() {
        val bodies =
            listOf(
                "\n * A documented value.",
                " * A documented value.\n",
                " * A documented value.\n   \n * More detail.",
                " *\n * A documented value.",
                " * A documented value.\n *",
            )
        for (body in bodies) {
            for (lineEnding in listOf("\n", "\r\n")) {
                val documentation = "/**\n$body\n */".replace("\n", lineEnding)
                val source = "$documentation${lineEnding}private val value = 0"

                assertEquals(1, MultilineKDocRule(Config.empty).lint(source).size, source)
            }
        }
    }

    @Test
    fun acceptsMarkedParagraphsAndTagSeparators() {
        val source =
            DetektTestSources.read("multiline-kdoc-rule/accepts-marked-paragraphs-and-tag-separators")

        assertEquals(0, MultilineKDocRule(Config.empty).lint(source).size)
    }

    @Test
    fun ignoresKDocDelimitersInStrings() {
        val documentation = "/**\n\n * Not a documentation comment.\n\n */"
        val source = "private val example = \"\"\"$documentation\"\"\""

        assertEquals(0, MultilineKDocRule(Config.empty).lint(source).size)
    }
}
