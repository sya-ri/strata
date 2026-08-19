package dev.s7a.strata.quality

import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifies visible declaration documentation and its intentional exemptions.
 */
internal class MultilineKDocRuleTest {
    /**
     * Reports undocumented visible classes and methods.
     */
    @Test
    internal fun reportsMissingDocumentation() {
        val source =
            """
            class Screen {
                fun render() = Unit
            }
            """.trimIndent()

        assertEquals(2, MultilineKDocRule(Config.empty).lint(source).size)
    }

    /**
     * Accepts multiline KDoc and skips overrides and standard test functions.
     */
    @Test
    internal fun acceptsDocumentedAndExemptDeclarations() {
        val source =
            """
            @Test
            fun testFunction() = Unit

            private fun hiddenFunction() = Unit

            /**
             * A documented screen.
             */
            open class Screen {
                /**
                 * Draws the screen.
                 */
                open fun render() = Unit

                override fun toString() = "screen"

                /**
                 * Draws with a local helper.
                 */
                fun renderWithLocalHelper() {
                    fun localHelper() = Unit
                    localHelper()
                }
            }
            """.trimIndent()

        assertEquals(0, MultilineKDocRule(Config.empty).lint(source).size)
    }

    /**
     * Skips the standard JUnit Jupiter test annotations in short and qualified forms.
     */
    @Test
    internal fun skipsKnownJUnitAnnotations() {
        val source =
            """
            @ParameterizedTest
            fun parameterizedFunction() = Unit

            @RepeatedTest
            fun repeatedFunction() = Unit

            @TestFactory
            fun factoryFunction() = Unit

            @TestTemplate
            fun templateFunction() = Unit

            @org.junit.jupiter.api.Test
            fun qualifiedTestFunction() = Unit

            @org.junit.jupiter.params.ParameterizedTest
            fun qualifiedParameterizedFunction() = Unit
            """.trimIndent()

        assertEquals(0, MultilineKDocRule(Config.empty).lint(source).size)
    }

    /**
     * Reports an unrelated annotation whose name happens to end with the word Test.
     */
    @Test
    internal fun reportsUnknownTestSuffixAnnotation() {
        val source =
            """
            @CustomTest
            fun customTestFunction() = Unit
            """.trimIndent()

        assertEquals(1, MultilineKDocRule(Config.empty).lint(source).size)
    }

    /**
     * Skips undocumented methods whose enclosing class is private.
     */
    @Test
    internal fun skipsMembersOfPrivateTypes() {
        val source =
            """
            private class HiddenScreen {
                fun render() = Unit
            }
            """.trimIndent()

        assertEquals(0, MultilineKDocRule(Config.empty).lint(source).size)
    }

    /**
     * Rejects a one-line KDoc even when a declaration has documentation text.
     */
    @Test
    internal fun rejectsOneLineKDoc() {
        val documentation = "/" + "** One-line documentation. " + "*/"
        val source =
            """
            $documentation
            class Screen
            """.trimIndent()

        assertEquals(1, MultilineKDocRule(Config.empty).lint(source).size)
    }

    /**
     * Rejects one-line KDoc on a private property even though private properties need no KDoc.
     */
    @Test
    internal fun rejectsOneLineKDocOnPrivateProperty() {
        val documentation = "/" + "** Private state. " + "*/"
        val source =
            """
            $documentation
            private val state = 0
            """.trimIndent()

        assertEquals(1, MultilineKDocRule(Config.empty).lint(source).size)
    }

    /**
     * Rejects one-line KDoc on a visible property while keeping missing visible-property documentation optional.
     */
    @Test
    internal fun rejectsOneLineKDocOnVisibleProperty() {
        val documentation = "/" + "** Public state. " + "*/"
        val source =
            """
            $documentation
            val state = 0
            """.trimIndent()

        assertEquals(1, MultilineKDocRule(Config.empty).lint(source).size)
    }
}
