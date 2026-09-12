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
            """
            /**
             * Named demo stages with their display captions.
             */
            enum class Stage(val caption: String) {
                Start("Start with a row") {
                    fun render() = Unit
                },
                End("Add scrolling"),
            }
            """.trimIndent()

        assertEquals(1, MultilineKDocRule(Config.empty).lint(source).size)
    }

    @Test
    fun requiresDocumentationOnTheEnumType() {
        val source = "enum class Stage { Start, End }"

        assertEquals(1, MultilineKDocRule(Config.empty).lint(source).size)
    }

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

    @Test
    internal fun reportsUnknownTestSuffixAnnotation() {
        val source =
            """
            @CustomTest
            fun customTestFunction() = Unit
            """.trimIndent()

        assertEquals(1, MultilineKDocRule(Config.empty).lint(source).size)
    }

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
